package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class AccountInviteAnonymizationMigrationTest {
    @Test void allowsDetachedCreatorsAndPreservesExistingRowsAcrossRepeatedMigration() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:invite-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table users(id bigint primary key)");
        jdbc.execute("create table account_invites(id bigint primary key, created_by_user_id bigint not null, "
                + "used_by_user_id bigint, used_at datetime(6), foreign key(created_by_user_id) references users(id))");
        jdbc.update("insert into users(id) values (1)");
        jdbc.update("insert into account_invites(id,created_by_user_id) values (10,1)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261103_048__invite_creator_anonymization.sql"));
        migration.execute(ds); migration.execute(ds);
        assertThat(jdbc.queryForObject("select created_by_user_id from account_invites where id=10", Long.class)).isEqualTo(1L);
        jdbc.update("update account_invites set created_by_user_id=null,revoked_at=current_timestamp where id=10");
        assertThat(jdbc.queryForObject("select created_by_user_id from account_invites where id=10", Long.class)).isNull();
        assertThat(jdbc.queryForObject("select revoked_at from account_invites where id=10", java.sql.Timestamp.class)).isNotNull();
    }
}
