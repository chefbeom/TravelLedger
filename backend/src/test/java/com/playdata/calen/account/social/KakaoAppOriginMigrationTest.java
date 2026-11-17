package com.playdata.calen.account.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class KakaoAppOriginMigrationTest {
    @Test void preservesLegacyRowsWithoutGuessingOriginAndIsSafeToRepeat() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:kakao-origin-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table social_accounts (id bigint primary key, provider_user_id varchar(128) not null)");
        jdbc.update("insert into social_accounts(id, provider_user_id) values (?, ?)", 1L, "12345");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261103_050__kakao_verified_app_origin.sql"));
        migration.execute(dataSource);
        assertThat(jdbc.queryForObject("select provider_user_id from social_accounts where id=1", String.class)).isEqualTo("12345");
        assertThat(jdbc.queryForObject("select provider_app_id from social_accounts where id=1", Long.class)).isNull();
        jdbc.update("update social_accounts set provider_app_id=? where id=1", 1234L);
        migration.execute(dataSource);
        assertThat(jdbc.queryForObject("select provider_app_id from social_accounts where id=1", Long.class)).isEqualTo(1234L);
    }
}
