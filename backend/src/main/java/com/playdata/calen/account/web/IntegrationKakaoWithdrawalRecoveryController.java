package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AccountWithdrawalPreviewResponse;
import com.playdata.calen.account.dto.AccountWithdrawalStartRequest;
import com.playdata.calen.account.service.KakaoWithdrawalRecoveryService;
import com.playdata.calen.account.withdrawal.RecoveryRequestThrottle;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Isolated integration only. Proxy configuration and real provider behavior still require verification. */
@RestController
@RequestMapping("/api/auth/withdrawal-recovery")
@RequiredArgsConstructor
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class IntegrationKakaoWithdrawalRecoveryController {
    private final KakaoWithdrawalRecoveryService recovery;
    private final RecoveryRequestThrottle throttle;

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> request(@Valid @RequestBody EmailRequest request, Authentication authentication,
                                                       HttpServletRequest httpRequest) {
        requireAnonymous(authentication);
        requireAllowedIp(httpRequest);
        if (throttle.allowEmail(request.email())) recovery.requestEmailProof(request.email());
        return noStore(Map.of("accepted", true, "message", "요청을 처리했습니다. 해당하는 계정이 있다면 인증 메일을 확인해 주세요."));
    }

    @PostMapping("/preview")
    public ResponseEntity<AccountWithdrawalPreviewResponse> preview(@Valid @RequestBody PreviewRequest request, Authentication authentication,
                                                                    HttpServletRequest httpRequest) {
        requireAnonymous(authentication);
        requireAllowedIp(httpRequest);
        var result = recovery.preview(request.token(), request.secondaryPin());
        return noStore(new AccountWithdrawalPreviewResponse(true, result.kakaoLinked(), result.kakaoUnlinkConfigured(),
                result.requiredFamilyTransfers(), result.requiredShareGroupTransfers(), result.requiredTravelPlanTransfers(), result.foreignTravelMedia()));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRequest request, Authentication authentication,
                                                      HttpServletRequest httpRequest) {
        requireAnonymous(authentication);
        requireAllowedIp(httpRequest);
        var choices = request.withdrawal();
        recovery.start(request.token(), choices.secondaryPin(), choices.familyOwners(), choices.groupOwners(), choices.travelPlanOwners());
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of(
                "accepted", true, "message", "탈퇴 요청을 접수했습니다. 자료 정리 완료는 별도로 확인해야 합니다."));
    }

    private void requireAnonymous(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            throw new BadRequestException("다른 계정에서 로그아웃한 뒤 진행해 주세요.");
        }
    }

    private void requireAllowedIp(HttpServletRequest request) {
        if (!throttle.allowIp(request)) throw new TooManyRequestsException("요청이 많습니다. 잠시 후 다시 시도해 주세요.", 600);
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record PreviewRequest(@NotBlank @Size(max = 256) String token,
                                 @NotBlank @Pattern(regexp = "[0-9]{8}") String secondaryPin) {}
    public record StartRequest(@NotBlank @Size(max = 256) String token,
                               @NotNull @Valid AccountWithdrawalStartRequest withdrawal) {}
}
