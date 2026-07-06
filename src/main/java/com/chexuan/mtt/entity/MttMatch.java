package com.chexuan.mtt.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 比赛主表（规划 §13）
 *
 * 状态机（规划 §3）：1=CREATE报名期 2=PLAYING比赛中 0=FINISHED已发奖 3=DISMISS解散
 */
@Data
@Entity
@Table(name = "mtt_match", indexes = {
        @Index(name = "idx_mtt_match_status", columnList = "status"),
        @Index(name = "idx_mtt_match_club", columnList = "club_id")
})
public class MttMatch {

    public static final int STATUS_FINISHED = 0;
    public static final int STATUS_CREATE = 1;
    public static final int STATUS_PLAYING = 2;
    public static final int STATUS_DISMISS = 3;

    public static final int REWARD_SCORE = 1;
    public static final int REWARD_DIAMOND = 2;
    public static final int REWARD_PRIZE = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 所属俱乐部（官方赛=系统俱乐部，规划 §17④） */
    @Column(name = "club_id", nullable = false)
    private Long clubId;

    @Column(name = "status", nullable = false)
    private Integer status = STATUS_CREATE;

    /** 开赛时间（ms) */
    @Column(name = "start_time", nullable = false)
    private Long startTime;

    /** 报名开放时间（ms，null=创建即可报名） */
    @Column(name = "entry_open_time")
    private Long entryOpenTime;

    /** 报名费（分） */
    @Column(name = "entry_fee", nullable = false)
    private Long entryFee = 0L;

    /** 报名费货币：SCORE 俱乐部积分（一期唯一支持，规划 §17③） */
    @Column(name = "entry_currency", length = 16, nullable = false)
    private String entryCurrency = "SCORE";

    /** 初始记分牌 */
    @Column(name = "initial_score", nullable = false)
    private Long initialScore = 10000L;

    /** 每桌人数（6/8） */
    @Column(name = "seat_num", nullable = false)
    private Integer seatNum = 8;

    /** 人数下限（不足解散退费） / 上限 */
    @Column(name = "lower_limit", nullable = false)
    private Integer lowerLimit = 4;

    @Column(name = "upper_limit", nullable = false)
    private Integer upperLimit = 200;

    /** 升底皮周期（分钟） */
    @Column(name = "upgrade_minutes", nullable = false)
    private Integer upgradeMinutes = 10;

    /**
     * 底皮级别表 JSON：[[级别,底皮],[级别,底皮]...]，例 [[1,10],[2,20],[3,30],[4,50],[5,80],[6,120],[7,200]]
     * 末级封顶不再升（规划 §8）
     */
    @Column(name = "level_table", columnDefinition = "TEXT")
    private String levelTable;

    /** 奖励类型：1积分 2钻石 3实物（规划 §11.2.5） */
    @Column(name = "reward_type", nullable = false)
    private Integer rewardType = REWARD_SCORE;

    /** 奖励圈名次比例 JSON：[50,30,20] = 前3名分50/30/20% */
    @Column(name = "reward_ranking", columnDefinition = "TEXT")
    private String rewardRanking;

    /** 实物赛名次奖品 JSON：[{"rank":1,"prizeName":"...","prizeIcon":"...","isVirtual":false}] */
    @Column(name = "prize_list", columnDefinition = "TEXT")
    private String prizeList;

    /** 固定奖池（运营预置，分） */
    @Column(name = "initial_pool", nullable = false)
    private Long initialPool = 0L;

    /**
     * 比赛桌规则模板 JSON（规划 §8 待确认①：创建时可配，默认竞技模板）
     * 例 {"mangoMax":5,"quanMang":true,"xiuZouMang":false,"diWang":true,"sanHua":true,"coverCard":false}
     */
    @Column(name = "rule_template", columnDefinition = "TEXT")
    private String ruleTemplate;

    /** 总奖池快照（冠军产生时按 §11.1 公式落库） */
    @Column(name = "total_bonus")
    private Long totalBonus;

    /** 报名人数（开赛时快照） */
    @Column(name = "participants")
    private Integer participants;

    /** 冠军 userId（结束后落库） */
    @Column(name = "champion_user_id")
    private Long championUserId;

    /** 取消原因（DISMISS 时） */
    @Column(name = "dismiss_reason", length = 128)
    private String dismissReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
