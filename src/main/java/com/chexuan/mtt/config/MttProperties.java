package com.chexuan.mtt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 比赛服配置（application.yml mtt.*）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mtt")
public class MttProperties {

    /** 服务间共享 token（主服必须配同值） */
    private String internalToken;

    /** 发奖失败重试上限 */
    private int payoutMaxRetry = 3;

    private GameServer gameServer = new GameServer();

    @Data
    public static class GameServer {
        /** 主服内部接口基址 */
        private String baseUrl;
    }
}
