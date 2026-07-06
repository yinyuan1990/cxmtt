package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 实物赛发放单（规划 §11.2.5，对齐德州 MTTReward）
 * 冠军产生时按名次生成，运营后台核销兑付。不进 ledger 金额。
 */
@Data
@Entity
@Table(name = "mtt_prize_grant",
        indexes = {
                @Index(name = "idx_mtt_prize_match", columnList = "match_id"),
                @Index(name = "idx_mtt_prize_status", columnList = "status")
        })
public class MttPrizeGrant {

    public static final String STATUS_GRANTED = "GRANTED";   // 待核销
    public static final String STATUS_REDEEMED = "REDEEMED"; // 已兑付

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Column(name = "prize_name", length = 64, nullable = false)
    private String prizeName;

    @Column(name = "prize_icon", length = 256)
    private String prizeIcon;

    @Column(name = "is_virtual", nullable = false)
    private Boolean isVirtual = false;

    @Column(name = "status", length = 12, nullable = false)
    private String status = STATUS_GRANTED;

    /** 核销操作人（运营账号） */
    @Column(name = "operator", length = 32)
    private String operator;

    @CreationTimestamp
    @Column(name = "granted_at", updatable = false)
    private LocalDateTime grantedAt;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;
}
