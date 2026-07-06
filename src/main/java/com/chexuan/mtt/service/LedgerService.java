package com.chexuan.mtt.service;

import com.chexuan.mtt.entity.LedgerEntry;
import com.chexuan.mtt.repository.MttRepositories.LedgerEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账务分录服务（规划 §11.3）—— 比赛所有资金动作的唯一入口
 *
 * 铁律：
 *   1. 任何入口（报名/退费/发奖）不允许绕开本服务直接改余额；
 *   2. 幂等键唯一约束兜底：同一键重复提交返回已有结果，绝不重复入账；
 *   3. 「余额变更 + 分录置 DONE」同一个事务；失败标记 FAILED 用独立事务持久化（可重试可审计）；
 *   4. 扣款（amount<0）余额不足即失败，不允许负余额。
 *
 * 余额落点：SCORE → club_member.score（需 clubId）；DIAMOND → user.diamond；GOLD → user.gold
 */
@Slf4j
@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public LedgerService(LedgerEntryRepository ledgerRepository,
                         JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager) {
        this.ledgerRepository = ledgerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交一笔账务并立即尝试入账。
     *
     * @return 入账完成（DONE）的分录
     * @throws IllegalStateException 业务失败（余额不足/成员不存在），分录已标 FAILED 可重试
     */
    public LedgerEntry post(Long matchId, Long userId, Long clubId, String entryType,
                            String currency, long amount, String idempotencyKey) {
        // 1. 幂等：同键已 DONE 直接返回
        LedgerEntry existing = ledgerRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (LedgerEntry.STATUS_DONE.equals(existing.getStatus())) {
                return existing;
            }
            return apply(existing.getId());
        }

        // 2. 新建分录（PENDING，独立小事务先落地 —— 崩溃后心跳可续跑）
        LedgerEntry entry = new LedgerEntry();
        entry.setMatchId(matchId);
        entry.setUserId(userId);
        entry.setClubId(clubId);
        entry.setEntryType(entryType);
        entry.setCurrency(currency);
        entry.setAmount(amount);
        entry.setIdempotencyKey(idempotencyKey);
        Long entryId;
        try {
            entryId = ledgerRepository.saveAndFlush(entry).getId();
        } catch (DataIntegrityViolationException dup) {
            // 并发同键插入：读回已有的那条
            LedgerEntry raced = ledgerRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
            if (LedgerEntry.STATUS_DONE.equals(raced.getStatus())) return raced;
            entryId = raced.getId();
        }

        // 3. 入账
        return apply(entryId);
    }

    /**
     * 心跳重试入口（发奖断点续跑）。
     */
    public boolean retry(LedgerEntry entry) {
        try {
            apply(entry.getId());
            return true;
        } catch (Exception e) {
            log.warn("账务重试失败: key={}, err={}", entry.getIdempotencyKey(), e.getMessage());
            return false;
        }
    }

    public List<LedgerEntry> findRetryable() {
        return ledgerRepository.findByStatusIn(
                List.of(LedgerEntry.STATUS_PENDING, LedgerEntry.STATUS_FAILED));
    }

    // ==================== 余额变更 ====================

    /**
     * 「校验+改余额+置DONE」一个事务；业务失败在事务外标 FAILED（独立事务），再抛出。
     */
    private LedgerEntry apply(Long entryId) {
        try {
            return txTemplate.execute(status -> doApply(entryId));
        } catch (BusinessFailure bf) {
            txTemplate.executeWithoutResult(status -> {
                LedgerEntry e = ledgerRepository.findById(entryId).orElse(null);
                if (e != null && !LedgerEntry.STATUS_DONE.equals(e.getStatus())) {
                    e.setStatus(LedgerEntry.STATUS_FAILED);
                    e.setFailReason(bf.getMessage());
                    e.setRetryCount(e.getRetryCount() == null ? 1 : e.getRetryCount() + 1);
                    ledgerRepository.save(e);
                }
            });
            throw new IllegalStateException(bf.getMessage());
        }
    }

    private LedgerEntry doApply(Long entryId) {
        LedgerEntry entry = ledgerRepository.findById(entryId)
                .orElseThrow(() -> new BusinessFailure("分录不存在: " + entryId));
        if (LedgerEntry.STATUS_DONE.equals(entry.getStatus())) {
            return entry; // 幂等
        }

        long amount = entry.getAmount();
        Long balanceBefore;
        int updated;

        if (LedgerEntry.CURRENCY_SCORE.equals(entry.getCurrency())) {
            if (entry.getClubId() == null || entry.getClubId() <= 0) {
                throw new BusinessFailure("SCORE 账务缺 clubId");
            }
            balanceBefore = jdbcTemplate.query(
                    "SELECT score FROM club_member WHERE club_id=? AND user_id=? AND status=1 FOR UPDATE",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    entry.getClubId(), entry.getUserId());
            if (balanceBefore == null) {
                throw new BusinessFailure("俱乐部成员不存在: club=" + entry.getClubId() + ", user=" + entry.getUserId());
            }
            if (amount < 0 && balanceBefore + amount < 0) {
                throw new BusinessFailure("俱乐部积分不足: 余额" + balanceBefore + ", 需" + (-amount));
            }
            updated = jdbcTemplate.update(
                    "UPDATE club_member SET score = score + ? WHERE club_id=? AND user_id=? AND status=1",
                    amount, entry.getClubId(), entry.getUserId());
        } else if (LedgerEntry.CURRENCY_DIAMOND.equals(entry.getCurrency())) {
            balanceBefore = jdbcTemplate.query(
                    "SELECT diamond FROM user WHERE id=? FOR UPDATE",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    entry.getUserId());
            if (balanceBefore == null) {
                throw new BusinessFailure("用户不存在: " + entry.getUserId());
            }
            if (amount < 0 && balanceBefore + amount < 0) {
                throw new BusinessFailure("钻石不足");
            }
            updated = jdbcTemplate.update(
                    "UPDATE user SET diamond = diamond + ? WHERE id=?",
                    amount, entry.getUserId());
        } else if (LedgerEntry.CURRENCY_GOLD.equals(entry.getCurrency())) {
            balanceBefore = jdbcTemplate.query(
                    "SELECT gold FROM user WHERE id=? FOR UPDATE",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    entry.getUserId());
            if (balanceBefore == null) {
                throw new BusinessFailure("用户不存在: " + entry.getUserId());
            }
            if (amount < 0 && balanceBefore + amount < 0) {
                throw new BusinessFailure("金币不足: 余额" + balanceBefore + ", 需" + (-amount) + "(可用钻石兑换)");
            }
            updated = jdbcTemplate.update(
                    "UPDATE user SET gold = gold + ? WHERE id=?",
                    amount, entry.getUserId());
        } else {
            throw new BusinessFailure("未知货币: " + entry.getCurrency());
        }

        if (updated != 1) {
            throw new BusinessFailure("余额更新行数异常: " + updated);
        }

        entry.setBalanceBefore(balanceBefore);
        entry.setBalanceAfter(balanceBefore + amount);
        entry.setStatus(LedgerEntry.STATUS_DONE);
        entry.setDoneAt(LocalDateTime.now());
        entry.setFailReason(null);
        LedgerEntry saved = ledgerRepository.save(entry);
        log.info("账务入账: match={}, user={}, type={}, {}{}, 余额{}→{}, key={}",
                entry.getMatchId(), entry.getUserId(), entry.getEntryType(), entry.getCurrency(),
                amount, balanceBefore, entry.getBalanceAfter(), entry.getIdempotencyKey());
        return saved;
    }

    /** 业务失败（可重试），与系统异常区分 */
    private static class BusinessFailure extends RuntimeException {
        BusinessFailure(String msg) { super(msg); }
    }
}
