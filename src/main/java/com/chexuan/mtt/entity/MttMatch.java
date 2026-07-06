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

    /**
     * ⭐ 赛事类型与货币绑定（2026-07 定版，冠军通吃模型）：
     *   1 金币赛：报名扣金币(user.gold, 钻石1:N兑换)，记分牌=金币(报名费即初始记分牌,1:1)。
     *             输的钱在牌局中已被对手赢走；打到只剩冠军时全场记分牌归冠军，
     *             终局兑付=冠军把记分牌换回金币(participants×entryFee+initialPool)。其余名次只显示排名。
     *   2 钻石赛：同上，货币为钻石(user.diamond)，记分牌=钻石。
     *   3 实物赛：报名扣钻石(平台留存)，记分牌纯虚拟(仅内存计分)，
     *             按名次发实物(prizeList,可多件) + 玩家填收货地址后台派送。
     */
    public static final int REWARD_GOLD = 1;
    public static final int REWARD_DIAMOND = 2;
    public static final int REWARD_PRIZE = 3;

    /** @deprecated 旧命名（积分赛）；一期已改为金币赛，常量值不变 */
    @Deprecated
    public static final int REWARD_SCORE = REWARD_GOLD;

    /** 按赛事类型推导报名货币（创建时强制覆写，不信任入参） */
    public static String entryCurrencyOf(Integer rewardType) {
        if (rewardType != null && rewardType == REWARD_GOLD) return "GOLD";
        return "DIAMOND"; // 钻石赛 & 实物赛都用钻石报名
    }

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

    /**
     * 报名费货币（由 rewardType 推导，创建时强制覆写）：
     *   GOLD 金币（金币赛）/ DIAMOND 钻石（钻石赛、实物赛）
     */
    @Column(name = "entry_currency", length = 16, nullable = false)
    private String entryCurrency = "GOLD";

    /**
     * 初始记分牌：
     *   金币赛/钻石赛：记分牌即货币 —— 创建时强制 initialScore = entryFee（1记分牌=1金币/钻石），
     *                  牌局中的输赢就是钱的流动，终局冠军兑付全部记分牌；
     *   实物赛：纯内存计分字段，比赛结束即作废，可自由配置。
     */
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

    /** 奖励类型：1金币赛 2钻石赛 3实物赛（规划 §11.2.5，货币绑定见类头注释） */
    @Column(name = "reward_type", nullable = false)
    private Integer rewardType = REWARD_GOLD;

    /**
     * @deprecated 名次比例分奖已废弃（2026-07 冠军通吃定版）：金币/钻石赛冠军兑付全部记分牌，
     * 实物赛按 prizeList 名次发实物。字段保留兼容旧数据，不再参与结算。
     */
    @Deprecated
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

    /**
     * ⭐ 比赛机器人数：开赛前 5 分钟自动报名 N 个该俱乐部机器人(is_robot=1)。
     * 机器人在比赛桌上按牌力公平打(不控盘,记分牌非真钱);机器人拿到的货币奖励
     * 回到机器人账户=运营留存。0=无机器人。
     */
    @Column(name = "robot_count", nullable = false)
    private Integer robotCount = 0;

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
