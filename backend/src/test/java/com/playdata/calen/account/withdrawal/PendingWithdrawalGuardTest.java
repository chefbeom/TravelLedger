package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PendingWithdrawalGuardTest {
    @Test void missingTableDoesNotBreakLegacyAdminWhenWorkflowIsDisabled() {
        var guard = new PendingWithdrawalGuard(emptyDatabase(), false);
        assertThatCode(() -> guard.requireNoPendingWithdrawal(3L)).doesNotThrowAnyException();
    }

    @Test void missingTableCannotBeUsedWithWorkflowEnabled() {
        var guard = new PendingWithdrawalGuard(emptyDatabase(), true);
        assertThatThrownBy(() -> guard.requireNoPendingWithdrawal(3L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test void unavailableMetadataCannotSilentlyPermitReactivation() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("unavailable"));
        var guard = new PendingWithdrawalGuard(dataSource, false);
        assertThatThrownBy(() -> guard.requireNoPendingWithdrawal(3L)).isInstanceOf(ServiceUnavailableException.class);
    }

    private DataSource emptyDatabase() {
        var db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:guard-empty-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return db;
    }
}
