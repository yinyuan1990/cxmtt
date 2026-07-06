package com.chexuan.mtt.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部接口鉴权：所有请求必须带 X-MTT-TOKEN（主服代理/主服上报/运营后台反代统一持有）。
 * 比赛服不做 JWT —— 客户端请求由主服校验 JWT 后代理进来（规划 §2.2）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTokenFilter extends OncePerRequestFilter {

    private final MttProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader("X-MTT-TOKEN");
        if (token == null || !token.equals(properties.getInternalToken())) {
            log.warn("内部token校验失败: uri={}, ip={}", request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"code\":401,\"message\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 健康检查放行
        return request.getRequestURI().startsWith("/actuator");
    }
}
