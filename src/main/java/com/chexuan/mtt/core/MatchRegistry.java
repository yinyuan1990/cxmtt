package com.chexuan.mtt.core;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的比赛注册表（matchId → MatchContext）
 */
@Component
public class MatchRegistry {

    private final Map<Long, MatchContext> contexts = new ConcurrentHashMap<>();

    public MatchContext getOrCreate(Long matchId) {
        return contexts.computeIfAbsent(matchId, MatchContext::new);
    }

    public MatchContext get(Long matchId) {
        return contexts.get(matchId);
    }

    public void remove(Long matchId) {
        contexts.remove(matchId);
    }

    public Collection<MatchContext> all() {
        return contexts.values();
    }
}
