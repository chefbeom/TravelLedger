package com.playdata.calen.account.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.account.withdrawal.WithdrawalCleanupKind;
import com.playdata.calen.account.withdrawal.WithdrawalCleanupTask;
import com.playdata.calen.account.withdrawal.WithdrawalCleanupTaskRepository;
import com.playdata.calen.account.withdrawal.IntegrationWithdrawalRetryService;
import com.playdata.calen.account.dto.ProfileSecondaryPinVerifyRequest;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only test status; no user identifiers, object paths, credentials, or task payloads are returned. */
@RestController
@RequestMapping("/api/admin/withdrawal-monitor")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class IntegrationWithdrawalMonitorController {
    private final AdminPageAccessService access;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskRepository tasks;
    private final IntegrationWithdrawalRetryService retryService;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<MonitorPage> list(@AuthenticationPrincipal AppUserPrincipal principal,
                                             HttpServletRequest httpRequest,
                                             @RequestParam(defaultValue = "0") int page) {
        access.requireVerified(httpRequest, principal.userId());
        if (page < 0 || page > 1000) throw new BadRequestException("페이지 범위를 확인해 주세요.");
        var result = requests.findAll(PageRequest.of(page, 20,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))));
        List<String> ids = result.getContent().stream().map(request -> request.getId()).toList();
        Map<String, List<TaskCount>> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : tasks.summarizeByRequestIds(ids)) {
                counts.computeIfAbsent((String) row[0], ignored -> new ArrayList<>())
                        .add(new TaskCount((WithdrawalCleanupKind) row[1], (WithdrawalCleanupTask.State) row[2], (Long) row[3]));
            }
        }
        List<RequestSummary> content = result.getContent().stream().map(request -> new RequestSummary(
                request.getId(), request.getRequestedAt(), request.getUpdatedAt(),
                List.copyOf(counts.getOrDefault(request.getId(), List.of())))).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new MonitorPage(page, result.hasNext(), content));
    }

    @GetMapping("/{requestId}/tasks")
    @Transactional(readOnly = true)
    public ResponseEntity<TaskPage> tasks(@AuthenticationPrincipal AppUserPrincipal principal,
                                         HttpServletRequest httpRequest, @PathVariable UUID requestId,
                                         @RequestParam(defaultValue = "0") int page) {
        access.requireVerified(httpRequest, principal.userId());
        if (page < 0 || page > 1000 || !requests.existsById(requestId.toString())) {
            throw new BadRequestException("탈퇴 요청 또는 페이지를 확인해 주세요.");
        }
        var result = tasks.findAllByRequestIdOrderByCreatedAtAscIdAsc(requestId.toString(), PageRequest.of(page, 20));
        var content = result.getContent().stream().map(task -> new TaskSummary(task.getId(), task.getKind(),
                task.getState(), task.getAttempts(), safeCode(task.getLastErrorCode()), task.getNextAttemptAt(),
                IntegrationWithdrawalRetryService.canRetry(task))).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TaskPage(page, result.hasNext(), content));
    }

    @PostMapping("/{requestId}/tasks/{taskId}/retry")
    public ResponseEntity<Void> retry(@AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest httpRequest,
                                      @PathVariable UUID requestId, @PathVariable UUID taskId,
                                      @Valid @RequestBody ProfileSecondaryPinVerifyRequest body) {
        access.requireVerified(httpRequest, principal.userId());
        retryService.retry(principal.userId(), requestId, taskId, body.secondaryPin(),
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,50}") ? code : null;
    }

    public record TaskCount(WithdrawalCleanupKind kind, WithdrawalCleanupTask.State state, long count) {}
    public record RequestSummary(String requestId, LocalDateTime requestedAt, LocalDateTime updatedAt, List<TaskCount> tasks) {}
    public record MonitorPage(int page, boolean hasNext, List<RequestSummary> content) {}
    public record TaskSummary(String taskId, WithdrawalCleanupKind kind, WithdrawalCleanupTask.State state,
                              int attempts, String errorCode, LocalDateTime nextAttemptAt, boolean retryable) {}
    public record TaskPage(int page, boolean hasNext, List<TaskSummary> content) {}
}
