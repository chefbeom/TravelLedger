package com.playdata.calen.account.dto;

import java.util.List;

public record AdminDashboardResponse(
        AdminSummaryResponse summary,
        List<AdminLoginAuditResponse> recentLoginLogs,
        List<AdminBlockedIpResponse> blockedIps,
        List<AdminUserSummaryResponse> users,
        List<AdminInviteSummaryResponse> recentInvites
) {
}
