package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 报名记录（规划 §5）—— 报名费凭证，落 MySQL 不落 Redis（可审计硬要求）
 */
@Data
@Entity
@Table(name = "mtt_registration",
        uniqueConstraints = @UniqueConstraint(name = "uk_mtt_reg", columnNames = {"match_id", "user_id"}),
        indexes = @Index(name = "idx_mtt_reg_match", columnList = "match_id"))
public class MttRegistration {

    public static final int STATUS_REGISTERED = 1;
    public static final int STATUS_REFUNDED = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 实付报名费（分） */
    @Column(name = "fee", nullable = false)
    private Long fee;

    /** 1=报名成功 2=已退费（退赛/解散/取消） */
    @Column(name = "status", nullable = false)
    private Integer status = STATUS_REGISTERED;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
