package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class LedgerAdjustmentMigrationTest {
    @Test void preservesExistingRowsAndAddsOwnerScopedIdempotency() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:adjust-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("create table ledger_entries (id bigint primary key, owner_id bigint not null)");
        jdbc.update("insert into ledger_entries (id, owner_id) values (1, 10), (2, 10)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261030_033_1__ledger_payment_adjustments.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        assertThat(jdbc.queryForObject("select financially_excluded from ledger_entries where id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select refunded_principal from ledger_entries where id=1", java.math.BigDecimal.class)).isEqualByComparingTo("0");
        jdbc.update("update ledger_entries set adjustment_request_key = 'same' where id=1");
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set adjustment_request_key = 'same' where id=2"))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("insert into ledger_entries (id, owner_id, adjustment_request_key) values (3, 11, 'same')");
    }
}
