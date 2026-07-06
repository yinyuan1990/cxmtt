package com.chexuan.mtt.service;

import com.chexuan.mtt.entity.LedgerEntry;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.repository.MttRepositories.MttMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全局心跳调度器（规划 §4，照搬德州 MttManage.MttCheckTask 思想）
 *
 * 每 10 秒一轮，比赛服唯一"发动机"：
 *   1. 装载未结束比赛（含重启恢复）
 *   2. 开赛检查（前60s分桌 / 到点放行发牌）
 *   3. 升底皮（时间反算，错过任意一轮都能追平）
 *   4. 发奖批次断点续跑
 *   5. 账务 PENDING/FAILED 重试（退费/发奖崩溃恢复）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MttScheduler {

    private final MttMatchRepository matchRepository;
    private final MatchLifecycleService lifecycleService;
    private final PayoutService payoutService;
    private final LedgerService ledgerService;
    private final AutoMatchService autoMatchService;

    @Scheduled(fixedDelay = 10_000L, initialDelay = 5_000L)
    public void heartbeat() {
        try {
            // 0. ⭐ 自动开赛（热闹机制,已开启俱乐部补足未开赛场次;默认全关）
            autoMatchService.ensureUpcoming();

            // 1. 装载
            lifecycleService.loadUnfinished();

            // 2/3. 逐场比赛检查
            List<MttMatch> active = matchRepository.findByStatusIn(
                    List.of(MttMatch.STATUS_CREATE, MttMatch.STATUS_PLAYING));
            for (MttMatch match : active) {
                try {
                    lifecycleService.checkStart(match);
                    if (match.getStatus() == MttMatch.STATUS_PLAYING) {
                        lifecycleService.checkLevelUpgrade(match);
                    }
                } catch (Exception e) {
                    log.error("心跳处理比赛异常: match={}", match.getId(), e);
                }
            }

            // 4. 发奖批次
            payoutService.processPendingBatches();

            // 5. 账务重试（退费等散笔）
            for (LedgerEntry entry : ledgerService.findRetryable()) {
                // 发奖批次内的分录由 processPendingBatches 管；这里兜底重试退费/散笔
                if (LedgerEntry.TYPE_REWARD_PAYOUT.equals(entry.getEntryType())) continue;
                if (entry.getRetryCount() != null && entry.getRetryCount() >= 5) continue; // 交人工
                ledgerService.retry(entry);
            }
        } catch (Exception e) {
            log.error("心跳轮异常", e);
        }
    }
}
