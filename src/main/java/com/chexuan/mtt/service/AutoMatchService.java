package com.chexuan.mtt.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.entity.MttAutoConfig;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.repository.MttAutoConfigRepository;
import com.chexuan.mtt.repository.MttMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * ⭐ 自动开赛（"热闹"机制）：保证开启俱乐部的赛事列表永远不空。
 *
 * 心跳每轮调 ensureUpcoming()：
 *   未开赛场次 < minUpcoming → 按模板补建，开赛时间在"最后一场未开赛比赛"之后
 *   intervalMinutes 递推（没有则 now+leadMinutes 起步）。
 *
 * 默认全部俱乐部关闭；公共俱乐部(大联盟)由运营开启并在模板里配 robotCount(公共俱乐部需要机器人)。
 * 真人俱乐部群主手动建赛与本机制共存 —— 只看数量不看来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMatchService {

    private final MttAutoConfigRepository autoConfigRepository;
    private final MttMatchRepository matchRepository;
    private final MatchLifecycleService lifecycleService;

    /** 心跳入口：逐个已开启俱乐部补足未开赛场次。 */
    public void ensureUpcoming() {
        List<MttAutoConfig> configs = autoConfigRepository.findByEnabledTrue();
        for (MttAutoConfig cfg : configs) {
            try {
                ensureClub(cfg);
            } catch (Exception e) {
                log.error("自动开赛异常: clubId={}", cfg.getClubId(), e);
            }
        }
    }

    private void ensureClub(MttAutoConfig cfg) {
        List<MttMatch> upcoming = matchRepository.findByClubIdAndStatusOrderByStartTimeDesc(
                cfg.getClubId(), MttMatch.STATUS_CREATE);
        int min = cfg.getMinUpcoming() != null ? Math.max(1, cfg.getMinUpcoming()) : 2;
        int need = min - upcoming.size();
        if (need <= 0) return;

        long now = System.currentTimeMillis();
        // 从"最后一场未开赛"之后递推排期；没有则 now+leadMinutes 起步
        long lastStart = upcoming.isEmpty() ? 0L : upcoming.get(0).getStartTime();
        long intervalMs = (cfg.getIntervalMinutes() != null ? Math.max(10, cfg.getIntervalMinutes()) : 60) * 60_000L;
        long firstStart = Math.max(lastStart + intervalMs, now + (cfg.getLeadMinutes() != null
                ? Math.max(5, cfg.getLeadMinutes()) : 20) * 60_000L);

        for (int i = 0; i < need; i++) {
            long startTime = firstStart + i * intervalMs;
            MttMatch match = buildFromTemplate(cfg, startTime);
            try {
                lifecycleService.create(match);
                log.info("自动开赛建赛: clubId={}, matchId={}, name={}, start={}",
                        cfg.getClubId(), match.getId(), match.getName(), startTime);
            } catch (Exception e) {
                log.error("自动开赛建赛失败: clubId={}, start={}", cfg.getClubId(), startTime, e);
            }
        }
    }

    /** 模板(MttMatch 字段子集 JSON) → 新比赛对象。 */
    private MttMatch buildFromTemplate(MttAutoConfig cfg, long startTime) {
        JSONObject t = cfg.getTemplateJson() != null && !cfg.getTemplateJson().isEmpty()
                ? JSON.parseObject(cfg.getTemplateJson()) : new JSONObject();

        MttMatch m = new MttMatch();
        m.setClubId(cfg.getClubId());
        String prefix = cfg.getNamePrefix() != null && !cfg.getNamePrefix().isBlank()
                ? cfg.getNamePrefix().trim() : "公开赛";
        m.setName(prefix + " " + new SimpleDateFormat("HH:mm").format(new Date(startTime)) + " 场");
        m.setStartTime(startTime);
        m.setEntryFee(t.getLongValue("entryFee", 1000L));
        // entryCurrency 由 rewardType 推导（lifecycleService.create 强制覆写），模板无需配置
        m.setInitialScore(t.getLongValue("initialScore", 10000L));
        m.setSeatNum(t.getIntValue("seatNum", 8));
        m.setLowerLimit(t.getIntValue("lowerLimit", 4));
        m.setUpperLimit(t.getIntValue("upperLimit", 200));
        m.setUpgradeMinutes(t.getIntValue("upgradeMinutes", 10));
        m.setLevelTable(t.getString("levelTable"));
        m.setRewardType(t.getIntValue("rewardType", MttMatch.REWARD_GOLD));
        m.setPrizeList(t.getString("prizeList"));
        m.setRewardRanking(t.getString("rewardRanking"));          // 钻石赛名次比例
        m.setPlatformFeePercent(t.getIntValue("platformFeePercent", 10)); // 钻石赛平台手续费%
        m.setRobotWinBias(t.getIntValue("robotWinBias", 0));       // 本场机器人输赢倾向
        m.setInitialPool(t.getLongValue("initialPool", 0L));
        m.setRuleTemplate(t.getString("ruleTemplate"));
        // ⭐ 公共俱乐部需要机器人：模板里配 robotCount，自动场自动带
        m.setRobotCount(t.getIntValue("robotCount", 0));
        return m;
    }
}
