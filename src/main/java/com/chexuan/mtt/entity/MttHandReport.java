package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 每局上报存档（规划 §13）—— 幂等去重（uk match+room+hand）+ 回放审计
 */
@Data
@Entity
@Table(name = "mtt_hand_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_mtt_hand", columnNames = {"match_id", "room_id", "hand_no"}),
        indexes = @Index(name = "idx_mtt_hand_match", columnList = "match_id"))
public class MttHandReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "hand_no", nullable = false)
    private Integer handNo;

    /** [{userId, scoreAfter, eliminated}] */
    @Column(name = "players", columnDefinition = "TEXT")
    private String players;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
