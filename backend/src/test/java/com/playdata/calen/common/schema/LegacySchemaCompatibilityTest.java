package com.playdata.calen.common.schema;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.config.HouseholdGoalSchemaUpdater;
import com.playdata.calen.ledger.config.*;
import com.playdata.calen.travel.config.*;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacySchemaCompatibilityTest {
    private static final List<Class<?>> UPDATERS = List.of(LedgerAiAnalysisSchemaUpdater.class,LedgerEntrySchemaUpdater.class,
            LedgerEntryChangeHistorySchemaUpdater.class,TravelMediaAssetSchemaUpdater.class,TravelPhotoClusterSchemaUpdater.class,
            TravelRouteSchemaUpdater.class,HouseholdGoalSchemaUpdater.class,LedgerImageAnalysisRequestSchemaUpdater.class,TravelMapShareLinkSchemaUpdater.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(context -> {
            // Test absent-property semantics independently of CI's APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false.
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }).withBean(DataSource.class,() -> dataSource).withBean(JdbcTemplate.class,() -> jdbc)
                .withUserConfiguration(UPDATERS.toArray(Class<?>[]::new));
    }
    @Test void missingPropertyPreservesAllNineLegacyBeansWithoutExecutingSql() {
        context().run(context -> {
            assertThat(context).hasNotFailed();
            for (var type : UPDATERS) assertThat(context).hasSingleBean(type);
        });
        verifyNoInteractions(dataSource);
        assertNoSqlExecuted();
    }
    @ParameterizedTest @ValueSource(strings={"false","FALSE","0","no",""})
    void disabledOrNonTruePropertyRemovesEveryDocumentedUpdater(String value) {
        context().withPropertyValues("app.schema.legacy-updaters.enabled="+value).run(context -> {
            assertThat(context).hasNotFailed();
            for (var type : UPDATERS) assertThat(context).doesNotHaveBean(type);
        });
        verifyNoInteractions(dataSource);
        assertNoSqlExecuted();
    }
    @ParameterizedTest @ValueSource(strings={"true","TRUE"})
    void explicitTruePreservesTheExistingCaseInsensitiveCondition(String value) {
        context().withPropertyValues("app.schema.legacy-updaters.enabled="+value).run(context -> {
            assertThat(context).hasNotFailed();
            for (var type : UPDATERS) assertThat(context).hasSingleBean(type);
        });
        verifyNoInteractions(dataSource);
        assertNoSqlExecuted();
    }
    @ParameterizedTest @CsvSource({"true,true,true","true,false,false","false,true,true","false,false,false"})
    void flywayFlagDoesNotSilentlyChangeTheLegacySwitch(String flyway,String legacy,boolean present) {
        context().withPropertyValues("spring.flyway.enabled="+flyway,"app.schema.legacy-updaters.enabled="+legacy).run(context -> {
            assertThat(context).hasNotFailed();
            for (var type : UPDATERS) {
                if (present) assertThat(context).hasSingleBean(type); else assertThat(context).doesNotHaveBean(type);
            }
        });
        verifyNoInteractions(dataSource);
        assertNoSqlExecuted();
    }
    @Test void everyExistingUpdaterUsesTheExactSameMergedPropertyContract() {
        for (var type : UPDATERS) {
            assertThat(type.isAnnotationPresent(LegacySchemaCompatibility.class)).as(type.getName()).isTrue();
            var condition = MergedAnnotations.from(type).get(ConditionalOnProperty.class).synthesize();
            assertThat(condition.prefix()).isEqualTo("app.schema.legacy-updaters"); assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("true"); assertThat(condition.matchIfMissing()).isTrue();
        }
    }
    @Test void existingUpdatersSkipNonMysqlDatabasesWithoutExecutingDdl() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        // Media/route updaters check table existence before their product guard.
        var tables = mock(ResultSet.class);
        when(metadata.getTables(isNull(),isNull(),anyString(),isNull())).thenReturn(tables);
        when(tables.next()).thenReturn(true);
        context().run(context -> {
            assertThat(context).hasNotFailed();
            for (var type : UPDATERS) {
                try { ((ApplicationRunner)context.getBean(type)).run(mock(ApplicationArguments.class)); }
                catch (Exception failure) { throw new AssertionError(type.getName(),failure); }
            }
        });
        verify(dataSource,times(9)).getConnection(); verify(connection,times(9)).close();
        assertNoSqlExecuted();
    }

    private void assertNoSqlExecuted() {
        // Spring invokes InitializingBean even on the mock; no query/update/execute is allowed.
        assertThat(mockingDetails(jdbc).getInvocations())
                .allSatisfy(invocation -> assertThat(invocation.getMethod().getName()).isEqualTo("afterPropertiesSet"));
    }
}
