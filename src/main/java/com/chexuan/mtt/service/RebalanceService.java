package com.chexuan.mtt.service;

import com.chexuan.mtt.common.MttMsgType;
import com.chexuan.mtt.core.MatchContext;
import com.chexuan.mtt.entity.MttCompetitor;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.gateway.GameServerClient;
import com.chexuan.mtt.repository.MttCompetitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 拆并桌（规划 §10，一期简化实现）
 *
 * 一期用「全场暂停重排」一种机制统一覆盖德州的三种情况（常规均衡/拆超额桌/终局合台）：
 *   触发条件（每局上报后检查）：
 *     ① 目标桌数 = ceil(存活/每桌人数) < 当前桌数     —— 需要收桌
 *     ② 或 最多桌与最少桌人数差 ≥ 2                   —— 需要均衡
 *   流程：pauseTable 全部 → 收齐局间 ACK → 随机重排存活玩家(shuffle 防老对手聚堆)
 *         → transferPlayers 迁移 → 关空桌 → resumeTable → 解锁
 *
 * 德州的增量均衡(randomMergeRoom)作为二期优化——一期规模小，全场重排最简单且不会出错。
 * ⚠️ 本简化已同步至规划 HTML §10。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebalanceService {

    private final MttCompetitorRepository competitorRepository;
    private final GameServerClient gameServer;
    private final MatchLogService matchLog;

    /**
     * 拆并桌检查（在 ctx.lock 内被调用）
     */
    public void checkAndRebalance(MttMatch match, MatchContext ctx, List<MttCompetitor> alive) {
        if (ctx.isRebalancing()) {
            // 已有重排在途：收 ACK
            collectAckAndMaybeExecute(match, ctx);
            return;
        }

        Map<Long, List<MttCompetitor>> byRoom = groupByRoom(alive);
        int currentTables = byRoom.size();
        if (currentTables <= 1) return;

        int targetTables = (alive.size() + match.getSeatNum() - 1) / match.getSeatNum();
        int max = byRoom.values().stream().mapToInt(List::size).max().orElse(0);
        int min = byRoom.values().stream().mapToInt(List::size).min().orElse(0);

        boolean needShrink = targetTables < currentTables;
        boolean needBalance = max - min >= 2;
        if (!needShrink && !needBalance) return;

        // 触发重排：先全场请求暂停
        log.info("触发拆并桌: match={}, alive={}, tables={}→{}, max/min={}/{}",
                match.getId(), alive.size(), currentTables, targetTables, max, min);
        matchLog.match(match.getId(), "触发拆并桌: 存活=" + alive.size() + ", 桌数" + currentTables + "→" + targetTables
                + ", 最多/最少桌=" + max + "/" + min + " → 全场请求暂停");
        ctx.setRebalancing(true);
        ctx.getPausedAck().clear();

        for (Long roomId : byRoom.keySet()) {
            try {
                boolean pausedNow = gameServer.pauseTable(roomId);
                if (pausedNow) {
                    ctx.getPausedAck().add(roomId);
                }
            } catch (Exception e) {
                log.error("暂停指令失败(等tablePaused上报兜底): roomId={}", roomId, e);
            }
        }
        collectAckAndMaybeExecute(match, ctx);
    }

    /**
     * 主服局间暂停后上报 ACK（ReportController 调）
     */
    public void onTablePaused(MttMatch match, MatchContext ctx, Long roomId) {
        if (!ctx.isRebalancing()) return;
        ctx.getPausedAck().add(roomId);
        collectAckAndMaybeExecute(match, ctx);
    }

    private void collectAckAndMaybeExecute(MttMatch match, MatchContext ctx) {
        List<MttCompetitor> alive = competitorRepository.findByMatchIdAndStatus(
                match.getId(), MttCompetitor.STATUS_ALIVE);
        Map<Long, List<MttCompetitor>> byRoom = groupByRoom(alive);

        // 所有仍有人的桌都 ACK 了才执行
        for (Long roomId : byRoom.keySet()) {
            if (!ctx.getPausedAck().contains(roomId)) {
                log.info("拆并桌等待暂停ACK: match={}, 缺 roomId={}", match.getId(), roomId);
                return;
            }
        }
        executeReallocate(match, ctx, alive, byRoom);
    }

    /**
     * 全场随机重排（对齐德州 reorganizedAllRoom：shuffle + 顺序均匀铺桌）
     */
    private void executeReallocate(MttMatch match, MatchContext ctx,
                                   List<MttCompetitor> alive, Map<Long, List<MttCompetitor>> byRoom) {
        int seatNum = match.getSeatNum();
        int targetTables = Math.max(1, (alive.size() + seatNum - 1) / seatNum);

        // 保留人最多的前 targetTables 张桌（减少搬动人数），其余桌关掉
        List<Long> keepRooms = byRoom.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(targetTables)
                .map(Map.Entry::getKey)
                .toList();
        List<Long> closeRooms = byRoom.keySet().stream()
                .filter(r -> !keepRooms.contains(r))
                .toList();

        // 随机重排：全部玩家打乱后按 base/addition 均匀铺到保留桌
        List<MttCompetitor> shuffled = new ArrayList<>(alive);
        Collections.shuffle(shuffled);
        int base = alive.size() / targetTables;
        int addition = alive.size() % targetTables;

        List<Map<String, Object>> moves = new ArrayList<>();
        int cursor = 0;
        for (int t = 0; t < targetTables; t++) {
            Long roomId = keepRooms.get(t);
            int size = base + (t < addition ? 1 : 0);
            List<MttCompetitor> tablePlayers = shuffled.subList(cursor, cursor + size);
            cursor += size;

            int seatNo = 1;
            for (MttCompetitor comp : tablePlayers) {
                if (!roomId.equals(comp.getRoomId()) || comp.getSeatNo() == null || comp.getSeatNo() != seatNo) {
                    Map<String, Object> move = new HashMap<>();
                    move.put("userId", comp.getUserId());
                    move.put("fromRoomId", comp.getRoomId());
                    move.put("toRoomId", roomId);
                    move.put("toSeatNo", seatNo);
                    move.put("score", comp.getScore());
                    moves.add(move);
                }
                comp.setRoomId(roomId);
                comp.setSeatNo(seatNo);
                competitorRepository.save(comp);
                seatNo++;
            }
        }

        try {
            if (!moves.isEmpty()) {
                gameServer.transferPlayers(moves);
                for (Map<String, Object> m : moves) {
                    matchLog.room(match.getId(), (Long) m.get("toRoomId"),
                            "迁入: u" + m.get("userId") + " 从room-" + m.get("fromRoomId")
                                    + " 座位" + m.get("toSeatNo") + " 带分=" + m.get("score"));
                }
            }
            for (Long roomId : closeRooms) {
                gameServer.closeTable(roomId);
                ctx.getTableRoomIds().remove(roomId);
                matchLog.both(match.getId(), roomId, "拆并桌关桌 closeTable");
            }
            for (Long roomId : keepRooms) {
                gameServer.resumeTable(roomId);
                matchLog.room(match.getId(), roomId, "拆并桌完成恢复发牌 resumeTable");
            }
        } catch (Exception e) {
            // 指令失败：保持 rebalancing 状态，下一次上报/心跳重新收敛
            log.error("拆并桌执行失败(等待重试): match={}", match.getId(), e);
            matchLog.match(match.getId(), "⚠️ 拆并桌执行失败(等待重试): " + e.getMessage());
            return;
        }

        ctx.setRebalancing(false);
        ctx.getPausedAck().clear();

        Map<String, Object> data = new HashMap<>();
        data.put("matchId", match.getId());
        data.put("tables", targetTables);
        gameServer.broadcastToUsers(alive.stream().map(MttCompetitor::getUserId).toList(),
                MttMsgType.TABLE_REBALANCE, data);
        log.info("拆并桌完成: match={}, 保留桌={}, 关桌={}, 迁移={}人",
                match.getId(), keepRooms, closeRooms, moves.size());
        matchLog.match(match.getId(), "拆并桌完成: 保留桌=" + keepRooms + ", 关桌=" + closeRooms
                + ", 迁移=" + moves.size() + "人");
    }

    private Map<Long, List<MttCompetitor>> groupByRoom(List<MttCompetitor> alive) {
        Map<Long, List<MttCompetitor>> byRoom = new HashMap<>();
        for (MttCompetitor c : alive) {
            if (c.getRoomId() == null) continue;
            byRoom.computeIfAbsent(c.getRoomId(), k -> new ArrayList<>()).add(c);
        }
        return byRoom;
    }
}
