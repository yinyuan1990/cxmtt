package com.chexuan.mtt.core;

import lombok.Data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一场比赛的内存运行态（德州 LeveRoom 的等价物，规划 §13）
 *
 * 可随时从 mtt_match + mtt_competitor 重建（重启恢复）；
 * 这里只放"丢了也能推导回来"的调度状态，账务真相永远在 DB。
 */
@Data
public class MatchContext {

    private final Long matchId;

    /** 比赛内所有牌桌 roomId（含比赛过程中开/关的动态变化） */
    private final Set<Long> tableRoomIds = ConcurrentHashMap.newKeySet();

    /** 当前底皮级别（心跳按时间反算，重启后从 1 重新对齐，upgradeLevel 幂等） */
    private volatile int currentLevel = 1;

    /** 开赛前分桌是否已触发（防心跳重复分桌） */
    private volatile boolean startTriggered = false;

    /** ⭐ 机器人自动报名是否已触发（开赛前5分钟一次） */
    private volatile boolean robotsRegistered = false;

    /** 到点放行发牌是否已触发 */
    private volatile boolean tablesResumed = false;

    /** 进奖励圈通知是否已发（只发一次，对齐德州 leveRoom.setReward） */
    private volatile boolean rewardCircleNotified = false;

    /** 拆并桌进行中（LOCKED，期间禁重购/延时报名——二期字段） */
    private volatile boolean rebalancing = false;

    /** 拆并桌暂停 ACK 收集 */
    private final Set<Long> pausedAck = ConcurrentHashMap.newKeySet();

    /** 冠军是否已产生（防重复发奖批次） */
    private volatile boolean finished = false;

    /** 比赛级互斥锁：上报处理 / 暂停ACK / 拆并桌 都必须持有 */
    private final Object lock = new Object();

    public MatchContext(Long matchId) {
        this.matchId = matchId;
    }
}
