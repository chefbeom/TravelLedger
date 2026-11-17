package com.playdata.calen.account.config;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/** Checks the migration at signup without performing startup DDL. */
@Component
@RequiredArgsConstructor
public class SocialCredentialSchemaSupport {
    private final DataSource dataSource;

    public void requireNullablePasswordColumn() {
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                String table = "app_users";
                String column = "password_hash";
                if (metadata.storesUpperCaseIdentifiers()) {
                    table = table.toUpperCase(Locale.ROOT);
                    column = column.toUpperCase(Locale.ROOT);
                }
                try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, column)) {
                    if (columns.next() && columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable) return;
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (SQLException | DataAccessException exception) {
            throw new ServiceUnavailableException("카카오 가입에 필요한 데이터베이스 상태를 확인할 수 없습니다.");
        }
        throw new ServiceUnavailableException("카카오 간편가입을 사용하려면 데이터베이스 마이그레이션이 필요합니다.");
    }
}
