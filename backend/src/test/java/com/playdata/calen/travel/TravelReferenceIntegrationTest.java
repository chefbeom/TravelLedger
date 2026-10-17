package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelReferenceAdapter;
import com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
        "app.seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:travel-reference;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelReferenceIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelBudgetItemRepository budgets;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired ReadTravelReferenceUseCase reads;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoSpyBean JpaTravelReferenceAdapter store;
    @MockitoBean ExchangeRateService rates;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    JpaTravelReferenceAdapter target;
    record Fixture(long owner, long budget, String label) {}
    @BeforeEach void onlyThePrivateDatabaseAndAnUnwrappedMandatorySpy() throws Exception {
        try (var connection = dataSource.getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:travel-reference"); }
        target = AopTestUtils.getUltimateTargetObject(store); reset(target);
    }
    @Test void bothGetRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/travel/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/travel/exchange-rates")).andExpect(status().isUnauthorized()); verifyNoInteractions(rates, redis, storage, coordinator);
    }
    @Test void catalogKeepsSevenFieldsDefaultOrderOwnerScopeNullKindsAndNoSummaryCache() throws Exception {
        var f = fixture(); var other = fixture();
        var tree = json.readTree(mvc.perform(get("/api/travel/categories").with(user(principal(f)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tree.size()).isEqualTo(7); assertThat(tree.path("planStatuses").toString()).isEqualTo("[\"PLANNED\",\"COMPLETED\",\"SAMPLE\"]");
        assertThat(tree.path("budgetCategories").toString()).contains(f.label()).doesNotContain(other.label());
        assertThat(tree.path("expenseCategories").toString()).contains(f.label()); assertThat(tree.path("memoryCategories").toString()).contains("Memory " + f.label());
        assertThat(tree.path("countries").toString()).isEqualTo("[\"KR\",\"kr\"]");
        verifyNoInteractions(rates, redis, storage, coordinator);
    }
    @Test void subsequentCatalogReadsSeeChangesWithoutIntroducingACache() {
        var f = fixture(); assertThat(reads.catalog(f.owner()).budgets()).contains(f.label());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> budgets.findById(f.budget()).orElseThrow().setCategory("Changed"));
        assertThat(reads.catalog(f.owner()).budgets()).contains("Changed").doesNotContain(f.label()); verifyNoInteractions(redis);
    }
    @Test void defaultAndExplicitCurrenciesKeepOrderAndTheFiveFieldProviderResponse() throws Exception {
        var f = fixture();
        when(rates.getLatestRates(anyCollection())).thenAnswer(call -> {
            Collection<String> currencies = call.getArgument(0);
            return currencies.stream().map(code -> new TravelExchangeRateResponse(code, new BigDecimal("1.2345678"), LocalDate.of(2026, 10, 17), true, "fixture-provider")).toList();
        });
        var response = json.readTree(mvc.perform(get("/api/travel/exchange-rates").with(user(principal(f)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(5); assertThat(response.get(0).size()).isEqualTo(5); assertThat(response.get(0).path("provider").asText()).isEqualTo("fixture-provider");
        verify(rates).getLatestRates(argThat(codes -> codes.stream().toList().equals(List.of("KRW", "USD", "JPY", "CNY", "EUR"))));
        mvc.perform(get("/api/travel/exchange-rates").param("currencies", "jpy,usd,jpy,").with(user(principal(f)))).andExpect(status().isOk());
        verify(rates).getLatestRates(argThat(codes -> codes.stream().toList().equals(List.of("JPY", "USD", "KRW")))); verifyNoInteractions(redis, storage, coordinator);
    }
    @Test void malformedCurrenciesFailBeforeProviderWhileUnavailableQuotesStillReturnOk() throws Exception {
        var f = fixture();
        mvc.perform(get("/api/travel/exchange-rates").param("currencies", "usd,,jpy").with(user(principal(f)))).andExpect(status().isBadRequest()); verifyNoInteractions(rates);
        when(rates.getLatestRates(anyCollection())).thenReturn(List.of(new TravelExchangeRateResponse("XXX", null, null, false, "Frankfurter")));
        mvc.perform(get("/api/travel/exchange-rates").param("currencies", "XXX").with(user(principal(f)))).andExpect(status().isOk()).andExpect(jsonPath("$[0].available").value(false)).andExpect(jsonPath("$[0].rateToKrw").isEmpty());
    }
    @Test void inactiveOwnerFailsBeforeSourceLookupEvenWhenCurrencyInputIsInvalid() {
        var f = fixture(); new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false));
        assertThatThrownBy(() -> reads.catalog(f.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> reads.rates(f.owner(), "invalid")).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(target, never()).catalogSource(any()); verifyNoInteractions(rates, redis, storage, coordinator);
    }
    @Test void mandatoryOwnerFactsAndExistingReadOnlyRequiredBoundaryArePreserved() {
        var f = fixture(); assertThatThrownBy(() -> store.catalogSource(f.owner())).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return call.callRealMethod(); }).when(target).requireActiveOwner(f.owner());
        reads.catalog(f.owner());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return List.of(); }).when(rates).getLatestRates(anyCollection());
        reads.rates(f.owner(), "krw"); reset(target);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse(); return List.of(); }).when(rates).getLatestRates(anyCollection());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> reads.rates(f.owner(), "krw"));
    }
    private AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var u = new AppUser(); u.setLoginId("reference-" + UUID.randomUUID()); u.setDisplayName("Fixture"); u.setPasswordHash("test-only-hash"); users.save(u);
            var p = new TravelPlan(); p.setOwner(u); p.setName("Trip"); p.setStartDate(LocalDate.of(2026, 10, 1)); p.setEndDate(LocalDate.of(2026, 10, 3)); plans.save(p);
            var label = "Custom-" + u.getId();
            var b = new TravelBudgetItem(); b.setPlan(p); b.setCategory(label); b.setTitle("Budget"); b.setAmount(BigDecimal.ONE); b.setAmountKrw(BigDecimal.ONE); b.setCurrencyCode("KRW"); b.setExchangeRateToKrw(BigDecimal.ONE); budgets.save(b);
            for (int i = 0; i < 2; i++) {
                var r = new TravelExpenseRecord(); r.setPlan(p); r.setRecordType(i == 0 ? null : TravelRecordType.MEMORY); r.setExpenseDate(p.getStartDate()); r.setCategory(i == 0 ? label : "Memory " + label);
                r.setCountry(i == 0 ? " KR " : "kr"); r.setTitle("Record"); r.setAmount(BigDecimal.ONE); r.setAmountKrw(BigDecimal.ONE); r.setCurrencyCode("KRW"); r.setExchangeRateToKrw(BigDecimal.ONE); records.save(r);
            }
            return new Fixture(u.getId(), b.getId(), label);
        });
    }
}
