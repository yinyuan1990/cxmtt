package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 发奖批次状态机（规划 §11.4，货币奖用；实物走 mtt_prize_grant）
 *
 * CALCULATED → PAYING → PAID；任一笔 FAILED 由心跳重试（幂等键保证不重复入账），
 * 超过 payout-max-retry 次仍失败 → 状态 FAILED 告警人工介入。
 */
@Data
@Entity
@Table(name = "mtt_payout_batch",
        indexes = @Index(name = "idx_mtt_payout_status", columnList = "status"))
public class MttPayoutBatch {

    public static final String STATUS_CALCULATED = "CALCULATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, unique = true)
    private Long matchId;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_CALCULATED;

    /** 名次金额快照 JSON：[{rank, userId, amount, currency}] */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
