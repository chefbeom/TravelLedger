package com.playdata.calen.account.web;

import com.playdata.calen.account.service.RestoreMaintenanceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RestoreMaintenanceFilter extends OncePerRequestFilter {

    private final RestoreMaintenanceService restoreMaintenanceService;

    public RestoreMaintenanceFilter(RestoreMaintenanceService restoreMaintenanceService) {
        this.restoreMaintenanceService = restoreMaintenanceService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!restoreMaintenanceService.isRestoreInProgress() || isAllowedDuringRestore(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"데이터 복구가 진행 중입니다. 잠시 후 다시 시도해 주세요.\"}");
    }

    private boolean isAllowedDuringRestore(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || uri.startsWith("/actuator/health")
                || uri.startsWith("/api/admin/data-management")
                || uri.startsWith("/api/admin/access");
    }
}
