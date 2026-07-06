package com.chexuan.mtt.api;

import com.alibaba.fastjson2.JSON;
import com.chexuan.mtt.common.BaseResponse;
import com.chexuan.mtt.entity.*;
import com.chexuan.mtt.repository.MttRepositories.*;
import com.chexuan.mtt.service.MatchLifecycleService;
import com.chexuan.mtt.service.PayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 运营后台接口（规划 §14）
 *
 * 鉴权：X-MTT-TOKEN（Vue 后台经主服/网关反代持有）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mtt/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MatchLifecycleService lifecycleService;
    private final PayoutService payoutService;
    private final MttMatchRepository matchRepository;
    private final MttRegistrationRepository registrationRepository;
    private final MttCompetitorRepository competitorRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final MttPrizeGrantRepository prizeGrantRepository;

    /** 创建比赛（字段=MttMatch，金额入参统一"分"） */
    @PostMapping("/create")
    public BaseResponse<MttMatch> create(@RequestBody MttMatch req) {
        try {
            return BaseResponse.success(lifecycleService.create(req));
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /** 取消/解散（触发批量退费） */
    @PostMapping("/cancel")
    public BaseResponse<String> cancel(@RequestBody Map<String, Object> body) {
        try {
            Long matchId = Long.valueOf(body.get("matchId").toString());
            String reason = body.getOrDefault("reason", "运营取消").toString();
            lifecycleService.cancel(matchId, reason);
            return BaseResponse.success("已取消");
        } catch (Exception e) {
            return BaseResponse.error(400, e.getMessage());
        }
    }

    /** 比赛列表 */
    @PostMapping("/list")
    public BaseResponse<List<MttMatch>> list(@RequestBody(required = false) Map<String, Object> body) {
        Long clubId = body != null && body.get("clubId") != null
                ? Long.valueOf(body.get("clubId").toString()) : null;
        List<MttMatch> list = clubId != null
                ? matchRepository.findByClubIdOrderByStartTimeDesc(clubId)
                : matchRepository.findAllByOrderByStartTimeDesc();
        return BaseResponse.success(list);
    }

    /** 比赛详情（实时报名/存活） */
    @PostMapping("/detail")
    public BaseResponse<Object> detail(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        MttMatch match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return BaseResponse.error(404, "比赛不存在");
        long registered = registrationRepository.countByMatchIdAndStatus(matchId, MttRegistration.STATUS_REGISTERED);
        long alive = competitorRepository.countByMatchIdAndStatus(matchId, MttCompetitor.STATUS_ALIVE);
        return BaseResponse.success(MatchLifecycleService.toDetail(match, registered, alive));
    }

    /** 参赛者名次 */
    @PostMapping("/competitors")
    public BaseResponse<List<MttCompetitor>> competitors(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        List<MttCompetitor> list = competitorRepository.findByMatchId(matchId);
        list.sort(Comparator.comparing(c -> c.getRankNo() == null ? Integer.MAX_VALUE : c.getRankNo()));
        return BaseResponse.success(list);
    }

    /** 账务流水（对账页数据源） */
    @PostMapping("/ledger")
    public BaseResponse<List<LedgerEntry>> ledger(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        return BaseResponse.success(ledgerRepository.findByMatchIdOrderByIdAsc(matchId));
    }

    /** 对账不变量校验（规划 §11.4） */
    @PostMapping("/reconcile")
    public BaseResponse<Object> reconcile(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        MttMatch match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return BaseResponse.error(404, "比赛不存在");
        List<LedgerEntry> entries = ledgerRepository.findByMatchIdOrderByIdAsc(matchId);
        return BaseResponse.success(payoutService.reconcile(matchId, entries, match));
    }

    /** 实物赛发放单列表 */
    @PostMapping("/prizeGrants")
    public BaseResponse<List<MttPrizeGrant>> prizeGrants(@RequestBody Map<String, Object> body) {
        Long matchId = Long.valueOf(body.get("matchId").toString());
        return BaseResponse.success(prizeGrantRepository.findByMatchId(matchId));
    }

    /** 实物核销兑付 */
    @PostMapping("/prizeRedeem")
    public BaseResponse<MttPrizeGrant> prizeRedeem(@RequestBody Map<String, Object> body) {
        Long grantId = Long.valueOf(body.get("grantId").toString());
        String operator = body.getOrDefault("operator", "admin").toString();
        MttPrizeGrant grant = prizeGrantRepository.findById(grantId).orElse(null);
        if (grant == null) return BaseResponse.error(404, "发放单不存在");
        if (MttPrizeGrant.STATUS_REDEEMED.equals(grant.getStatus())) {
            return BaseResponse.error(400, "已核销过");
        }
        grant.setStatus(MttPrizeGrant.STATUS_REDEEMED);
        grant.setOperator(operator);
        grant.setRedeemedAt(LocalDateTime.now());
        prizeGrantRepository.save(grant);
        log.info("实物核销: grant={}, match={}, user={}, prize={}, by={}",
                grantId, grant.getMatchId(), grant.getUserId(), grant.getPrizeName(), operator);
        return BaseResponse.success(grant);
    }
}
