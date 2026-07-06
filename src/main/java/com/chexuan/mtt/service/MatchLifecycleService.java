package com.chexuan.mtt.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.common.MttMsgType;
import com.chexuan.mtt.core.MatchContext;
import com.chexuan.mtt.core.MatchRegistry;
import com.chexuan.mtt.entity.MttCompetitor;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.entity.MttRegistration;
import com.chexuan.mtt.gateway.GameServerClient;
import com.chexuan.mtt.repository.MttRepositories.MttCompetitorRepository;
import com.chexuan.mtt.repository.MttRepositories.MttMatchRepository;
import com.chexuan.mtt.repository.MttRepositories.MttRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 比赛生命周期编排（规划 §3/§5/§6/§8）
 *
 * 创建 → 报名期 → 开赛前60s人数校验+分桌 → 到点放行发牌 → 升底皮循环 → (淘汰/拆并桌在 HandReportService)
 * 取消/人数不足 → 批量退费 + DISMISS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchLifecycleService {

    /** 默认底皮级别表（规划 §8 模板）：[级别, 底皮] */
    public static final int[][] DEFAULT_LEVEL_TABLE =
            {{1, 10}, {2, 20}, {3, 30}, {4, 50}, {5, 80}, {6, 120}, {7, 200}};

    private final MttMatchRepository matchRepository;
    private final MttRegistrationRepository registrationRepository;
    private final MttCompetitorRepository competitorRepository;
    private final MatchRegistry registry;
    private final GameServerClient gameServer;
    private final RegistrationService registrationService;

    // ==================== 创建 / 取消 ====================

    public MttMatch create(MttMatch match) {
        if (match.getStartTime() == null || match.getStartTime() < System.currentTimeMillis() + 120_000L) {
            throw new IllegalStateException("开赛时间至少要在 2 分钟之后");
        }
        if (match.getClubId() == null || match.getClubId() <= 0) {
            throw new IllegalStateException("比赛必须挂在俱乐部下");
        }
        if (match.getSeatNum() == null || (match.getSeatNum() != 6 && match.getSeatNum() != 8)) {
            match.setSeatNum(8);
        }
        if (match.getLevelTable() == null || match.getLevelTable().isEmpty()) {
            match.setLevelTable(JSON.toJSONString(DEFAULT_LEVEL_TABLE));
        }
        if (match.getRewardRanking() == null || match.getRewardRanking().isEmpty()) {
            match.setRewardRanking("[50,30,20]");
        }
        // ⭐ 报名货币由赛事类型强制推导（金币赛=GOLD，钻石赛/实物赛=DIAMOND），不信任入参
        if (match.getRewardType() == null) {
            match.setRewardType(MttMatch.REWARD_GOLD);
        }
        match.setEntryCurrency(MttMatch.entryCurrencyOf(match.getRewardType()));
        // 实物赛必须配奖品清单（按名次可配多件，对齐德州 mtt_reward）
        if (match.getRewardType() == MttMatch.REWARD_PRIZE
                && (match.getPrizeList() == null || match.getPrizeList().isBlank())) {
            throw new IllegalStateException("实物赛必须配置奖品清单 prizeList（按名次，可配多件）");
        }
        match.setStatus(MttMatch.STATUS_CREATE);
        MttMatch saved = matchRepository.save(match);
        log.info("比赛创建: id={}, name={}, start={}, club={}", saved.getId(), saved.getName(),
                saved.getStartTime(), saved.getClubId());
        return saved;
    }

    /**
     * 取消/解散：退费 + 关桌 + 通知 + DISMISS。可反复调用（幂等，退费单笔幂等键）。
     */
    public void cancel(Long matchId, String reason) {
        MttMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalStateException("比赛不存在"));
        if (match.getStatus() == MttMatch.STATUS_FINISHED) {
            throw new IllegalStateException("比赛已结束，不能取消");
        }

        // 1. 关比赛桌（PLAYING 中取消）
        MatchContext ctx = registry.get(matchId);
        if (ctx != null) {
            for (Long roomId : ctx.getTableRoomIds()) {
                try {
                    gameServer.closeTable(roomId);
                } catch (Exception e) {
                    log.error("取消比赛关桌失败(不阻塞): roomId={}, err={}", roomId, e.getMessage());
                }
            }
        }

        // 2. 批量退费（逐笔幂等，失败的由心跳重试 ledger）
        registrationService.refundAll(match, "cancel");

        // 3. 通知所有报名玩家
        List<Long> userIds = registrationRepository
                .findByMatchIdAndStatus(matchId, MttRegistration.STATUS_REFUNDED)
                .stream().map(MttRegistration::getUserId).toList();
        Map<String, Object> data = new HashMap<>();
        data.put("matchId", matchId);
        data.put("reason", reason);
        gameServer.broadcastToUsers(userIds, MttMsgType.MATCH_CANCELLED, data);

        // 4. 状态置 DISMISS
        match.setStatus(MttMatch.STATUS_DISMISS);
        match.setDismissReason(reason);
        matchRepository.save(match);
        registry.remove(matchId);
        log.warn("比赛已取消: id={}, reason={}", matchId, reason);
    }

    // ==================== 心跳钩子（MttScheduler 每 10s 调） ====================

    /**
     * 装载未结束比赛到内存（含重启恢复）
     */
    public void loadUnfinished() {
        List<MttMatch> active = matchRepository.findByStatusIn(
                List.of(MttMatch.STATUS_CREATE, MttMatch.STATUS_PLAYING));
        for (MttMatch match : active) {
            MatchContext ctx = registry.get(match.getId());
            if (ctx != null) continue;

            ctx = registry.getOrCreate(match.getId());
            if (match.getStatus() == MttMatch.STATUS_PLAYING) {
                // 重启恢复：从 competitor 表重建桌集合；分桌一定已经发生过
                ctx.setStartTriggered(true);
                ctx.setTablesResumed(true); // 保守：重启后不再重复 resume（桌自己在跑）
                for (MttCompetitor c : competitorRepository.findByMatchIdAndStatus(
                        match.getId(), MttCompetitor.STATUS_ALIVE)) {
                    if (c.getRoomId() != null) ctx.getTableRoomIds().add(c.getRoomId());
                }
                log.info("重启恢复比赛: id={}, tables={}", match.getId(), ctx.getTableRoomIds());
            }
        }
    }

    /**
     * 开赛检查：距开赛 0~60s → 人数校验 + 分桌（一次性）；到点 → 放行发牌。
     */
    public void checkStart(MttMatch match) {
        MatchContext ctx = registry.getOrCreate(match.getId());
        long now = System.currentTimeMillis();
        long untilStart = match.getStartTime() - now;

        // —— ⭐ 开赛前 5 分钟：机器人自动报名（一次，需在 60s 报名截止线之前） ——
        if (match.getStatus() == MttMatch.STATUS_CREATE && untilStart <= 300_000L && untilStart > 90_000L
                && !ctx.isRobotsRegistered()
                && match.getRobotCount() != null && match.getRobotCount() > 0) {
            synchronized (ctx.getLock()) {
                if (!ctx.isRobotsRegistered()) {
                    ctx.setRobotsRegistered(true);
                    try {
                        registrationService.registerRobots(match);
                    } catch (Exception e) {
                        log.error("机器人自动报名异常: match={}", match.getId(), e);
                    }
                }
            }
        }

        // —— 开赛前 60s：分桌 ——
        if (match.getStatus() == MttMatch.STATUS_CREATE && untilStart <= 60_000L && !ctx.isStartTriggered()) {
            synchronized (ctx.getLock()) {
                if (ctx.isStartTriggered()) return;
                ctx.setStartTriggered(true);
            }

            long count = registrationRepository.countByMatchIdAndStatus(
                    match.getId(), MttRegistration.STATUS_REGISTERED);
            if (count < match.getLowerLimit()) {
                log.warn("人数不足解散: match={}, 报名={}, 下限={}", match.getId(), count, match.getLowerLimit());
                cancel(match.getId(), "报名人数不足，比赛解散，报名费已全额退还");
                return;
            }

            try {
                allocateTables(match, ctx);
                match.setStatus(MttMatch.STATUS_PLAYING);
                match.setParticipants((int) count);
                matchRepository.save(match);
            } catch (Exception e) {
                // 分桌失败：允许下一轮心跳重试
                log.error("分桌失败(下轮心跳重试): match={}", match.getId(), e);
                ctx.setStartTriggered(false);
            }
            return;
        }

        // —— 到开赛时间：放行发牌 ——
        if (match.getStatus() == MttMatch.STATUS_PLAYING && untilStart <= 0 && !ctx.isTablesResumed()) {
            synchronized (ctx.getLock()) {
                if (ctx.isTablesResumed()) return;
                ctx.setTablesResumed(true);
            }
            for (Long roomId : ctx.getTableRoomIds()) {
                try {
                    gameServer.resumeTable(roomId);
                } catch (Exception e) {
                    log.error("放行发牌失败: roomId={}", roomId, e);
                }
            }
            log.info("比赛正式开打: match={}, tables={}", match.getId(), ctx.getTableRoomIds().size());
        }
    }

    /**
     * 升底皮：时间反算应处级别（德州 upgradeBlind 思想，错过任意心跳都能追平）
     */
    public void checkLevelUpgrade(MttMatch match) {
        MatchContext ctx = registry.get(match.getId());
        if (ctx == null || !ctx.isTablesResumed()) return;

        long elapsedMs = System.currentTimeMillis() - match.getStartTime();
        if (elapsedMs < 0) return;

        int[][] table = parseLevelTable(match.getLevelTable());
        int shouldBe = (int) (elapsedMs / (match.getUpgradeMinutes() * 60_000L)) + 1;
        if (shouldBe > table.length) shouldBe = table.length; // 末级封顶

        if (shouldBe > ctx.getCurrentLevel()) {
            int baseScore = table[shouldBe - 1][1];
            ctx.setCurrentLevel(shouldBe);
            for (Long roomId : ctx.getTableRoomIds()) {
                try {
                    gameServer.upgradeLevel(roomId, shouldBe, baseScore);
                } catch (Exception e) {
                    log.error("升底皮下发失败(下轮追平): roomId={}", roomId, e);
                }
            }
            // 广播给所有存活玩家
            List<Long> alive = competitorRepository
                    .findByMatchIdAndStatus(match.getId(), MttCompetitor.STATUS_ALIVE)
                    .stream().map(MttCompetitor::getUserId).toList();
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", match.getId());
            data.put("level", shouldBe);
            data.put("baseScore", baseScore);
            gameServer.broadcastToUsers(alive, MttMsgType.LEVEL_UPGRADE, data);
            log.info("升底皮: match={}, level={}, baseScore={}", match.getId(), shouldBe, baseScore);
        }
    }

    // ==================== 分桌（规划 §6，德州均匀铺桌算法） ====================

    private void allocateTables(MttMatch match, MatchContext ctx) {
        List<MttRegistration> regs = registrationRepository.findByMatchIdAndStatus(
                match.getId(), MttRegistration.STATUS_REGISTERED);
        List<Long> userIds = new ArrayList<>(regs.stream().map(MttRegistration::getUserId).toList());
        Collections.shuffle(userIds);

        int total = userIds.size();
        int seatNum = match.getSeatNum();
        int roomNum = (total + seatNum - 1) / seatNum;
        int base = total / roomNum;
        int addition = total % roomNum;   // 前 addition 桌各多 1 人

        int[][] levelTable = parseLevelTable(match.getLevelTable());
        int level1BaseScore = levelTable[0][1];
        Map<String, Object> rules = match.getRuleTemplate() != null
                ? JSON.parseObject(match.getRuleTemplate())
                : new HashMap<>();

        int cursor = 0;
        for (int t = 0; t < roomNum; t++) {
            int size = base + (t < addition ? 1 : 0);
            List<Long> tableUsers = userIds.subList(cursor, cursor + size);
            cursor += size;

            Long roomId = gameServer.createTable(match.getId(),
                    match.getName() + "-第" + (t + 1) + "桌", match.getClubId(),
                    seatNum, level1BaseScore, rules);
            ctx.getTableRoomIds().add(roomId);

            List<Map<String, Object>> seatList = new ArrayList<>();
            int seatNo = 1;
            for (Long uid : tableUsers) {
                Map<String, Object> p = new HashMap<>();
                p.put("userId", uid);
                p.put("seatNo", seatNo);
                p.put("score", match.getInitialScore());
                seatList.add(p);

                MttCompetitor comp = competitorRepository
                        .findByMatchIdAndUserId(match.getId(), uid)
                        .orElseGet(MttCompetitor::new);
                comp.setMatchId(match.getId());
                comp.setUserId(uid);
                comp.setScore(match.getInitialScore());
                comp.setStatus(MttCompetitor.STATUS_ALIVE);
                comp.setRoomId(roomId);
                comp.setSeatNo(seatNo);
                competitorRepository.save(comp);
                seatNo++;
            }
            gameServer.seatPlayers(roomId, seatList);
            log.info("分桌: match={}, table#{} roomId={}, players={}", match.getId(), t + 1, roomId, size);
        }

        // 开赛提醒
        Map<String, Object> remind = new HashMap<>();
        remind.put("matchId", match.getId());
        remind.put("startTime", match.getStartTime());
        gameServer.broadcastToUsers(userIds, MttMsgType.MATCH_START_REMIND, remind);
    }

    // ==================== 工具 ====================

    public static int[][] parseLevelTable(String json) {
        if (json == null || json.isEmpty()) return DEFAULT_LEVEL_TABLE;
        try {
            JSONArray arr = JSON.parseArray(json);
            int[][] table = new int[arr.size()][2];
            for (int i = 0; i < arr.size(); i++) {
                JSONArray row = arr.getJSONArray(i);
                table[i][0] = row.getIntValue(0);
                table[i][1] = row.getIntValue(1);
            }
            return table.length > 0 ? table : DEFAULT_LEVEL_TABLE;
        } catch (Exception e) {
            return DEFAULT_LEVEL_TABLE;
        }
    }

    public static JSONObject toDetail(MttMatch m, long registeredCount, long aliveCount) {
        JSONObject o = JSON.parseObject(JSON.toJSONString(m));
        o.put("registeredCount", registeredCount);
        o.put("aliveCount", aliveCount);
        return o;
    }
}
