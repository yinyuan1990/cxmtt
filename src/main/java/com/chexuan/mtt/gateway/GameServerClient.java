package com.chexuan.mtt.gateway;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chexuan.mtt.config.MttProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 主服（牌局执行器）内部指令客户端（规划 §12.1）
 *
 * 所有指令幂等（主服按参数自去重），失败重试 3 次；仍失败抛出由调用方决定回滚/挂起。
 */
@Slf4j
@Component
public class GameServerClient {

    private final MttProperties properties;
    private final RestTemplate restTemplate;

    public GameServerClient(MttProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(f);
    }

    /**
     * 建比赛桌，返回 roomId
     */
    public Long createTable(Long matchId, String name, Long clubId, int seatNum,
                            int baseScore, Map<String, Object> ruleTemplate) {
        JSONObject body = new JSONObject();
        body.put("matchId", matchId);
        body.put("name", name);
        body.put("clubId", clubId);
        body.put("seatNum", seatNum);
        body.put("baseScore", baseScore);
        body.put("rules", ruleTemplate);
        JSONObject resp = post("/internal/match/createTable", body);
        return resp.getJSONObject("data").getLong("roomId");
    }

    /** 批量入座：players=[{userId,seatNo,score}] */
    public void seatPlayers(Long roomId, List<Map<String, Object>> players) {
        JSONObject body = new JSONObject();
        body.put("roomId", roomId);
        body.put("players", players);
        post("/internal/match/seatPlayers", body);
    }

    /** 暂停（局间生效）。返回 true=当前已处于局间、立即暂停完成 */
    public boolean pauseTable(Long roomId) {
        JSONObject body = new JSONObject();
        body.put("roomId", roomId);
        JSONObject resp = post("/internal/match/pauseTable", body);
        JSONObject data = resp.getJSONObject("data");
        return data != null && Boolean.TRUE.equals(data.getBoolean("paused"));
    }

    public void resumeTable(Long roomId) {
        JSONObject body = new JSONObject();
        body.put("roomId", roomId);
        post("/internal/match/resumeTable", body);
    }

    /** 迁移玩家：moves=[{userId,fromRoomId,toRoomId,toSeatNo,score}]（两桌都须已暂停） */
    public void transferPlayers(List<Map<String, Object>> moves) {
        JSONObject body = new JSONObject();
        body.put("moves", moves);
        post("/internal/match/transferPlayers", body);
    }

    /** 升底皮（下一局生效） */
    public void upgradeLevel(Long roomId, int level, int baseScore) {
        JSONObject body = new JSONObject();
        body.put("roomId", roomId);
        body.put("level", level);
        body.put("baseScore", baseScore);
        post("/internal/match/upgradeLevel", body);
    }

    /** 关桌（比赛结束/取消） */
    public void closeTable(Long roomId) {
        JSONObject body = new JSONObject();
        body.put("roomId", roomId);
        post("/internal/match/closeTable", body);
    }

    /** 经主服 WS 给指定玩家推送比赛消息（350~357 段） */
    public void broadcastToUsers(List<Long> userIds, int type, Map<String, Object> data) {
        try {
            JSONObject body = new JSONObject();
            body.put("userIds", userIds);
            body.put("type", type);
            body.put("data", data);
            post("/internal/match/broadcast", body);
        } catch (Exception e) {
            // 推送尽力而为，不影响主流程
            log.warn("比赛消息推送失败: type={}, err={}", type, e.getMessage());
        }
    }

    private JSONObject post(String path, JSONObject body) {
        String url = properties.getGameServer().getBaseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MTT-TOKEN", properties.getInternalToken());
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                String resp = restTemplate.postForObject(url, entity, String.class);
                JSONObject json = JSON.parseObject(resp);
                if (json == null || json.getIntValue("code") != 200) {
                    throw new RuntimeException("主服指令失败: " + path + " → " + resp);
                }
                return json;
            } catch (RuntimeException e) {
                last = e;
                log.warn("主服指令重试({}/3): {} err={}", i + 1, path, e.getMessage());
                try { Thread.sleep(500L * (i + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException("主服指令最终失败: " + path, last);
    }
}
