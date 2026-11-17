package com.playdata.calen.account.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SocialCredentialSchemaSupportTest {
    @Test
    void allowsOnlyAnExplicitlyNullablePasswordColumn() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getColumns(any(), any(), eq("app_users"), eq("password_hash"))).thenReturn(columns);
        when(columns.next()).thenReturn(true);
        when(columns.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNullable, DatabaseMetaData.columnNoNulls);
        var support = new SocialCredentialSchemaSupport(source);
        assertThatCode(support::requireNullablePasswordColumn).doesNotThrowAnyException();
        assertThatThrownBy(support::requireNullablePasswordColumn).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void databaseInspectionFailureDoesNotPermitSignup() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("database unavailable"));
        assertThatThrownBy(() -> new SocialCredentialSchemaSupport(source).requireNullablePasswordColumn())
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
