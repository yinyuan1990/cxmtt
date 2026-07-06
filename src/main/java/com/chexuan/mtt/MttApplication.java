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
 * ⭐ 显式声明仓储扫描包（双重保险）：早期版本把仓储写成嵌套接口（外层包一个 MttRepositories
 * 类），Spring Data 在嵌套 jar（jar:nested:）打包下扫描不到嵌套接口（Found 0 JPA repository
 * interfaces），已改为每个仓储独立文件（顶层接口）根治；这里保留显式 basePackages 以防万一。
 */
@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.chexuan.mtt.repository")
public class MttApplication {

    public static void main(String[] args) {
        SpringApplication.run(MttApplication.class, args);
    }
}
