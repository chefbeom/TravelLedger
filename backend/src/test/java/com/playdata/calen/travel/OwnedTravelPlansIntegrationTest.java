package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelPlansAdapter;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:owned-travel-plans;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnedTravelPlansIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelBudgetItemRepository budgets;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired ReadOwnedTravelPlansUseCase reads;
    @Autowired TravelSummaryCache summaries;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoSpyBean JpaOwnedTravelPlansAdapter store;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    JpaOwnedTravelPlansAdapter target;
    record Fixture(long owner, long plan) {}
    @BeforeEach void useOnlyThePrivateDatabaseAndUnwrapMandatorySpy() throws Exception {
        try (var connection = dataSource.getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:owned-travel-plans"); }
        target = AopTestUtils.getUltimateTargetObject(store); reset(target);
    }
    @Test void allThreeGetRoutesRequireAuthentication() throws Exception {
        for (String path : List.of("/api/travel/plans", "/api/travel/plans/1", "/api/travel/portfolio")) mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(redis, storage, coordinator);
    }
    @Test void listDetailAndPortfolioRetainOwnerScopeRawCountsAndExistingJsonFields() throws Exception {
        var f = fixture(); var stranger = fixture();
        var list = json.readTree(mvc.perform(get("/api/travel/plans").with(user(principal(f)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1); assertThat(list.get(0).size()).isEqualTo(20);
        // The legacy aggregate SQL excludes null kind, while detached detail/portfolio treat it as LEDGER.
        assertThat(list.get(0).path("id").asLong()).isEqualTo(f.plan()); assertThat(list.get(0).path("recordCount").asInt()).isZero(); assertThat(list.get(0).path("memoryRecordCount").asInt()).isEqualTo(1);
        var detail = json.readTree(mvc.perform(get("/api/travel/plans/{id}", f.plan()).with(user(principal(f)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.size()).isEqualTo(27); assertThat(detail.path("records").get(0).path("recordType").isNull()).isTrue();
        assertThat(detail.path("routeSegments").get(0).path("durationMinutes").asInt()).isZero();
        var portfolio = json.readTree(mvc.perform(get("/api/travel/portfolio").with(user(principal(f)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(portfolio.size()).isEqualTo(22); assertThat(portfolio.path("includedPlanCount").asInt()).isEqualTo(1); assertThat(portfolio.path("actualTotalKrw").decimalValue()).isEqualByComparingTo("12000");
        mvc.perform(get("/api/travel/plans/{id}", f.plan()).with(user(principal(stranger)))).andExpect(status().isNotFound());
        verifyNoInteractions(storage, coordinator);
    }
    @Test void inactiveOwnerFailsBeforeEvenReadingAnExistingCache() {
        var f = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false));
        assertThatThrownBy(() -> reads.plans(f.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> reads.portfolio(f.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> reads.detail(f.owner(), f.plan())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verifyNoInteractions(redis, storage, coordinator);
    }
    @Test void hitsKeepLegacyDisplayValuesWithoutQueryingChildrenOrPublishing() {
        var f = fixture();
        var wire = new TravelPlanSummaryResponse(f.plan(), "Cached", null, null, null, "old", null, "old-status", "old-color", null, null, null, 0, 0, 0, 0, 0, null, null, null);
        when(redis.get(eq(TravelSummaryCache.plansKey(f.owner())), org.mockito.ArgumentMatchers.<TypeReference<List<TravelPlanSummaryResponse>>>any())).thenReturn(List.of(wire));
        var values = reads.plans(f.owner()); assertThat(values.get(0).head().status()).isEqualTo("old-status");
        verify(target, never()).aggregates(any()); verify(redis, never()).set(anyString(), any(), any());
    }
    @Test void cachePublicationUsesTheSameKeysWireTypesAndSixtySecondTtl() {
        var f = fixture(); reads.plans(f.owner()); reads.portfolio(f.owner());
        verify(redis).set(eq(TravelSummaryCache.plansKey(f.owner())), argThat(v -> v instanceof List<?> values && values.get(0) instanceof TravelPlanSummaryResponse), eq(Duration.ofSeconds(60)));
        verify(redis).set(eq(TravelSummaryCache.portfolioKey(f.owner())), isA(com.playdata.calen.travel.dto.TravelPortfolioResponse.class), eq(Duration.ofSeconds(60)));
    }
    @Test void sharedRegistryInvalidationPreventsAnInflightReaderFromRepublishingStaleData() throws Exception {
        var f = fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { var value = call.callRealMethod(); entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); return value; }).when(target).aggregates(f.owner());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> reads.plans(f.owner())); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            summaries.invalidateOwner(f.owner()); release.countDown(); assertThat(future.get(10, TimeUnit.SECONDS)).hasSize(1);
            verify(redis, never()).set(anyString(), any(), any()); verify(redis).delete(any(String[].class));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void mandatoryStoreAndReadOnlyExecutionRemainInsideTheSingleFlightLoader() {
        var f = fixture(); assertThatThrownBy(() -> store.aggregates(f.owner())).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return call.callRealMethod(); }).when(target).requireActiveOwner(f.owner());
        reads.plans(f.owner()); reads.portfolio(f.owner()); reads.detail(f.owner(), f.plan());
        verify(target, times(3)).requireActiveOwner(f.owner());
    }
    @Test void summaryReadsSuspendAnOuterWriteTransactionBeforeFlightAdmissionAndResumeItAfterward() {
        var f = fixture();
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return call.callRealMethod(); }).when(target).requireActiveOwner(f.owner());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            reads.plans(f.owner()); reads.portfolio(f.owner());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        });
    }
    private AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var u = new AppUser(); u.setLoginId("owned-plans-" + UUID.randomUUID()); u.setDisplayName("Fixture"); u.setPasswordHash("test-only-hash"); users.save(u);
            var p = new TravelPlan(); p.setOwner(u); p.setName("Trip"); p.setStartDate(LocalDate.of(2026, 10, 1)); p.setEndDate(LocalDate.of(2026, 10, 3)); plans.save(p);
            var b = new TravelBudgetItem(); b.setPlan(p); b.setCategory("Food"); b.setTitle("Budget"); b.setAmount(new BigDecimal("20000")); b.setAmountKrw(new BigDecimal("20000")); b.setCurrencyCode("KRW"); b.setExchangeRateToKrw(BigDecimal.ONE); budgets.save(b);
            for (int i = 0; i < 2; i++) {
                var r = new TravelExpenseRecord(); r.setPlan(p); r.setRecordType(i == 0 ? null : TravelRecordType.MEMORY); r.setExpenseDate(p.getStartDate()); r.setCategory("Place"); r.setTitle("Record");
                r.setAmount(new BigDecimal("12000")); r.setAmountKrw(new BigDecimal("12000")); r.setCurrencyCode("KRW"); r.setExchangeRateToKrw(BigDecimal.ONE); records.save(r);
            }
            var path = new TravelRouteSegment(); path.setPlan(p); path.setRouteDate(p.getStartDate()); path.setTitle("Route"); path.setDurationMinutes(null); path.setRoutePathJson("[]"); routes.save(path);
            return new Fixture(u.getId(), p.getId());
        });
    }
}
