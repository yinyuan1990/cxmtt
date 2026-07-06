package com.chexuan.mtt.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.common.BaseResponse;
import com.chexuan.mtt.core.MatchContext;
import com.chexuan.mtt.core.MatchRegistry;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.repository.MttRepositories.MttMatchRepository;
import com.chexuan.mtt.service.HandReportService;
import com.chexuan.mtt.service.RebalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 主服上报入口（规划 §12.2）
 */
@Slf4j
@RestController
@RequestMapping("/mtt/report")
@RequiredArgsConstructor
public class ReportController {

    private final HandReportService handReportService;
    private final RebalanceService rebalanceService;
    private final MatchRegistry registry;
    private final MttMatchRepository matchRepository;

    /**
     * 每局结束上报（幂等键 matchId:roomId:handNo）
     * body: {matchId, roomId, handNo, players:[{userId, scoreAfter, eliminated}]}
     */
    @PostMapping("/handResult")
    public BaseResponse<String> handResult(@RequestBody JSONObject body) {
        try {
            Long matchId = body.getLong("matchId");
            Long roomId = body.getLong("roomId");
            Integer handNo = body.getInteger("handNo");
            JSONArray players = body.getJSONArray("players");
            if (matchId == null || roomId == null || handNo == null || players == null) {
                return BaseResponse.error(400, "参数不完整");
            }
            handReportService.onHandResult(matchId, roomId, handNo, players);
            return BaseResponse.success("ok");
        } catch (Exception e) {
            log.error("handResult 处理异常", e);
            return BaseResponse.error(500, e.getMessage());
        }
    }

    /**
     * 暂停 ACK（拆并桌用：主服局间生效后上报）
     */
    @PostMapping("/tablePaused")
    public BaseResponse<String> tablePaused(@RequestBody Map<String, Object> body) {
        try {
            Long matchId = Long.valueOf(body.get("matchId").toString());
            Long roomId = Long.valueOf(body.get("roomId").toString());
            MttMatch match = matchRepository.findById(matchId).orElse(null);
            MatchContext ctx = registry.get(matchId);
            if (match != null && ctx != null) {
                synchronized (ctx.getLock()) {
                    rebalanceService.onTablePaused(match, ctx, roomId);
                }
            }
            return BaseResponse.success("ok");
        } catch (Exception e) {
            log.error("tablePaused 处理异常", e);
            return BaseResponse.error(500, e.getMessage());
        }
    }

    /**
     * 玩家断线/重连状态（一期仅记录日志，托管由主服操作超时自动丢完成）
     */
    @PostMapping("/playerOffline")
    public BaseResponse<String> playerOffline(@RequestBody Map<String, Object> body) {
        log.info("比赛玩家离线状态上报: {}", body);
        return BaseResponse.success("ok");
    }
}
