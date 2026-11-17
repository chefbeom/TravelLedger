package com.playdata.calen.account.security;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.transaction.annotation.Transactional;

/** Keeps cookies and existing usernames intact while binding a series to an immutable user ID. */
public class BoundRememberMeTokenRepository implements AccountRememberMeTokens {
    private static final String ID_PREFIX = "{calen-user-id}:";
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JdbcTokenRepositoryImpl legacy;
    private volatile boolean boundColumnSeen;

    public BoundRememberMeTokenRepository(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.legacy = new JdbcTokenRepositoryImpl();
        this.legacy.setDataSource(dataSource);
    }

    @Override
    @Transactional
    public void createNewToken(PersistentRememberMeToken token) {
        if (!hasBoundColumn()) {
            legacy.createNewToken(token);
            return;
        }
        Long userId = lockActiveUserId(token.getUsername());
        if (userId == null) throw new UsernameNotFoundException("사용자 계정을 찾을 수 없습니다.");
        jdbc.update("insert into persistent_logins(username, series, token, last_used, user_id) values (?, ?, ?, ?, ?)",
                token.getUsername(), token.getSeries(), token.getTokenValue(), new Timestamp(token.getDate().getTime()), userId);
    }

    @Override
    @Transactional
    public PersistentRememberMeToken getTokenForSeries(String seriesId) {
        if (!hasBoundColumn()) return legacy.getTokenForSeries(seriesId);
        TokenRow row = jdbc.query("select username, series, token, last_used, user_id from persistent_logins where series = ?",
                result -> result.next() ? new TokenRow(result.getString(1), result.getString(2), result.getString(3),
                        result.getTimestamp(4), (Long) result.getObject(5, Long.class)) : null, seriesId);
        if (row == null || row.lastUsed() == null) return null;
        Long userId = row.userId();
        if (userId == null) {
            // An unbound name may be an orphan left by an older node or deleted account.
            // It cannot safely be matched to a user who registered later with the same ID.
            // V040 binds rows present during migration; other rows require a new login.
            return null;
        }
        return new PersistentRememberMeToken(boundUsername(userId), row.series(), row.token(), new Date(row.lastUsed().getTime()));
    }

    @Override
    public void updateToken(String series, String tokenValue, Date lastUsed) {
        if (!hasBoundColumn()) {
            legacy.updateToken(series, tokenValue, lastUsed);
            return;
        }
        int updated = jdbc.update("update persistent_logins set token = ?, last_used = ? where series = ? and user_id is not null",
                tokenValue, new Timestamp(lastUsed.getTime()), series);
        if (updated != 1) throw new InvalidCookieException("자동 로그인 정보가 더 이상 유효하지 않습니다.");
    }

    @Override
    public void removeUserTokens(String username) {
        if (!hasBoundColumn()) {
            legacy.removeUserTokens(username);
            return;
        }
        OptionalLong boundId = parseBoundUsername(username);
        if (boundId.isPresent()) {
            removeUserTokensById(boundId.getAsLong());
        }
    }

    @Override
    public void removeUserTokensById(Long userId) {
        if (userId == null || userId <= 0) return;
        if (!hasBoundColumn()) {
            List<String> names = jdbc.queryForList("select login_id from app_users where id = ?", String.class, userId);
            if (!names.isEmpty()) legacy.removeUserTokens(names.get(0));
            return;
        }
        jdbc.update("delete from persistent_logins where user_id = ? or (user_id is null and username = "
                + "(select login_id from app_users where id = ?))", userId, userId);
    }

    @Override
    public boolean isBoundSchemaAvailable() {
        return hasBoundColumn();
    }

    @Override
    public void requireBoundSchema() {
        if (!hasBoundColumn()) throw new ServiceUnavailableException("안전한 탈퇴를 위해 자동 로그인 저장소 마이그레이션이 필요합니다.");
    }

    public static OptionalLong parseBoundUsername(String username) {
        if (username == null || !username.startsWith(ID_PREFIX)) return OptionalLong.empty();
        String value = username.substring(ID_PREFIX.length());
        if (!value.matches("[1-9][0-9]{0,18}")) return OptionalLong.empty();
        try { return OptionalLong.of(Long.parseLong(value)); } catch (NumberFormatException exception) { return OptionalLong.empty(); }
    }

    private static String boundUsername(long userId) { return ID_PREFIX + userId; }

    private Long lockActiveUserId(String loginId) {
        List<Long> ids = jdbc.queryForList("select id from app_users where login_id = ? and active = true and email_verified = true for update",
                Long.class, loginId);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    private boolean hasBoundColumn() {
        if (boundColumnSeen) return true;
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            String table = metadata.storesUpperCaseIdentifiers() ? "PERSISTENT_LOGINS" : "persistent_logins";
            String column = metadata.storesUpperCaseIdentifiers() ? "USER_ID" : "user_id";
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, column)) {
                if (columns.next()) boundColumnSeen = true;
            }
            return boundColumnSeen;
        } catch (SQLException exception) {
            throw new ServiceUnavailableException("자동 로그인 저장소를 확인할 수 없습니다.");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private record TokenRow(String username, String series, String token, Timestamp lastUsed, Long userId) {}
}
