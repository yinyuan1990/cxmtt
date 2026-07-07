package com.chexuan.mtt.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.common.MttMsgType;
import com.chexuan.mtt.core.MatchContext;
import com.chexuan.mtt.core.MatchRegistry;
import com.chexuan.mtt.entity.MttCompetitor;
import com.chexuan.mtt.entity.MttHandReport;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.gateway.GameServerClient;
import com.chexuan.mtt.repository.MttCompetitorRepository;
import com.chexuan.mtt.repository.MttHandReportRepository;
import com.chexuan.mtt.repository.MttMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 每局上报处理（规划 §9 淘汰与排名）
 *
 * 主服在 prepareNextRound 上报：{matchId, roomId, handNo, players:[{userId, scoreAfter, eliminated}]}
 * - eliminated=true 由主服判定（记分牌不足下一手门槛，方案B 直接淘汰）
 * - 名次倒序：rank = participants - 已淘汰累计 + 1；同局多人按 scoreAfter 升序先出
 * - 处理完触发拆并桌检查（RebalanceService）与终局检查（PayoutService）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandReportService {

    private final MttMatchRepository matchRepository;
    private final MttCompetitorRepository competitorRepository;
    private final MttHandReportRepository handReportRepository;
    private final MatchRegistry registry;
    private final GameServerClient gameServer;
    private final RebalanceService rebalanceService;
    private final PayoutService payoutService;
    private final MatchLogService matchLog;

    /**
     * 幂等入口：同 (matchId, roomId, handNo) 只处理一次。
     */
    public void onHandResult(Long matchId, Long roomId, Integer handNo, JSONArray players) {
        MttMatch match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getStatus() != MttMatch.STATUS_PLAYING) {
            log.warn("上报忽略(比赛不在进行中): match={}, room={}, hand={}", matchId, roomId, handNo);
            return;
        }

        // 幂等存档
        MttHandReport report = new MttHandReport();
        report.setMatchId(matchId);
        report.setRoomId(roomId);
        report.setHandNo(handNo);
        report.setPlayers(players.toJSONString());
        try {
            handReportRepository.save(report);
        } catch (DataIntegrityViolationException dup) {
            log.info("重复上报忽略: match={}, room={}, hand={}", matchId, roomId, handNo);
            return;
        }

        MatchContext ctx = registry.getOrCreate(matchId);
        synchronized (ctx.getLock()) {
            ctx.getTableRoomIds().add(roomId);
            processInLock(match, ctx, roomId, handNo, players);
        }
    }

    private void processInLock(MttMatch match, MatchContext ctx, Long roomId, Integer handNo, JSONArray players) {
        // ⭐ 按房间记一行本局摘要（排查赛况的主日志）
        StringBuilder handLine = new StringBuilder("第" + handNo + "局结束 上报: ");
        for (int i = 0; i < players.size(); i++) {
            JSONObject p = players.getJSONObject(i);
            if (i > 0) handLine.append(", ");
            handLine.append("u").append(p.getLong("userId"))
                    .append("=").append(p.getLongValue("scoreAfter"));
            if (p.getBooleanValue("eliminated")) handLine.append("(淘汰)");
        }
        matchLog.room(match.getId(), roomId, handLine.toString());

        // 1. 刷新记分牌
        Set<Long> reportedUserIds = new HashSet<>();
        List<MttCompetitor> eliminatedNow = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            JSONObject p = players.getJSONObject(i);
            Long uid = p.getLong("userId");
            reportedUserIds.add(uid);
            long scoreAfter = p.getLongValue("scoreAfter");
            boolean eliminated = p.getBooleanValue("eliminated");

            MttCompetitor comp = competitorRepository.findByMatchIdAndUserId(match.getId(), uid).orElse(null);
            if (comp == null || comp.getStatus() == MttCompetitor.STATUS_ELIMINATED) continue;

            comp.setScore(Math.max(0L, scoreAfter));
            if (eliminated || scoreAfter <= 0) {
                eliminatedNow.add(comp);
            } else {
                competitorRepository.save(comp);
            }
        }

        // 1.5 ⭐ 弃赛自愈:登记在本桌的存活者不在上报名单里 = 已被主服移除(弃赛/防御路径)
        //     → 记分牌清零按淘汰处理,防止"幽灵存活者"卡终局判定
        for (MttCompetitor comp : competitorRepository.findByMatchIdAndStatus(
                match.getId(), MttCompetitor.STATUS_ALIVE)) {
            if (roomId.equals(comp.getRoomId()) && !reportedUserIds.contains(comp.getUserId())) {
                comp.setScore(0L);
                eliminatedNow.add(comp);
                log.warn("弃赛自愈判淘汰: match={}, user={}, room={}, hand={}",
                        match.getId(), comp.getUserId(), roomId, handNo);
                matchLog.room(match.getId(), roomId, "弃赛自愈: u" + comp.getUserId() + " 不在上报名单 → 记分牌清零判淘汰");
            }
        }

        // 2. 淘汰定名次（倒序法；同局多人按出局时记分牌升序先出 = 名次更差）
        if (!eliminatedNow.isEmpty()) {
            eliminatedNow.sort(Comparator.comparingLong(MttCompetitor::getScore));
            int participants = match.getParticipants() != null ? match.getParticipants() : 0;
            long alreadyOut = competitorRepository.countByMatchIdAndStatus(
                    match.getId(), MttCompetitor.STATUS_ELIMINATED);

            for (MttCompetitor comp : eliminatedNow) {
                alreadyOut++;
                int rank = (int) (participants - alreadyOut + 1);
                comp.setStatus(MttCompetitor.STATUS_ELIMINATED);
                comp.setRankNo(rank);
                comp.setScore(0L);
                comp.setEliminateHandNo(handNo);
                comp.setEliminateLevel(ctx.getCurrentLevel());
                competitorRepository.save(comp);

                Map<String, Object> data = new HashMap<>();
                data.put("matchId", match.getId());
                data.put("rank", rank);
                gameServer.broadcastToUsers(List.of(comp.getUserId()), MttMsgType.ELIMINATED, data);
                log.info("淘汰: match={}, user={}, rank={}, hand={}", match.getId(), comp.getUserId(), rank, handNo);
                matchLog.both(match.getId(), roomId, "淘汰: u" + comp.getUserId() + " 名次=" + rank
                        + " (第" + handNo + "局, 底皮Lv" + ctx.getCurrentLevel() + ")");
            }
        }

        // 3. 存活名单
        List<MttCompetitor> alive = competitorRepository.findByMatchIdAndStatus(
                match.getId(), MttCompetitor.STATUS_ALIVE);

        // 3.5 进奖励圈通知（只发一次：存活人数首次 <= 奖励名次数）
        notifyRewardCircleIfNeeded(match, ctx, alive);

        // 4. 终局：只剩 1 人 → 冠军 + 发奖
        if (alive.size() <= 1) {
            finishMatch(match, ctx, alive);
            return;
        }

        // 5. 拆并桌检查（决策 + 指令下发）
        rebalanceService.checkAndRebalance(match, ctx, alive);
    }

    /**
     * 进奖励圈通知（363）：
     *   实物赛：存活人数首次 ≤ prizeList 配置的名次数（进圈=有奖品拿）；
     *   钻石赛：存活人数首次 ≤ rewardRanking 名次数（进圈=有钻石分）；
     *   金币赛：冠军通吃无奖励圈概念，剩 2 人时通知「决赛对决」。
     */
    private void notifyRewardCircleIfNeeded(MttMatch match, MatchContext ctx, List<MttCompetitor> alive) {
        if (ctx.isRewardCircleNotified()) return;
        int type = match.getRewardType() != null ? match.getRewardType() : MttMatch.REWARD_GOLD;
        int rewardRankCount;
        if (type == MttMatch.REWARD_PRIZE) {
            rewardRankCount = parsePrizeRankCount(match.getPrizeList());
        } else if (type == MttMatch.REWARD_DIAMOND) {
            rewardRankCount = parseRankingCount(match.getRewardRanking());
        } else {
            rewardRankCount = 2; // 金币赛：剩2人=决赛圈
        }
        if (rewardRankCount <= 0 || alive.size() > rewardRankCount) return;

        ctx.setRewardCircleNotified(true);
        Map<String, Object> data = new HashMap<>();
        data.put("matchId", match.getId());
        data.put("aliveCount", alive.size());
        gameServer.broadcastToUsers(alive.stream().map(MttCompetitor::getUserId).toList(),
                MttMsgType.REWARD_CIRCLE, data);
        log.info("进入奖励圈/决赛圈: match={}, alive={}", match.getId(), alive.size());
        matchLog.match(match.getId(), "进入奖励圈/决赛圈: 存活=" + alive.size()
                + " → " + alive.stream().map(c -> "u" + c.getUserId()).toList());
    }

    private void finishMatch(MttMatch match, MatchContext ctx, List<MttCompetitor> alive) {
        if (ctx.isFinished()) return;
        ctx.setFinished(true);

        if (!alive.isEmpty()) {
            MttCompetitor champion = alive.get(0);
            champion.setRankNo(1);
            champion.setStatus(MttCompetitor.STATUS_ELIMINATED); // 终态：比赛结束
            competitorRepository.save(champion);
            match.setChampionUserId(champion.getUserId());
            matchRepository.save(match);
            log.info("冠军产生: match={}, user={}", match.getId(), champion.getUserId());
            matchLog.match(match.getId(), "🏆 冠军产生: u" + champion.getUserId()
                    + ", 记分牌=" + champion.getScore());
        }

        // 关掉所有比赛桌
        for (Long roomId : ctx.getTableRoomIds()) {
            try {
                gameServer.closeTable(roomId);
                matchLog.room(match.getId(), roomId, "终局关桌 closeTable");
            } catch (Exception e) {
                log.error("终局关桌失败: roomId={}", roomId, e);
                matchLog.room(match.getId(), roomId, "⚠️ 终局关桌失败: " + e.getMessage());
            }
        }

        // 生成发奖批次（心跳负责逐笔入账，规划 §11.4）
        payoutService.createPayoutBatch(match);
    }

    /** 钻石赛名次比例数组的名次数 */
    public static int parseRankingCount(String rewardRankingJson) {
        try {
            JSONArray arr = JSON.parseArray(rewardRankingJson);
            return arr != null ? arr.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 实物赛奖品清单里配置的名次数（去重 rank） */
    public static int parsePrizeRankCount(String prizeListJson) {
        try {
            JSONArray arr = JSON.parseArray(prizeListJson);
            Set<Integer> ranks = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                ranks.add(arr.getJSONObject(i).getIntValue("rank"));
            }
            return ranks.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
