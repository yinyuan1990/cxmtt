package com.chexuan.mtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 扯旋 MTT 竞标赛服务（赛事大脑）
 *
 * 职责：比赛配置/报名/奖池/心跳调度/分桌决策/升底皮/淘汰名次/拆并桌决策/奖励结算/账务流水。
 * 不碰一张牌 —— 牌局执行全部在主服 chexuan-game（比赛桌模式），通过内部 REST 指令/上报交互。
 *
 * 设计文档：src/最新核心文档/扯旋MTT竞标赛-后端规划.html（真相之源，改设计先改它）
 *
 * ⭐ 显式声明仓储扫描包：MttRepositories 里的仓储都是嵌套接口，打包成 Spring Boot 3.2
 * 嵌套 jar（jar:nested:）后默认的 classpath 扫描扫不到嵌套接口（Found 0 JPA repository
 * interfaces），必须显式指定 basePackages 才能正确注册。
 */
@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.chexuan.mtt.repository")
public class MttApplication {

    public static void main(String[] args) {
        SpringApplication.run(MttApplication.class, args);
    }
}
