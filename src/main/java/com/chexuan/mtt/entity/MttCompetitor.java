package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 参赛者赛况（规划 §9/§13）—— 开赛分桌时从报名记录生成
 *
 * 名次倒序法：rank = 总参赛人数 - 已淘汰人数累计 + 1（越晚淘汰名次越高，冠军 rank=1）
 */
@Data
@Entity
@Table(name = "mtt_competitor",
        uniqueConstraints = @UniqueConstraint(name = "uk_mtt_comp", columnNames = {"match_id", "user_id"}),
        indexes = @Index(name = "idx_mtt_comp_match", columnList = "match_id"))
public class MttCompetitor {

    public static final int STATUS_ALIVE = 0;
    public static final int STATUS_ELIMINATED = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 当前记分牌 */
    @Column(name = "score", nullable = false)
    private Long score;

    /** 名次（存活时为 null，淘汰时倒序分配，冠军=1） */
    @Column(name = "rank_no")
    private Integer rankNo;

    /** 0=存活 1=淘汰 */
    @Column(name = "status", nullable = false)
    private Integer status = STATUS_ALIVE;

    /** 当前所在比赛桌 roomId / 座位号 */
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "seat_no")
    private Integer seatNo;

    /** 出局时的手数 / 底皮级别（战绩展示） */
    @Column(name = "eliminate_hand_no")
    private Integer eliminateHandNo;

    @Column(name = "eliminate_level")
    private Integer eliminateLevel;

    /** 是否进奖励圈 / 分得奖金（分，货币奖）*/
    @Column(name = "is_reward", nullable = false)
    private Boolean isReward = false;

    @Column(name = "reward_amount", nullable = false)
    private Long rewardAmount = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
