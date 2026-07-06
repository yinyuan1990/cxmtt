package com.chexuan.mtt.api;

import com.chexuan.mtt.common.BaseResponse;
import com.chexuan.mtt.entity.MttCompetitor;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.entity.MttRegistration;
import com.chexuan.mtt.repository.MttRepositories.*;
import com.chexuan.mtt.service.MatchLifecycleService;
import com.chexuan.mtt.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 玩家侧接口（规划 §12.3 客户端 140~144 对应）
 *
 * 客户端 → 主服（JWT 校验）→ 代理到这里（X-MTT-TOKEN），userId 由主服解出后透传。
 */
@Slf4j
@RestController
@RequestMapping("/internal/player")
@RequiredArgsConstructor
public class InternalPlayerController {

    private final RegistrationService registrationService;
    private final MttMatchRepository matchRepository;
    private final MttRegistrationRepository registrationRepository;
    private final MttCompetitorRepository competitorRepository;
    private final MttPrizeGrantRepository prizeGrantRepository;
    private final com.chexuan.mtt.service.MatchLifecycleService lifecycleService;

    /** 140 报名 */
    @PostMapping("/register")
    public BaseResponse<String> register(@RequestBody Map<String, Object> body) {
        try {
            Long matchId = Long.valueOf(body.get("matchId").toString());
            Long userId = Long.valueOf(body.get("userId").toString());
            registrationService.register(matchId, userId);
            return BaseResponse.success("报名成功，等待开赛");
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /** 141 退赛 */
    @PostMapping("/unregister")
    public BaseResponse<String> unregister(@RequestBody Map<String, Object> body) {
        try {
            Long matchId = Long.valueOf(body.get("matchId").toString());
            Long userId = Long.valueOf(body.get("userId").toString());
            registrationService.unregister(matchId, userId);
            return BaseResponse.success("已退赛，报名费已退还");
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /** 142 比赛列表（按俱乐部；报名期+进行中+近期结束） */
    @PostMapping("/list")
    public BaseResponse<List<Map<String, Object>>> list(@RequestBody Map<String, Object> body) {
        Long clubId = Long.valueOf(body.get("clubId").toString());
        Long userId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;

        List<Map<String, Object>> result = new ArrayList<>();
        for (MttMatch m : matchRepository.findByClubIdOrderByStartTimeDesc(clubId)) {
            long registered = registrationRepository.countByMatchIdAndStatus(
                    m.getId(), MttRegistration.STATUS_REGISTERED);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("matchId", m.getId());
            item.put("name", m.getName());
            item.put("status", m.getStatus());
            item.put("startTime", m.getStartTime());
            item.put("entryFee", m.getEntryFee());
            item.put("initialScore", m.getInitialScore());
            item.put("seatNum", m.getSeatNum());
            item.put("rewardType", m.getRewardType());
            item.put("registered", registered);
            item.put("upperLimit", m.getUpperLimit());
            item.put("lowerLimit", m.getLowerLimit());
            if (userId != null) {
                item.put("myRegistered", registrationRepository.findByMatchIdAndUserId(m.getId(), userId)
                        .map(r -> r.getStatus() == MttRegistration.STATUS_REGISTERED).orElse(false));
            }
            result.add(item);
        }
        return BaseResponse.success(result);
    }

    /** 143 比赛详情 */
    @PostMapping("/detail")
    public BaseResponse<Object> detail(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        MttMatch match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return BaseResponse.error(404, "比赛不存在");
        long registered = registrationRepository.countByMatchIdAndStatus(matchId, MttRegistration.STATUS_REGISTERED);
        long alive = competitorRepository.countByMatchIdAndStatus(matchId, MttCompetitor.STATUS_ALIVE);
        return BaseResponse.success(MatchLifecycleService.toDetail(match, registered, alive));
    }

    /** 145 我的历史比赛（已结束/解散的参赛记录：名次/奖励，历史页数据源） */
    @PostMapping("/history")
    public BaseResponse<List<Map<String, Object>>> history(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        List<Map<String, Object>> result = new ArrayList<>();
        for (MttRegistration reg : registrationRepository.findByUserIdAndStatus(
                userId, MttRegistration.STATUS_REGISTERED)) {
            MttMatch m = matchRepository.findById(reg.getMatchId()).orElse(null);
            if (m == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("matchId", m.getId());
            item.put("name", m.getName());
            item.put("status", m.getStatus());
            item.put("startTime", m.getStartTime());
            item.put("rewardType", m.getRewardType());
            item.put("participants", m.getParticipants());
            item.put("totalBonus", m.getTotalBonus());
            competitorRepository.findByMatchIdAndUserId(m.getId(), userId).ifPresent(c -> {
                item.put("myRank", c.getRankNo());
                item.put("myReward", c.getRewardAmount());
                item.put("eliminateHandNo", c.getEliminateHandNo());
                item.put("eliminateLevel", c.getEliminateLevel());
            });
            result.add(item);
        }
        // 最近的在前
        result.sort((a, b) -> Long.compare((Long) b.get("startTime"), (Long) a.get("startTime")));
        return BaseResponse.success(result);
    }

    /** 146 我的奖品（实物赛发放单：待填地址/待派送/已派送/已兑付） */
    @PostMapping("/myPrizes")
    public BaseResponse<Object> myPrizes(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        return BaseResponse.success(prizeGrantRepository.findByUserIdOrderByIdDesc(userId));
    }

    /**
     * 148 填写收货地址（实物奖）：GRANTED 状态下可填/可改；派送后锁定。
     * body:{grantId, receiverName, receiverPhone, receiverAddress}
     */
    @PostMapping("/fillPrizeAddress")
    public BaseResponse<Object> fillPrizeAddress(@RequestBody Map<String, Object> body) {
        try {
            Long userId = Long.valueOf(body.get("userId").toString());
            Long grantId = Long.valueOf(body.get("grantId").toString());
            String name = String.valueOf(body.getOrDefault("receiverName", "")).trim();
            String phone = String.valueOf(body.getOrDefault("receiverPhone", "")).trim();
            String address = String.valueOf(body.getOrDefault("receiverAddress", "")).trim();
            if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
                return BaseResponse.error(400, "收货人/电话/地址均不能为空");
            }
            if (name.length() > 32 || phone.length() > 20 || address.length() > 256) {
                return BaseResponse.error(400, "收货信息过长");
            }

            var grant = prizeGrantRepository.findById(grantId).orElse(null);
            if (grant == null || !grant.getUserId().equals(userId)) {
                return BaseResponse.error(404, "奖品发放单不存在");
            }
            if (Boolean.TRUE.equals(grant.getIsVirtual())) {
                return BaseResponse.error(400, "虚拟奖品无需收货地址");
            }
            if (!com.chexuan.mtt.entity.MttPrizeGrant.STATUS_GRANTED.equals(grant.getStatus())) {
                return BaseResponse.error(400, "奖品已派送，地址不可修改，如需变更请联系客服");
            }
            grant.setReceiverName(name);
            grant.setReceiverPhone(phone);
            grant.setReceiverAddress(address);
            grant.setAddressFilledAt(java.time.LocalDateTime.now());
            prizeGrantRepository.save(grant);
            log.info("实物奖收货地址已填: grant={}, user={}, prize={}", grantId, userId, grant.getPrizeName());
            return BaseResponse.success("地址已保存，等待平台派送");
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /**
     * 147 群主建赛（权限校验在主服代理层完成：必须是该俱乐部群主/管理员）。
     * body = MttMatch 字段（clubId 已由主服校验后透传）。
     */
    @PostMapping("/createByOwner")
    public BaseResponse<MttMatch> createByOwner(@RequestBody MttMatch req) {
        try {
            return BaseResponse.success(lifecycleService.create(req));
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /** 144 我的赛况（名次/记分牌/所在桌） */
    @PostMapping("/myStatus")
    public BaseResponse<Object> myStatus(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        Long userId = Long.valueOf(body.get("userId").toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchId", matchId);
        result.put("registered", registrationRepository.findByMatchIdAndUserId(matchId, userId)
                .map(r -> r.getStatus() == MttRegistration.STATUS_REGISTERED).orElse(false));

        MttCompetitor comp = competitorRepository.findByMatchIdAndUserId(matchId, userId).orElse(null);
        if (comp != null) {
            result.put("score", comp.getScore());
            result.put("rank", comp.getRankNo());
            result.put("alive", comp.getStatus() == MttCompetitor.STATUS_ALIVE);
            result.put("roomId", comp.getRoomId());
            result.put("seatNo", comp.getSeatNo());
            result.put("rewardAmount", comp.getRewardAmount());
            long aliveCount = competitorRepository.countByMatchIdAndStatus(matchId, MttCompetitor.STATUS_ALIVE);
            result.put("aliveCount", aliveCount);
        }
        return BaseResponse.success(result);
    }
}
