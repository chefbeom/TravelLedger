package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

/** Existing pending requests must stay protected even when the test workflow is switched off. */
@Service
public class PendingWithdrawalGuard {
    private final DataSource dataSource;
    private final boolean workflowEnabled;

    public PendingWithdrawalGuard(DataSource dataSource,
            @Value("${app.account-withdrawal.internal-workflow-enabled:false}") boolean workflowEnabled) {
        this.dataSource = dataSource;
        this.workflowEnabled = workflowEnabled;
    }

    public void requireNoPendingWithdrawal(Long userId) {
        // Use a locking/current read, not a potentially stale repeatable-read snapshot.
        if (hasPendingWithdrawalForUpdate(userId)) {
            throw new BadRequestException("탈퇴 처리 중인 계정은 다시 활성화할 수 없습니다.");
        }
    }

    /** Current locking read, including on databases whose ordinary reads use an older snapshot. */
    public boolean hasPendingWithdrawalForUpdate(Long userId) {
        if (!requestTableExists()) {
            if (workflowEnabled) throw new ServiceUnavailableException("탈퇴 작업 저장소 설정이 필요합니다.");
            return false;
        }
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement query = connection.prepareStatement(
                    "select id from account_withdrawal_requests where user_id = ? for update")) {
                query.setLong(1, userId);
                try (ResultSet rows = query.executeQuery()) { return rows.next(); }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (SQLException | DataAccessException exception) {
            throw new ServiceUnavailableException("탈퇴 작업 상태를 확인할 수 없습니다.");
        }
    }

    private boolean requestTableExists() {
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                String table = "account_withdrawal_requests";
                if (metadata.storesUpperCaseIdentifiers()) table = table.toUpperCase(Locale.ROOT);
                try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
                    return tables.next();
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (SQLException | DataAccessException exception) {
            throw new ServiceUnavailableException("탈퇴 작업 상태를 확인할 수 없습니다.");
        }
    }
}
