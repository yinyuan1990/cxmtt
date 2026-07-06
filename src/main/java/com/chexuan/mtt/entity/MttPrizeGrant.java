package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 实物赛发放单（规划 §11.2.5，对齐德州 MTTReward：按名次可配多件奖品）
 * 冠军产生时按名次生成。不进 ledger 金额。
 *
 * ⭐ 派送闭环（2026-07）：
 *   GRANTED(已发放,待玩家填收货地址) → 玩家填地址 → SHIPPED(运营已派送) → REDEEMED(已签收/兑付完成)
 *   虚拟奖品(isVirtual=true)无需地址，运营直接核销 REDEEMED。
 */
@Data
@Entity
@Table(name = "mtt_prize_grant",
        indexes = {
                @Index(name = "idx_mtt_prize_match", columnList = "match_id"),
                @Index(name = "idx_mtt_prize_status", columnList = "status")
        })
public class MttPrizeGrant {

    public static final String STATUS_GRANTED = "GRANTED";   // 已发放（实物待玩家填地址）
    public static final String STATUS_SHIPPED = "SHIPPED";   // 运营已派送（实物）
    public static final String STATUS_REDEEMED = "REDEEMED"; // 已兑付/已签收

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

    // ==================== ⭐ 收货地址（玩家填写，运营后台按此派送） ====================

    /** 收货人姓名 */
    @Column(name = "receiver_name", length = 32)
    private String receiverName;

    /** 收货人电话 */
    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;

    /** 收货地址（省市区+详细） */
    @Column(name = "receiver_address", length = 256)
    private String receiverAddress;

    /** 地址填写时间 */
    @Column(name = "address_filled_at")
    private LocalDateTime addressFilledAt;

    /** 派送信息（快递单号/备注，运营填） */
    @Column(name = "ship_note", length = 128)
    private String shipNote;

    /** 派送时间 */
    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /** 核销操作人（运营账号） */
    @Column(name = "operator", length = 32)
    private String operator;

    @CreationTimestamp
    @Column(name = "granted_at", updatable = false)
    private LocalDateTime grantedAt;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;
}
