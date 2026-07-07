package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ⭐ 实物奖品库（平台级，最简版）
 *
 * 运营先在奖品库登记（名称/图标/详情/是否虚拟），建实物赛时奖品清单从下拉列表选，
 * 不再手填。prizeList 快照仍存 mtt_match（奖品库后改不影响已建比赛）。
 */
@Data
@Entity
@Table(name = "mtt_prize_item", indexes = @Index(name = "idx_prize_item_status", columnList = "status"))
public class MttPrizeItem {

    public static final int STATUS_ON = 1;   // 上架（可选）
    public static final int STATUS_OFF = 0;  // 下架（历史比赛不受影响）

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 奖品名称 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 奖品图标 URL */
    @Column(name = "icon", length = 256)
    private String icon;

    /** 奖品详情（简介/规格） */
    @Column(name = "detail", length = 512)
    private String detail;

    /** 是否虚拟物品（虚拟=无需收货地址直接核销） */
    @Column(name = "is_virtual", nullable = false)
    private Boolean isVirtual = false;

    /** 1上架 0下架 */
    @Column(name = "status", nullable = false)
    private Integer status = STATUS_ON;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
