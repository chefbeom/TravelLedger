package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Revoke outstanding email proofs when account security changes, even if recovery is later disabled. */
@Service
public class WithdrawalChallengeRevoker {
    private final DataSource dataSource;
    private final WithdrawalEmailChallengeRepository challenges;
    private final boolean workflowEnabled;

    public WithdrawalChallengeRevoker(DataSource dataSource, WithdrawalEmailChallengeRepository challenges,
            @Value("${app.account-withdrawal.internal-workflow-enabled:false}") boolean workflowEnabled) {
        this.dataSource = dataSource;
        this.challenges = challenges;
        this.workflowEnabled = workflowEnabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(Long userId, LocalDateTime now) {
        if (!tableExists()) {
            if (workflowEnabled) throw new ServiceUnavailableException("탈퇴 인증 저장소 설정이 필요합니다.");
            return;
        }
        challenges.invalidateOpenTokens(userId, now);
    }

    private boolean tableExists() {
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                String name = "withdrawal_email_challenges";
                if (metadata.storesUpperCaseIdentifiers()) name = name.toUpperCase(Locale.ROOT);
                try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, name, new String[]{"TABLE"})) {
                    return tables.next();
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (SQLException | DataAccessException exception) {
            throw new ServiceUnavailableException("탈퇴 인증 상태를 확인할 수 없습니다.");
        }
    }
}
