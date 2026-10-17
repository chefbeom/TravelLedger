package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.application.port.out.TravelBudgetStore;
import com.playdata.calen.travel.domain.model.TravelBudgetData.Draft;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.service.ExchangeRateService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:travel-budget-refactoring-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelBudgetRefactoringIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired TravelBudgetItemRepository budgets;
    @Autowired WriteTravelBudgetUseCase writes;
    @Autowired TravelBudgetStore store;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RedisCacheService redis;
    @MockitoBean ExchangeRateService rates;
    @MockitoBean com.playdata.calen.travel.service.TravelClusterRefreshCoordinator clusters;

    @BeforeEach void clearMockCalls() { clearInvocations(redis, rates); }

    @Test void createUpdateAndDeletePreserveJsonRoundingAndPlanSummaries() throws Exception {
        long owner = userId("hana"), plan = plan();
        clearInvocations(redis);
        String raw = mvc.perform(post("/api/travel/plans/{id}/budget-items", plan).with(as("hana")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("1.2345", "3"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(raw);
        long id = created.path("id").asLong();
        var expected = new TravelBudgetItemResponse(id, "Food", "Lunch", new BigDecimal("1.23"), "USD",
                new BigDecimal("3.000000"), new BigDecimal("3.70"), "note", 0);
        assertThat(raw).isEqualTo(json.writeValueAsString(expected));
        verifyInvalidations(owner, 2);
        mvc.perform(get("/api/travel/plans/{id}", plan).with(as("hana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedTotalKrw").value(3.70)).andExpect(jsonPath("$.budgetItems.length()").value(1));
        clearInvocations(redis);
        mvc.perform(put("/api/travel/budget-items/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("7.776", "1.0050004"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(7.78)).andExpect(jsonPath("$.amountKrw").value(7.81));
        assertThat(budgets.findById(id).orElseThrow().getAmountKrw()).isEqualByComparingTo("7.81");
        verifyInvalidations(owner, 2);
        clearInvocations(redis);
        mvc.perform(delete("/api/travel/budget-items/{id}", id).with(as("hana")).with(csrf())).andExpect(status().isOk());
        assertThat(budgets.existsById(id)).isFalse();
        verifyInvalidations(owner, 2);
        mvc.perform(get("/api/travel/plans/{id}", plan).with(as("hana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedTotalKrw").value(0)).andExpect(jsonPath("$.budgetItems.length()").value(0));
        verifyNoInteractions(rates);
    }

    @Test void ownerAuthenticationCsrfAndValidationAreUnchanged() throws Exception {
        long plan = plan();
        long id = postBudget("hana", plan, "10", "3").path("id").asLong();
        clearInvocations(redis, rates);
        mvc.perform(post("/api/travel/plans/{id}/budget-items", plan).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("10", "3")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/travel/plans/{id}/budget-items", plan).with(as("hana")).contentType(MediaType.APPLICATION_JSON).content(body("10", "3")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/travel/plans/{id}/budget-items", plan).with(as("minsu")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("10", "3"))).andExpect(status().isNotFound());
        mvc.perform(put("/api/travel/budget-items/{id}", id).with(as("minsu")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("10", "3"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/budget-items/{id}", id).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(put("/api/travel/budget-items/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("0", "3"))).andExpect(status().isBadRequest());
        assertThat(budgets.findById(id).orElseThrow().getAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(redis, rates);
    }

    @Test void rollbackRestoresBudgetAndDoesNotPerformCommitInvalidation() throws Exception {
        long owner = userId("hana"), plan = plan();
        long id = postBudget("hana", plan, "10", "3").path("id").asLong();
        clearInvocations(redis);
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(tx -> {
            writes.update(owner, id, new Draft("Food", "changed", BigDecimal.TEN, "KRW", null, null, null));
            budgets.flush();
            assertThat(budgets.findById(id).orElseThrow().getTitle()).isEqualTo("changed");
            verifyInvalidations(owner, 1);
            tx.setRollbackOnly();
        });
        assertThat(budgets.findById(id).orElseThrow().getTitle()).isEqualTo("Lunch");
        verifyInvalidations(owner, 1);
        clearInvocations(redis);
        transaction.executeWithoutResult(tx -> {
            writes.update(owner, id, new Draft("Food", "committed", BigDecimal.TEN, "KRW", null, null, null));
            verifyInvalidations(owner, 1);
        });
        assertThat(budgets.findById(id).orElseThrow().getTitle()).isEqualTo("committed");
        verifyInvalidations(owner, 2);
    }

    @Test void persistenceAdapterRequiresTheUseCaseTransaction() {
        assertThatThrownBy(() -> store.requireOwnedPlan(userId("hana"), 1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void verifyInvalidations(long owner, int times) {
        verify(redis, times(times)).delete(TravelSummaryCache.plansKey(owner), TravelSummaryCache.portfolioKey(owner),
                TravelSummaryCache.mapKey(owner), TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }

    private long userId(String login) { return users.findByLoginId(login).orElseThrow().getId(); }
    private RequestPostProcessor as(String login) { return user(AppUserPrincipal.from(users.findByLoginId(login).orElseThrow())); }
    private long plan() throws Exception {
        return json.readTree(mvc.perform(post("/api/travel/plans").with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "budget refactoring", "startDate", "2026-10-10", "endDate", "2026-10-15",
                        "homeCurrency", "KRW", "headCount", 1)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    private String body(String amount, String rate) throws Exception {
        return json.writeValueAsString(Map.of("category", " Food ", "title", " Lunch ", "amount", new BigDecimal(amount),
                "currencyCode", "usd", "exchangeRateToKrw", new BigDecimal(rate), "memo", " note "));
    }

    private JsonNode postBudget(String login, long plan, String amount, String rate) throws Exception {
        return json.readTree(mvc.perform(post("/api/travel/plans/{id}/budget-items", plan).with(as(login)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(amount, rate))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
