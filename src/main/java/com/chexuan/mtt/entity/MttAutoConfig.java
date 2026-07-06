package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ⭐ 俱乐部自动开赛配置（保证赛事列表不空，"热闹"机制）
 *
 * 语义：
 *   - enabled 默认 false —— 真人俱乐部想热闹才手动开；公共俱乐部(大联盟)开启+带机器人。
 *   - 心跳每轮检查：该俱乐部"未开赛(CREATE)"的比赛 < minUpcoming → 按模板补建，
 *     开赛时间从 now+leadMinutes 起、相邻场次间隔 intervalMinutes 递推。
 *   - 模板 templateJson = MttMatch 字段子集（entryFee/initialScore/seatNum/levelTable/
 *     rewardType/rewardRanking/robotCount/ruleTemplate...），建赛时套用。
 *   - 群主手动建的比赛与自动建的共存，自动机制只看数量不看来源。
 */
@Data
@Entity
@Table(name = "mtt_auto_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_mtt_auto_club", columnNames = "club_id"))
public class MttAutoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    /** 自动开赛开关（默认关） */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    /** 列表里保证至少 N 场未开赛的比赛 */
    @Column(name = "min_upcoming", nullable = false)
    private Integer minUpcoming = 2;

    /** 新建比赛距开赛的报名期（分钟） */
    @Column(name = "lead_minutes", nullable = false)
    private Integer leadMinutes = 20;

    /** 相邻自动场次的间隔（分钟） */
    @Column(name = "interval_minutes", nullable = false)
    private Integer intervalMinutes = 60;

    /** 赛事名前缀（自动建赛命名："{prefix} HH:mm 场"） */
    @Column(name = "name_prefix", length = 32)
    private String namePrefix = "公开赛";

    /** 比赛模板 JSON（MttMatch 字段子集） */
    @Column(name = "template_json", columnDefinition = "TEXT")
    private String templateJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
