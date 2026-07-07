package com.chexuan.mtt.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.common.MttMsgType;
import com.chexuan.mtt.config.MttProperties;
import com.chexuan.mtt.entity.*;
import com.chexuan.mtt.gateway.GameServerClient;
import com.chexuan.mtt.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 奖励结算（规划 §11，2026-07 四次定版）
 *
 * 金币赛（记分牌即金币，冠军通吃）：
 *   输赢在牌局中已通过记分牌流动完成；终局只有一笔兑付：冠军把记分牌换回金币，
 *   金额 = participants × entryFee + initialPool。其余名次只记录排名。
 *
 * 钻石赛（虚拟记分牌，奖池按名次比例分）：
 *   奖池 = participants × entryFee × (100−platformFeePercent)/100 + initialPool
 *   按 rewardRanking 名次比例发钻石给前几名（ledger 幂等，心跳断点续跑）。
 *   手续费部分 = 平台留存。
 *
 * 实物赛：
 *   报名钻石=平台留存，记分牌纯内存；按名次生成 mtt_prize_grant 发放单（可多件），
 *   玩家填收货地址 → 运营后台派送核销。不走 ledger 金额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final MttMatchRepository matchRepository;
    private final MttCompetitorRepository competitorRepository;
    private final MttPayoutBatchRepository payoutBatchRepository;
    private final MttPrizeGrantRepository prizeGrantRepository;
    private final LedgerService ledgerService;
    private final GameServerClient gameServer;
    private final MttProperties properties;
    private final MatchLogService matchLog;

    /**
     * 冠军产生时调用：计算奖池 + 生成批次（CALCULATED）+ 实物发放单。幂等（match 唯一批次）。
     */
    public void createPayoutBatch(MttMatch match) {
        if (payoutBatchRepository.findByMatchId(match.getId()).isPresent()) {
            return;
        }

        int participants = match.getParticipants() != null ? match.getParticipants() : 0;
        long totalBonus;
        if (match.getRewardType() == MttMatch.REWARD_PRIZE) {
            // 实物赛：无货币奖池（报名钻石=平台留存，奖励只有实物）
            totalBonus = 0L;
        } else if (match.getRewardType() == MttMatch.REWARD_DIAMOND) {
            // ⭐ 钻石赛：报名费总和 − 平台手续费 + 固定奖池
            long gross = participants * match.getEntryFee();
            int feePercent = match.getPlatformFeePercent() != null ? match.getPlatformFeePercent() : 10;
            long platformFee = gross * feePercent / 100;
            totalBonus = gross - platformFee + match.getInitialPool();
            matchLog.match(match.getId(), "钻石赛奖池核算: 报名费总和=" + gross
                    + ", 平台手续费(" + feePercent + "%)=" + platformFee
                    + ", 固定奖池=" + match.getInitialPool() + ", 可分奖池=" + totalBonus);
        } else {
            // 金币赛：冠军通吃
            totalBonus = participants * match.getEntryFee() + match.getInitialPool();
        }
        match.setTotalBonus(totalBonus);
        matchRepository.save(match);

        // 名次 → 玩家
        Map<Integer, MttCompetitor> byRank = new HashMap<>();
        for (MttCompetitor c : competitorRepository.findByMatchId(match.getId())) {
            if (c.getRankNo() != null) byRank.put(c.getRankNo(), c);
        }

        List<Map<String, Object>> detail = new ArrayList<>();

        if (match.getRewardType() == MttMatch.REWARD_PRIZE) {
            // ⭐ 实物赛：按名次发实物（对齐德州 mtt_reward——每个名次一条，可配多件）
            //   记分牌纯内存字段，比赛结束作废；发放单等玩家填收货地址后运营派送
            Map<Integer, JSONObject> prizeByRank = new HashMap<>();
            try {
                JSONArray arr = JSON.parseArray(match.getPrizeList());
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    prizeByRank.put(p.getIntValue("rank"), p);
                }
            } catch (Exception e) {
                log.error("prizeList 解析失败: match={}", match.getId(), e);
            }

            for (Map.Entry<Integer, JSONObject> e : prizeByRank.entrySet()) {
                int rank = e.getKey();
                MttCompetitor comp = byRank.get(rank);
                if (comp == null) continue; // 并列名次空档

                JSONObject prize = e.getValue();
                comp.setIsReward(true);
                MttPrizeGrant grant = new MttPrizeGrant();
                grant.setMatchId(match.getId());
                grant.setUserId(comp.getUserId());
                grant.setRankNo(rank);
                grant.setPrizeName(prize.getString("prizeName"));
                grant.setPrizeIcon(prize.getString("prizeIcon"));
                grant.setIsVirtual(Boolean.TRUE.equals(prize.getBoolean("isVirtual")));
                prizeGrantRepository.save(grant);
                competitorRepository.save(comp);

                Map<String, Object> d = new HashMap<>();
                d.put("rank", rank);
                d.put("userId", comp.getUserId());
                d.put("prizeName", grant.getPrizeName());
                detail.add(d);

                // 367 到账通知（实物：提示去「我的奖品」填收货地址）
                Map<String, Object> notice = new HashMap<>();
                notice.put("matchId", match.getId());
                notice.put("rank", rank);
                notice.put("prizeName", grant.getPrizeName());
                notice.put("needAddress", !Boolean.TRUE.equals(grant.getIsVirtual()));
                gameServer.broadcastToUsers(List.of(comp.getUserId()), MttMsgType.REWARD_ARRIVED, notice);
            }
        } else if (match.getRewardType() == MttMatch.REWARD_DIAMOND) {
            // ⭐ 钻石赛：奖池按 rewardRanking 名次比例分给前几名（钻石）
            List<Integer> percents = parsePercents(match.getRewardRanking());
            for (int rank = 1; rank <= percents.size(); rank++) {
                MttCompetitor comp = byRank.get(rank);
                if (comp == null) continue; // 并列名次空档

                long amount = totalBonus * percents.get(rank - 1) / 100; // 向下取整
                if (amount <= 0) continue;
                comp.setIsReward(true);
                comp.setRewardAmount(amount);
                competitorRepository.save(comp);

                Map<String, Object> d = new HashMap<>();
                d.put("rank", rank);
                d.put("userId", comp.getUserId());
                d.put("amount", amount);
                d.put("currency", LedgerEntry.CURRENCY_DIAMOND);
                detail.add(d);
            }
        } else {
            // ⭐ 金币赛（冠军通吃）：全场记分牌已集中在冠军手里，
            //   终局唯一一笔兑付 = 冠军记分牌换回金币（按公式口径，不读桌面筹码）
            MttCompetitor champion = byRank.get(1);
            if (champion != null && totalBonus > 0) {
                champion.setIsReward(true);
                champion.setRewardAmount(totalBonus);
                competitorRepository.save(champion);

                Map<String, Object> d = new HashMap<>();
                d.put("rank", 1);
                d.put("userId", champion.getUserId());
                d.put("amount", totalBonus);
                d.put("currency", LedgerEntry.CURRENCY_GOLD);
                detail.add(d);
            }
        }

        MttPayoutBatch batch = new MttPayoutBatch();
        batch.setMatchId(match.getId());
        batch.setDetail(JSON.toJSONString(detail));
        payoutBatchRepository.save(batch);
        log.info("发奖批次生成: match={}, totalBonus={}, 名次数={}", match.getId(), totalBonus, detail.size());
        matchLog.match(match.getId(), "发奖批次生成: 冠军兑付/奖池=" + totalBonus + ", 明细=" + JSON.toJSONString(detail));
    }

    /**
     * 心跳入口：处理 CALCULATED/PAYING 批次，逐笔幂等入账；全 DONE → PAID + 比赛 FINISHED。
     */
    public void processPendingBatches() {
        List<MttPayoutBatch> batches = payoutBatchRepository.findByStatusIn(
                List.of(MttPayoutBatch.STATUS_CALCULATED, MttPayoutBatch.STATUS_PAYING));
        for (MttPayoutBatch batch : batches) {
            try {
                processBatch(batch);
            } catch (Exception e) {
                log.error("发奖批次处理异常(下轮重试): batch={}", batch.getId(), e);
            }
        }
    }

    private void processBatch(MttPayoutBatch batch) {
        MttMatch match = matchRepository.findById(batch.getMatchId()).orElse(null);
        if (match == null) return;

        batch.setStatus(MttPayoutBatch.STATUS_PAYING);
        payoutBatchRepository.save(batch);

        JSONArray detail = JSON.parseArray(batch.getDetail());
        boolean allDone = true;
        for (int i = 0; i < detail.size(); i++) {
            JSONObject item = detail.getJSONObject(i);
            if (item.getString("prizeName") != null) continue; // 实物走发放单，无账务

            Long userId = item.getLong("userId");
            long amount = item.getLongValue("amount");
            String currency = item.getString("currency");
            int rank = item.getIntValue("rank");
            String idem = "m" + match.getId() + ":reward:u" + userId;
            try {
                ledgerService.post(match.getId(), userId, match.getClubId(),
                        LedgerEntry.TYPE_REWARD_PAYOUT, currency, amount, idem);

                Map<String, Object> data = new HashMap<>();
                data.put("matchId", match.getId());
                data.put("rank", rank);
                data.put("amount", amount);
                data.put("currency", currency);
                gameServer.broadcastToUsers(List.of(userId), MttMsgType.REWARD_ARRIVED, data);
                matchLog.match(match.getId(), "发奖入账: u" + userId + " 名次=" + rank
                        + " +" + amount + " " + currency);
            } catch (Exception e) {
                allDone = false;
                log.warn("发奖入账失败(可重试): match={}, user={}, err={}", match.getId(), userId, e.getMessage());
                matchLog.match(match.getId(), "⚠️ 发奖入账失败(心跳重试): u" + userId + ", " + e.getMessage());
            }
        }

        if (allDone) {
            batch.setStatus(MttPayoutBatch.STATUS_PAID);
            payoutBatchRepository.save(batch);
            match.setStatus(MttMatch.STATUS_FINISHED);
            matchRepository.save(match);
            log.info("发奖完成，比赛结束: match={}", match.getId());
            matchLog.match(match.getId(), "✅ 发奖完成, 比赛状态→FINISHED");
        } else {
            batch.setRetryCount(batch.getRetryCount() + 1);
            if (batch.getRetryCount() >= properties.getPayoutMaxRetry()) {
                batch.setStatus(MttPayoutBatch.STATUS_FAILED);
                log.error("⚠️ 发奖批次连续失败{}次，转人工介入: match={}, batch={}",
                        batch.getRetryCount(), match.getId(), batch.getId());
            }
            payoutBatchRepository.save(batch);
        }
    }

    private List<Integer> parsePercents(String rewardRankingJson) {
        try {
            List<Integer> list = JSON.parseArray(rewardRankingJson, Integer.class);
            return list != null && !list.isEmpty() ? list : List.of(50, 30, 20);
        } catch (Exception e) {
            return List.of(50, 30, 20);
        }
    }

    /**
     * 对账不变量（规划 §11.4，AdminController /reconcile 手动触发或每日 Job）
     */
    public Map<String, Object> reconcile(Long matchId, List<LedgerEntry> entries, MttMatch match) {
        long feeSum = 0, refundSum = 0, rewardSum = 0;
        for (LedgerEntry e : entries) {
            if (!LedgerEntry.STATUS_DONE.equals(e.getStatus())) continue;
            switch (e.getEntryType()) {
                case LedgerEntry.TYPE_ENTRY_FEE -> feeSum += -e.getAmount();
                case LedgerEntry.TYPE_ENTRY_REFUND -> refundSum += e.getAmount();
                case LedgerEntry.TYPE_REWARD_PAYOUT -> rewardSum += e.getAmount();
            }
        }
        long expectPool = match.getTotalBonus() != null ? match.getTotalBonus()
                : feeSum - refundSum + match.getInitialPool();
        boolean prizeMatch = match.getRewardType() != null && match.getRewardType() == MttMatch.REWARD_PRIZE;
        boolean diamondMatch = match.getRewardType() != null && match.getRewardType() == MttMatch.REWARD_DIAMOND;
        int feePercent = match.getPlatformFeePercent() != null ? match.getPlatformFeePercent() : 10;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("matchId", matchId);
        r.put("entryFeeSum", feeSum);
        r.put("refundSum", refundSum);
        r.put("rewardPayoutSum", rewardSum);
        r.put("initialPool", match.getInitialPool());
        r.put("totalBonus", match.getTotalBonus());
        // 实物赛：报名钻石=平台留存(不构成货币奖池)，无货币发奖 → 只校验 rewardSum==0
        r.put("invariant_reward_le_pool", prizeMatch ? rewardSum == 0 : rewardSum <= expectPool);
        if (diamondMatch) {
            // 钻石赛：奖池 = 净报名费×(100−手续费%)/100 + initialPool
            long netFee = feeSum - refundSum;
            long expectDiamondPool = netFee * (100 - feePercent) / 100 + match.getInitialPool();
            r.put("platformFeePercent", feePercent);
            r.put("platformFee", netFee * feePercent / 100); // 平台留存(手续费)
            r.put("invariant_pool_eq_fee", match.getTotalBonus() == null
                    || match.getStatus() == MttMatch.STATUS_DISMISS
                    || expectDiamondPool == match.getTotalBonus());
        } else {
            r.put("invariant_pool_eq_fee", prizeMatch
                    || match.getTotalBonus() == null
                    || match.getStatus() == MttMatch.STATUS_DISMISS
                    || feeSum - refundSum + match.getInitialPool() == match.getTotalBonus());
        }
        if (prizeMatch) {
            r.put("platformRevenue", feeSum - refundSum); // 实物赛平台留存(钻石)
        }
        return r;
    }

}
