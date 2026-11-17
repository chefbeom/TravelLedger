package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Prevent a pre-existing repeatable-read snapshot from hiding recently committed sharing data. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class WithdrawalTransactionGuard {
    private final DataSource dataSource;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireReadCommitted() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new ServiceUnavailableException("탈퇴 작업의 안전한 트랜잭션 격리 수준을 확인할 수 없습니다.");
            }
        } catch (SQLException exception) {
            throw new ServiceUnavailableException("탈퇴 작업의 트랜잭션 상태를 확인할 수 없습니다.");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
