package com.playdata.calen.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryChangeHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.sharing.adapter.support.RecordShareContractMapper;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareCommand;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareUseCase;
import com.playdata.calen.sharing.application.port.out.ShareRequestsPort;
import com.playdata.calen.sharing.application.port.out.ShareSourcePort;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Receipt;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Every HTTP request finishes its own transaction, so dirty checking is tested
// across commits, not hidden by a test-wide rollback or mocked repositories.
@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:record-share-edited-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false",
        "app.redis.enabled=false", "app.travel.media-storage-path=build/record-share-edited-test-media"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(RecordShareEditedSnapshotIntegrationTest.SourceProbeConfiguration.class)
class RecordShareEditedSnapshotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired CategoryGroupRepository categories;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryRepository entries;
    @Autowired LedgerEntryChangeHistoryRepository history;
    @Autowired LedgerEntryService ledger;
    @Autowired RecordShareRepository shares;
    @Autowired CreateRecordShareUseCase creation;
    @Autowired ShareRequestsPort creationStore;
    @Autowired RecordSharingService sharing;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SourceLockProbe sourceProbe;

    @Test
    void editedShareCommitsOnlySnapshotThenImportsUsingRecipientsOwnCatalog() throws Exception {
        Fixture f = fixture();
        long entryCount = entries.count(), historyCount = history.count();
        Map<String, Object> edit = edit(f);
        // Sender travel IDs are deliberately ignored, including forged links.
        edit.put("travelPlanId", 999999L);
        edit.put("travelRecordId", 999998L);
        JsonNode shared = postJson("hana", "/api/record-shares", request(f, edit)).get(0);
        long id = shared.path("id").asLong();
        assertThat(shared.path("ledger").path("title").asText()).isEqualTo("공유용 저녁 절반");
        assertThat(shared.path("ledger").path("amount").decimalValue()).isEqualByComparingTo("7500");
        assertThat(shared.path("ledger").path("entryDate").asText()).isEqualTo("2026-10-14");
        assertThat(LocalTime.parse(shared.path("ledger").path("entryTime").asText())).isEqualTo(LocalTime.of(18, 24));
        assertThat(shared.path("ledger").path("memo").asText()).isEqualTo("공유 거래 메모");
        assertThat(shared.path("shareMemo").asText()).isEqualTo("금액을 나눠서 보내요");
        assertThat(shared.path("ledger").path("travelPlanId").isNull()).isTrue();
        assertThat(shared.path("ledger").path("travelRecordId").isNull()).isTrue();
        assertOriginalUnchanged(f);
        assertThat(entries.count()).isEqualTo(entryCount);
        assertThat(history.count()).isEqualTo(historyCount);

        JsonNode received = json.readTree(mvc.perform(get("/api/record-shares").with(as("minsu")).param("kind", "LEDGER"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(received.path("items").findValuesAsText("title")).contains("공유용 저녁 절반");
        Map<String, Object> approval = new LinkedHashMap<>(edit);
        approval.put("categoryGroupId", f.receiverCategory());
        approval.put("paymentMethodId", f.receiverPayment());
        JsonNode accepted = postJson("minsu", "/api/record-shares/" + id + "/accept-ledger", approval);
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        long imported = accepted.path("importedLedgerEntryId").asLong();
        JsonNode saved = json.valueToTree(ledger.getEntryForSharing(userId("minsu"), imported));
        assertThat(saved.path("title").asText()).isEqualTo("공유용 저녁 절반");
        assertThat(saved.path("amount").decimalValue()).isEqualByComparingTo("7500");
        assertThat(saved.path("categoryGroupId").asLong()).isEqualTo(f.receiverCategory());
        assertThat(saved.path("paymentMethodId").asLong()).isEqualTo(f.receiverPayment());
        assertThat(saved.path("travelPlanId").isNull()).isTrue();
        assertOriginalUnchanged(f);
        assertThat(entries.count()).isEqualTo(entryCount + 1);
    }

    @Test
    void changedPendingShareRequiresCancellationButIdenticalRetryIsIdempotent() throws Exception {
        Fixture f = fixture();
        Map<String, Object> edit = edit(f);
        long id = postJson("hana", "/api/record-shares", request(f, edit)).get(0).path("id").asLong();
        long count = shares.count();
        assertThat(postJson("hana", "/api/record-shares", request(f, edit)).get(0).path("id").asLong()).isEqualTo(id);
        assertThat(shares.count()).isEqualTo(count);
        edit.put("title", "취소 후 다시 보낼 내용");
        postStatus("hana", "/api/record-shares", request(f, edit), 400);
        assertThat(shares.findById(id).orElseThrow().getTitle()).isEqualTo("공유용 저녁 절반");
        postJson("hana", "/api/record-shares/" + id + "/cancel", Map.of());
        assertThat(postJson("hana", "/api/record-shares", request(f, edit)).get(0).path("title").asText()).isEqualTo("취소 후 다시 보낼 내용");
        assertOriginalUnchanged(f);
    }

    @Test
    void rejectsInvalidDraftAndForeignUsersCatalogBeforeCreatingShare() throws Exception {
        Fixture f = fixture();
        long count = shares.count();
        Map<String, Object> edit = edit(f);
        edit.put("title", " ");
        postStatus("hana", "/api/record-shares", request(f, edit), 400);
        edit = edit(f);
        edit.put("categoryGroupId", f.receiverCategory());
        postStatus("hana", "/api/record-shares", request(f, edit), 404);
        edit = edit(f);
        edit.put("paymentMethodId", f.receiverPayment());
        postStatus("hana", "/api/record-shares", request(f, edit), 404);
        edit = edit(f);
        edit.put("foreignCurrencyCode", "USD");
        edit.put("foreignAmount", 10);
        postStatus("hana", "/api/record-shares", request(f, edit), 400);
        assertThat(shares.count()).isEqualTo(count);
        assertOriginalUnchanged(f);
    }

    @Test
    void legacyRequestWithoutOverrideSharesOriginalUnchanged() throws Exception {
        Fixture f = fixture();
        Map<String, Object> body = request(f, edit(f));
        body.remove("ledgerOverride");
        JsonNode shared = postJson("hana", "/api/record-shares", body).get(0);
        assertSameLedger(shared.path("ledger"), f.original());
        assertOriginalUnchanged(f);
    }

    @Test
    void laterRecipientFailureRollsBackEarlierRecipientAndPreservesSource() throws Exception {
        Fixture f = fixture();
        long count = shares.count();
        AppUser outsider = new AppUser();
        outsider.setLoginId("outsider-" + UUID.randomUUID()); outsider.setDisplayName("그룹 외 사용자"); outsider.setPasswordHash("test-placeholder");
        users.saveAndFlush(outsider);
        Map<String, Object> body = request(f, edit(f));
        body.put("recipientIds", List.of(userId("minsu"), outsider.getId()));
        postStatus("hana", "/api/record-shares", body, 404);
        assertThat(shares.count()).isEqualTo(count);
        assertOriginalUnchanged(f);

        // Updating an existing canceled request must also roll back as a unit.
        long id = postJson("hana", "/api/record-shares", request(f, edit(f))).get(0).path("id").asLong();
        postJson("hana", "/api/record-shares/" + id + "/cancel", Map.of());
        var canceled = shares.findById(id).orElseThrow();
        String originalSnapshot = canceled.getSnapshotJson();
        var respondedAt = canceled.getRespondedAt();
        postStatus("hana", "/api/record-shares", body, 404);
        var unchanged = shares.findById(id).orElseThrow();
        assertThat(shares.count()).isEqualTo(count + 1);
        assertThat(unchanged.getStatus()).isEqualTo(RecordShareStatus.CANCELED);
        assertThat(unchanged.getSnapshotJson()).isEqualTo(originalSnapshot);
        assertThat(unchanged.getRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void simultaneousCreationQueuesOnlyOneRequest() throws Exception {
        Fixture f = fixture();
        long before = shares.count(), senderId = userId("hana");
        var command = command(request(f, edit(f)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<List<Receipt>> action = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
            return creation.create(senderId, command);
        };
        try {
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).get(0).id()).isEqualTo(second.get(10, TimeUnit.SECONDS).get(0).id());
            assertThat(shares.count()).isEqualTo(before + 1);
            assertOriginalUnchanged(f);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void approvalCannotBeUndoneByConcurrentReshareThroughAnotherGroup() throws Exception {
        Fixture f = fixture();
        long senderId = userId("hana"), receiverId = userId("minsu");
        long id = postJson("hana", "/api/record-shares", request(f, edit(f))).get(0).path("id").asLong();
        long nextGroup = postJson("hana", "/api/travel/share-groups", Map.of("name", "race-" + UUID.randomUUID(), "recipientLoginIds", List.of("minsu"))).path("id").asLong();
        Map<String, Object> body = request(f, edit(f)); body.remove("ledgerOverride"); body.put("groupId", nextGroup);
        var command = command(body);
        Map<String, Object> own = edit(f); own.put("categoryGroupId", f.receiverCategory()); own.put("paymentMethodId", f.receiverPayment());
        var approval = json.convertValue(own, LedgerEntryRequest.class);
        long entriesBefore = entries.count();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<List<Receipt>>> reShare = new AtomicReference<>();
        CountDownLatch sourceLocked = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                shares.findLockedById(id).orElseThrow();
                sourceProbe.next.set(sourceLocked);
                reShare.set(executor.submit(() -> creation.create(senderId, command)));
                try { assertThat(sourceLocked.await(5, TimeUnit.SECONDS)).as("reshare reached the source lock while approval owns the share row").isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                sharing.acceptLedger(receiverId, id, approval);
            });
            Receipt result = reShare.get().get(10, TimeUnit.SECONDS).get(0);
            assertThat(result.status()).isEqualTo(RecordShareStatus.ACCEPTED);
            assertThat(result.groupId()).isEqualTo(f.groupId());
            assertThat(result.importedLedgerEntryId()).isNotNull();
            assertThat(shares.findById(id).orElseThrow().getStatus()).isEqualTo(RecordShareStatus.ACCEPTED);
            assertThat(entries.count()).isEqualTo(entriesBefore + 1);
            assertOriginalUnchanged(f);
        } finally { sourceProbe.next.set(null); executor.shutdownNow(); }
    }

    @Test
    void persistencePortCannotBeInvokedWithoutTheCreationTransaction() {
        assertThatThrownBy(() -> creationStore.lockExisting(RecordShareKind.LEDGER, 1L, 2L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private CreateRecordShareCommand command(Map<String, Object> body) {
        return RecordShareContractMapper.command(json.convertValue(body, RecordShareDtos.Create.class));
    }

    static class SourceLockProbe { final AtomicReference<CountDownLatch> next = new AtomicReference<>(); }
    @TestConfiguration
    static class SourceProbeConfiguration {
        @Bean SourceLockProbe sourceLockProbe() { return new SourceLockProbe(); }
        @Bean @Primary ShareSourcePort observedSource(@Qualifier("jpaShareSourceAdapter") ShareSourcePort actual, SourceLockProbe probe) {
            return (owner, kind, source, edit) -> {
                var result = actual.lockOwnedSource(owner, kind, source, edit);
                var latch = probe.next.getAndSet(null);
                if (latch != null) latch.countDown();
                return result;
            };
        }
    }

    private Fixture fixture() throws Exception {
        AppUser sender = users.findByLoginId("hana").orElseThrow();
        AppUser receiver = users.findByLoginId("minsu").orElseThrow();
        long senderCategory = category(sender), receiverCategory = category(receiver);
        long senderPayment = payment(sender), receiverPayment = payment(receiver);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entryDate", "2026-10-13"); body.put("entryTime", "12:30:00");
        body.put("title", "내 원본 저녁 식사"); body.put("memo", "원본 거래 메모"); body.put("amount", 15000);
        body.put("entryType", "EXPENSE"); body.put("categoryGroupId", senderCategory); body.put("paymentMethodId", senderPayment);
        JsonNode original = postJson("hana", "/api/entries", body);
        long group = postJson("hana", "/api/travel/share-groups", Map.of("name", "share-edit-" + UUID.randomUUID(), "recipientLoginIds", List.of("minsu"))).path("id").asLong();
        return new Fixture(original, group, senderCategory, senderPayment, receiverCategory, receiverPayment);
    }

    private Map<String, Object> edit(Fixture f) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entryDate", "2026-10-14"); body.put("entryTime", "18:24:00");
        body.put("title", "공유용 저녁 절반"); body.put("memo", "공유 거래 메모"); body.put("amount", 7500);
        body.put("entryType", "EXPENSE"); body.put("categoryGroupId", f.senderCategory()); body.put("paymentMethodId", f.senderPayment());
        return body;
    }

    private Map<String, Object> request(Fixture f, Map<String, Object> edit) {
        return new LinkedHashMap<>(Map.of("kind", "LEDGER", "sourceId", f.original().path("id").asLong(), "groupId", f.groupId(),
                "recipientIds", List.of(userId("minsu")), "shareMemo", "금액을 나눠서 보내요", "ledgerOverride", edit));
    }

    private void assertOriginalUnchanged(Fixture f) throws Exception {
        assertSameLedger(json.valueToTree(ledger.getEntryForSharing(userId("hana"), f.original().path("id").asLong())), f.original());
    }

    private void assertSameLedger(JsonNode actual, JsonNode expected) throws Exception {
        assertThat(json.treeToValue(actual, LedgerEntryResponse.class)).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(json.treeToValue(expected, LedgerEntryResponse.class));
    }

    private long category(AppUser owner) {
        CategoryGroup group = new CategoryGroup(); group.setOwner(owner); group.setName("식비"); group.setEntryType(EntryType.EXPENSE);
        return categories.saveAndFlush(group).getId();
    }

    private long payment(AppUser owner) {
        PaymentMethod method = new PaymentMethod(); method.setOwner(owner); method.setName("카드"); method.setKind(PaymentMethodKind.CARD);
        return payments.saveAndFlush(method).getId();
    }

    private long userId(String login) { return users.findByLoginId(login).orElseThrow().getId(); }
    private RequestPostProcessor as(String login) { return user(AppUserPrincipal.from(users.findByLoginId(login).orElseThrow())); }
    private JsonNode postJson(String login, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).with(as(login)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void postStatus(String login, String path, Object body, int expectedStatus) throws Exception {
        mvc.perform(post(path).with(as(login)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(expectedStatus));
    }

    private record Fixture(JsonNode original, long groupId, long senderCategory, long senderPayment, long receiverCategory, long receiverPayment) {}
}
