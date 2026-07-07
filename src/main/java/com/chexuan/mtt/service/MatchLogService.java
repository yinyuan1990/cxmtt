package com.chexuan.mtt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ⭐ 比赛日志（按比赛/房间分文件，排查赛况用）
 *
 * 目录结构：
 *   {mtt.match-log-dir}/m{matchId}/match.log          比赛级事件(创建/报名/分桌/升底皮/终局/发奖)
 *   {mtt.match-log-dir}/m{matchId}/room-{roomId}.log  房间级事件(建桌/入座/每局上报/淘汰/暂停恢复/迁移/关桌)
 *
 * 设计：追加写+失败不抛（日志绝不影响业务），单条一行带时间戳。
 */
@Slf4j
@Service
public class MatchLogService {

    @Value("${mtt.match-log-dir:/www/chexuan-logs/mtt}")
    private String baseDir;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 比赛级日志：m{matchId}/match.log */
    public void match(Long matchId, String message) {
        append(dir(matchId).resolve("match.log"), message);
    }

    /** 房间级日志：m{matchId}/room-{roomId}.log（同时在 match.log 记一行摘要方便纵览） */
    public void room(Long matchId, Long roomId, String message) {
        append(dir(matchId).resolve("room-" + roomId + ".log"), message);
    }

    /** 房间级 + 比赛级各记一份（重要节点用：建桌/终局/迁移） */
    public void both(Long matchId, Long roomId, String message) {
        room(matchId, roomId, message);
        match(matchId, "[room-" + roomId + "] " + message);
    }

    private Path dir(Long matchId) {
        return Paths.get(baseDir, "m" + matchId);
    }

    private void append(Path file, String message) {
        try {
            Files.createDirectories(file.getParent());
            String line = LocalDateTime.now().format(TS) + " " + message + System.lineSeparator();
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("比赛日志写入失败(不影响业务): {}, err={}", file, e.getMessage());
        }
    }
}
