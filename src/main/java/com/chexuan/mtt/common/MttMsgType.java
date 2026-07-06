package com.chexuan.mtt.common;

/**
 * 比赛推送消息号（360~369 段，经主服 WS 转发；与主服 ServerMsgType 登记一致，规划 §12.3）
 *
 * ⚠️ 350/351 已被主服房间语音/礼物占用（ROOM_VOICE_PUSH/ROOM_GIFT_PUSH），比赛段定为 360 起。
 */
public class MttMsgType {

    public static final int MATCH_START_REMIND = 360;  // 开赛提醒（开赛前1分钟）
    public static final int LEVEL_UPGRADE = 361;       // 升底皮 {matchId, level, baseScore}
    public static final int TABLE_REBALANCE = 362;     // 拆并桌通知 {matchId, tables}
    public static final int REWARD_CIRCLE = 363;       // 恭喜进入奖励圈 {matchId, aliveCount}
    public static final int RANK_CHANGE = 364;         // 名次变化 {matchId, rank, alive}
    public static final int ELIMINATED = 365;          // 淘汰通知 {matchId, rank}
    public static final int MATCH_CANCELLED = 366;     // 比赛取消 {matchId, reason}
    public static final int REWARD_ARRIVED = 367;      // 发奖到账 {matchId, rank, amount, currency} / 实物 {prizeName}
}
