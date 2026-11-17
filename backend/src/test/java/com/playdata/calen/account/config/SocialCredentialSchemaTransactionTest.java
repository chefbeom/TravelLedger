package com.playdata.calen.account.config;

import com.playdata.calen.account.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:social-schema-single-connection;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=2000"
})
class SocialCredentialSchemaTransactionTest {
    @Autowired private AppUserRepository users;
    @Autowired private SocialCredentialSchemaSupport support;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void checksTheSchemaUsingTheExistingTransactionConnection() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.count(); // Occupy the only connection with the JPA transaction.
            support.requireNullablePasswordColumn();
        });
    }
}
