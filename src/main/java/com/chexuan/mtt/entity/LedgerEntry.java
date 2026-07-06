package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 账务分录（规划 §11.3 核心）
 *
 * 所有资金动作只有一条路：先写 ledger_entry（幂等键唯一），入账成功才动余额。
 * 实物奖不进 ledger（走 mtt_prize_grant）。
 *
 * 对账不变量（ReconcileJob 校验）：
 *   Σ ENTRY_FEE - Σ ENTRY_REFUND = totalBonus（该赛，货币口径）
 *   Σ REWARD_PAYOUT ≤ totalBonus
 */
@Data
@Entity
@Table(name = "ledger_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_idem", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_ledger_match", columnList = "match_id"),
                @Index(name = "idx_ledger_status", columnList = "status")
        })
public class LedgerEntry {

    public static final String TYPE_ENTRY_FEE = "ENTRY_FEE";
    public static final String TYPE_ENTRY_REFUND = "ENTRY_REFUND";
    public static final String TYPE_REWARD_PAYOUT = "REWARD_PAYOUT";
    public static final String TYPE_REBUY_FEE = "REBUY_FEE";       // 二期
    public static final String TYPE_HUNTER_BOUNTY = "HUNTER_BOUNTY"; // 二期

    public static final String CURRENCY_SCORE = "SCORE";
    public static final String CURRENCY_DIAMOND = "DIAMOND";
    /** ⭐ 金币：钻石单向兑换而来（user.gold），金币赛报名/发奖货币 */
    public static final String CURRENCY_GOLD = "GOLD";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SCORE 时需要：入账目标俱乐部 */
    @Column(name = "club_id")
    private Long clubId;

    @Column(name = "entry_type", length = 24, nullable = false)
    private String entryType;

    @Column(name = "currency", length = 16, nullable = false)
    private String currency = CURRENCY_SCORE;

    /** 正=给玩家，负=扣玩家（分） */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** 幂等键，例 "m1001:reward:u22" / "m1001:refund:u33" */
    @Column(name = "idempotency_key", length = 64, nullable = false)
    private String idempotencyKey;

    @Column(name = "status", length = 12, nullable = false)
    private String status = STATUS_PENDING;

    /** 入账失败原因（FAILED 时） */
    @Column(name = "fail_reason", length = 128)
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "balance_before")
    private Long balanceBefore;

    @Column(name = "balance_after")
    private Long balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "done_at")
    private LocalDateTime doneAt;
}
