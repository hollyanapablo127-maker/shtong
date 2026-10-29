package com.shtong.gateway;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;
import java.util.Set;

@Component
public final class ApiSecurityInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> AUTH_BOOTSTRAP = Set.of("/v1/auth/login");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("X-Request-Id", RequestIds.resolve(request));
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        if (SAFE.contains(request.getMethod()) || AUTH_BOOTSTRAP.contains(request.getRequestURI())) return true;
        String session = cookie(request, "SHTONG_SESSION");
        String refresh = cookie(request, "SHTONG_REFRESH");
        if (session == null && refresh == null) return true;
        String csrf = cookie(request, "SHTONG_CSRF");
        if (csrf == null || !Objects.equals(csrf, request.getHeader("X-CSRF-Token"))) {
            throw new ApiError(403, "CSRF_VALIDATION_FAILED", "error.csrf.failed");
        }
        return true;
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (cookie.getName().equals(name)) return cookie.getValue();
        return null;
    }
}
