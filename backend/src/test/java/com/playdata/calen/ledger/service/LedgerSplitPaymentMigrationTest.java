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

class LedgerSplitPaymentMigrationTest {
    @Test void existingTransactionsArePreservedAndPartsAreUniquePerOwner() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:split-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("create table ledger_entries (id bigint primary key, owner_id bigint not null)");
        jdbc.update("insert into ledger_entries (id, owner_id) values (1, 10), (2, 10), (3, 11)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261030_033_2__ledger_split_installments.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        assertThat(jdbc.queryForObject("select installment_months from ledger_entries where id=1", Integer.class)).isNull();
        jdbc.update("update ledger_entries set split_payment_group_id='group-a', split_payment_index=1 where id=1");
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set split_payment_group_id='group-a', split_payment_index=1 where id=2"))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("update ledger_entries set split_payment_group_id='group-a', split_payment_index=1 where id=3");
    }
}
