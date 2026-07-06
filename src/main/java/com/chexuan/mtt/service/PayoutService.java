package com.chexuan.mtt.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.common.MttMsgType;
import com.chexuan.mtt.config.MttProperties;
import com.chexuan.mtt.entity.*;
import com.chexuan.mtt.gateway.GameServerClient;
import com.chexuan.mtt.repository.MttRepositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 奖励结算（规划 §11）
 *
 * 奖池公式（一期无重购/猎人）: totalBonus = participants × entryFee + initialPool
 * 奖励类型（§11.2.5）：
 *   1 积分 / 2 钻石 → ledger 逐笔幂等入账（发奖批次状态机，心跳断点续跑）
 *   3 实物         → mtt_prize_grant 发放单（运营核销）；名次命中 prizeList 发实物，
 *                    未命中的奖励圈名次发积分（货币奖池只在货币名次间分）
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

    /**
     * 冠军产生时调用：计算奖池 + 生成批次（CALCULATED）+ 实物发放单。幂等（match 唯一批次）。
     */
    public void createPayoutBatch(MttMatch match) {
        if (payoutBatchRepository.findByMatchId(match.getId()).isPresent()) {
            return;
        }

        int participants = match.getParticipants() != null ? match.getParticipants() : 0;
        long totalBonus = participants * match.getEntryFee() + match.getInitialPool();
        match.setTotalBonus(totalBonus);
        matchRepository.save(match);

        // 名次 → 玩家
        Map<Integer, MttCompetitor> byRank = new HashMap<>();
        for (MttCompetitor c : competitorRepository.findByMatchId(match.getId())) {
            if (c.getRankNo() != null) byRank.put(c.getRankNo(), c);
        }

        // 实物名次集合
        Map<Integer, JSONObject> prizeByRank = new HashMap<>();
        if (match.getRewardType() == MttMatch.REWARD_PRIZE && match.getPrizeList() != null) {
            try {
                JSONArray arr = JSON.parseArray(match.getPrizeList());
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    prizeByRank.put(p.getIntValue("rank"), p);
                }
            } catch (Exception e) {
                log.error("prizeList 解析失败: match={}", match.getId(), e);
            }
        }

        // 货币奖比例（§11.2.5 组合规则：实物名次不占奖池百分比，奖池只在货币名次间分）
        List<Integer> percents = parsePercents(match.getRewardRanking());
        String currency = match.getRewardType() == MttMatch.REWARD_DIAMOND
                ? LedgerEntry.CURRENCY_DIAMOND : LedgerEntry.CURRENCY_SCORE;

        List<Map<String, Object>> detail = new ArrayList<>();
        for (int rank = 1; rank <= percents.size(); rank++) {
            MttCompetitor comp = byRank.get(rank);
            if (comp == null) continue; // 并列名次空档

            comp.setIsReward(true);

            JSONObject prize = prizeByRank.get(rank);
            if (prize != null) {
                // 实物：发放单
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
                continue;
            }

            long amount = totalBonus * percents.get(rank - 1) / 100; // 向下取整（对齐德州）
            if (amount <= 0) continue;
            comp.setRewardAmount(amount);
            competitorRepository.save(comp);

            Map<String, Object> d = new HashMap<>();
            d.put("rank", rank);
            d.put("userId", comp.getUserId());
            d.put("amount", amount);
            d.put("currency", currency);
            detail.add(d);
        }

        MttPayoutBatch batch = new MttPayoutBatch();
        batch.setMatchId(match.getId());
        batch.setDetail(JSON.toJSONString(detail));
        payoutBatchRepository.save(batch);
        log.info("发奖批次生成: match={}, totalBonus={}, 名次数={}", match.getId(), totalBonus, detail.size());
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
            } catch (Exception e) {
                allDone = false;
                log.warn("发奖入账失败(可重试): match={}, user={}, err={}", match.getId(), userId, e.getMessage());
            }
        }

        if (allDone) {
            batch.setStatus(MttPayoutBatch.STATUS_PAID);
            payoutBatchRepository.save(batch);
            match.setStatus(MttMatch.STATUS_FINISHED);
            matchRepository.save(match);
            log.info("发奖完成，比赛结束: match={}", match.getId());
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

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("matchId", matchId);
        r.put("entryFeeSum", feeSum);
        r.put("refundSum", refundSum);
        r.put("rewardPayoutSum", rewardSum);
        r.put("initialPool", match.getInitialPool());
        r.put("totalBonus", match.getTotalBonus());
        r.put("invariant_reward_le_pool", rewardSum <= expectPool);
        r.put("invariant_pool_eq_fee", match.getTotalBonus() == null
                || match.getStatus() == MttMatch.STATUS_DISMISS
                || feeSum - refundSum + match.getInitialPool() == match.getTotalBonus());
        return r;
    }

    private List<Integer> parsePercents(String rewardRankingJson) {
        try {
            return JSON.parseArray(rewardRankingJson, Integer.class);
        } catch (Exception e) {
            return List.of(50, 30, 20);
        }
    }
}
