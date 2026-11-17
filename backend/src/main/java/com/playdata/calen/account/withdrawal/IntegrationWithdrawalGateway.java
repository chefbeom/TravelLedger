package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.dto.AccountWithdrawalStartRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Isolated integration environments only; operational withdrawal is not ready. */
@Service
@RequiredArgsConstructor
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class IntegrationWithdrawalGateway {
    private final AccountWithdrawalStartService start;

    public UUID submit(Long userId, AccountWithdrawalStartRequest request) {
        return start.start(userId, request.secondaryPin(), request.familyOwners(), request.groupOwners(), request.travelPlanOwners());
    }
}
