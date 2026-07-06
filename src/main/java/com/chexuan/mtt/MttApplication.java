package com.chexuan.mtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 扯旋 MTT 竞标赛服务（赛事大脑）
 *
 * 职责：比赛配置/报名/奖池/心跳调度/分桌决策/升底皮/淘汰名次/拆并桌决策/奖励结算/账务流水。
 * 不碰一张牌 —— 牌局执行全部在主服 chexuan-game（比赛桌模式），通过内部 REST 指令/上报交互。
 *
 * 设计文档：src/最新核心文档/扯旋MTT竞标赛-后端规划.html（真相之源，改设计先改它）
 */
@SpringBootApplication
@EnableScheduling
public class MttApplication {

    public static void main(String[] args) {
        SpringApplication.run(MttApplication.class, args);
    }
}
