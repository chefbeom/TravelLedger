package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:personalization-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class HouseholdPersonalizationIntegrationTest {
    private static final String PATH = "/api/account/preferences/household-personalization";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    AppUser owner;

    @BeforeEach void createOwner() { owner = createUser(); }

    @Test void freshAccountGetsTheSameReadableDefaultsFromBothApisWithoutSavingThem() throws Exception {
        JsonNode preferences = read(owner);
        JsonNode aggregate = mapper.readTree(mvc.perform(get("/api/account/preferences/household-aggregates")
                        .with(user(AppUserPrincipal.from(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preferences.path("widgets")).isEqualTo(aggregate.path("widgets"));
        assertThat(preferences.at("/widgets/0/kind").asText()).isEqualTo("PAYMENT_METHOD");
        assertThat(preferences.at("/widgets/1/kind").asText()).isEqualTo("MONTHLY_GOAL");
        assertThat(preferences.at("/widgets/2/kind").asText()).isEqualTo("TOTAL");
        assertThat(preferences.at("/widgets/3/kind").asText()).isEqualTo("MONTHLY_CUMULATIVE_CHART");
        assertThat(List.of(preferences.at("/widgets/0/cardColumns").asInt(), preferences.at("/widgets/1/cardColumns").asInt(),
                preferences.at("/widgets/2/cardColumns").asInt(), preferences.at("/widgets/3/cardColumns").asInt()))
                .containsExactly(2, 2, 2, 3);
        assertThat(users.findById(owner.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isNull();
    }

    @Test void readsLegacySettingsWithoutChangingThem() throws Exception {
        owner.setHouseholdAggregateSettingsJson("[{\"kind\":\"TOTAL\",\"period\":\"YEAR\",\"amountType\":\"INCOME\"}]");
        users.saveAndFlush(owner);
        JsonNode initial = read(owner);
        assertThat(initial.path("widgets").size()).isEqualTo(6);
        assertThat(initial.at("/widgets/0/period").asText()).isEqualTo("YEAR");
        assertThat(initial.at("/widgets/0/title").asText()).isEmpty();
        assertThat(initial.at("/widgets/0/hidden").asBoolean()).isFalse();
        assertThat(read(owner)).isEqualTo(initial);
        assertThat(users.findById(owner.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isEqualTo(owner.getHouseholdAggregateSettingsJson());
    }

    @Test void savesOneBundleAndKeepsExistingApisCompatible() throws Exception {
        ObjectNode draft = draft(owner);
        ((ObjectNode) draft.at("/widgets/0")).put("title", "내 월간 합계").put("hidden", true).put("displaySize", "LARGE");
        JsonNode saved = write(owner, draft, 200);
        assertThat(saved.at("/widgets/0/title").asText()).isEqualTo("내 월간 합계");
        assertThat(saved.at("/widgets/0/hidden").asBoolean()).isTrue();
        assertThat(saved.at("/widgets/0/displaySize").asText()).isEqualTo("LARGE");
        assertThat(saved.path("layout")).isEqualTo(draft.path("layout"));
        assertThat(saved.path("preferences")).isEqualTo(draft.path("preferences"));
        assertThat(read(owner)).isEqualTo(saved);
        JsonNode old = mapper.readTree(mvc.perform(get("/api/account/preferences/household-aggregates").with(user(AppUserPrincipal.from(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(old.path("widgets")).isEqualTo(saved.path("widgets"));
    }

    @Test void olderClientDoesNotEraseNewWidgetMetadata() throws Exception {
        ObjectNode draft = draft(owner);
        ((ObjectNode) draft.at("/widgets/0")).put("title", "사용자 제목").put("hidden", true).put("displaySize", "SMALL");
        JsonNode saved = write(owner, draft, 200);
        var widgets = saved.path("widgets").deepCopy();
        for (var widget : widgets) ((ObjectNode) widget).remove(List.of("title", "hidden", "displaySize"));
        var legacy = mapper.createObjectNode().set("widgets", widgets);
        mvc.perform(put("/api/account/preferences/household-aggregates").with(user(AppUserPrincipal.from(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(legacy))).andExpect(status().isOk());
        assertThat(read(owner).at("/widgets/0/title").asText()).isEqualTo("사용자 제목");
        assertThat(read(owner).at("/widgets/0/hidden").asBoolean()).isTrue();
        assertThat(read(owner).at("/widgets/0/displaySize").asText()).isEqualTo("SMALL");
    }

    @Test void staleRevisionCannotOverwriteAnotherWindow() throws Exception {
        ObjectNode first = draft(owner), stale = first.deepCopy();
        ((ObjectNode) first.path("preferences")).put("highlightMode", "income");
        JsonNode committed = write(owner, first, 200);
        ((ObjectNode) stale.path("preferences")).put("highlightMode", "expense");
        write(owner, stale, 409);
        assertThat(read(owner)).isEqualTo(committed);
    }

    @Test void legacyViewWritesPreserveNewDefaultsAndInvalidateTheModernRevision() throws Exception {
        ObjectNode draft = draft(owner);
        ((ObjectNode) draft.path("preferences")).put("quickEntryPanelEnabled", false).put("defaultWeekMode", "current");
        JsonNode committed = write(owner, draft, 200);
        ObjectNode legacy = ((ObjectNode) committed.path("preferences")).deepCopy();
        legacy.remove(List.of("quickEntryPanelEnabled", "defaultWeekMode"));
        legacy.put("highlightMode", "income");
        ObjectNode request = mapper.createObjectNode().put("version", 2);
        request.set("payload", legacy);
        mvc.perform(put("/api/account/preferences/layout-settings/household-calendar-view")
                        .with(user(AppUserPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request))).andExpect(status().isOk());
        JsonNode current = read(owner);
        assertThat(current.at("/preferences/quickEntryPanelEnabled").asBoolean()).isFalse();
        assertThat(current.at("/preferences/defaultWeekMode").asText()).isEqualTo("current");
        assertThat(current.at("/preferences/highlightMode").asText()).isEqualTo("income");
        ObjectNode stale = (ObjectNode) committed;
        write(owner, stale, 409);
    }

    @Test void settingsAreScopedToAuthenticatedOwner() throws Exception {
        AppUser other = createUser();
        JsonNode otherBefore = read(other);
        ObjectNode wrongOwnerRevision = draft(owner);
        wrongOwnerRevision.put("revision", otherBefore.path("revision").asText());
        write(owner, wrongOwnerRevision, 409);
        write(owner, draft(owner), 200);
        assertThat(read(other)).isEqualTo(otherBefore);
    }

    @Test void invalidSettingsReturn400AndLeaveAllStoresUnchanged() throws Exception {
        JsonNode before = read(owner);
        ObjectNode invalid = draft(owner);
        ((ObjectNode) invalid.at("/widgets/0")).putNull("kind");
        write(owner, invalid, 400);
        invalid = draft(owner);
        ((ObjectNode) invalid.at("/layout/0")).put("w", 100);
        write(owner, invalid, 400);
        invalid = draft(owner);
        ((ObjectNode) invalid.path("preferences")).put("receiptOcrPanelEnabled", "true");
        write(owner, invalid, 400);
        invalid = draft(owner);
        ((ObjectNode) invalid.at("/widgets/0")).put("period", "INVALID");
        write(owner, invalid, 400);
        invalid = draft(owner);
        ((ObjectNode) invalid.at("/widgets/0")).put("layoutW", -1);
        write(owner, invalid, 400);
        assertThat(read(owner)).isEqualTo(before);
    }

    @Test void downstreamSaveFailureRollsBackAggregateAndLayoutWritesTogether() throws Exception {
        JsonNode before = read(owner);
        ObjectNode invalid = draft(owner);
        ((ObjectNode) invalid.at("/widgets/0")).put("title", "저장되면 안 됨");
        ((ObjectNode) invalid.at("/layout/0")).put("oversized", "x".repeat(200_001));
        write(owner, invalid, 400);
        assertThat(read(owner)).isEqualTo(before);
    }

    @Test void concurrentEditorsOnlyCommitOneRevision() throws Exception {
        ObjectNode first = draft(owner), second = first.deepCopy();
        ((ObjectNode) first.path("preferences")).put("highlightMode", "income");
        ((ObjectNode) second.path("preferences")).put("highlightMode", "expense");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstResult = pool.submit(() -> { ready.countDown(); start.await(); return putStatus(owner, first); });
            var secondResult = pool.submit(() -> { ready.countDown(); start.await(); return putStatus(owner, second); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void directWidgetSizeAndDetailsRoundTripAndSurviveLegacyWrites() throws Exception {
        ObjectNode request = draft(owner);
        ((ObjectNode) request.at("/widgets/0")).put("cardWidth", 360).put("cardHeight", 240)
                .put("showPeriod", false).put("showDetails", false);
        JsonNode committed = write(owner, request, 200);
        assertThat(committed.at("/widgets/0/cardWidth").asInt()).isEqualTo(360);
        assertThat(read(owner).at("/widgets/0/cardHeight").asInt()).isEqualTo(240);
        ObjectNode legacy = mapper.createObjectNode();
        var legacyWidgets = committed.path("widgets").deepCopy();
        for (JsonNode widget : legacyWidgets) ((ObjectNode) widget).remove(List.of("cardWidth", "cardHeight", "showPeriod", "showDetails"));
        legacy.set("widgets", legacyWidgets);
        mvc.perform(put("/api/account/preferences/household-aggregates").with(user(AppUserPrincipal.from(owner)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(legacy))).andExpect(status().isOk());
        JsonNode current = read(owner);
        assertThat(current.at("/widgets/0/cardWidth").asInt()).isEqualTo(360);
        assertThat(current.at("/widgets/0/cardHeight").asInt()).isEqualTo(240);
        assertThat(current.at("/widgets/0/showPeriod").asBoolean()).isFalse();
        assertThat(current.at("/widgets/0/showDetails").asBoolean()).isFalse();
        ObjectNode reset = (ObjectNode) current;
        ((ObjectNode) reset.at("/widgets/0")).put("cardWidth", 0).put("cardHeight", 0);
        JsonNode automatic = write(owner, reset, 200);
        assertThat(automatic.at("/widgets/0/cardWidth").isNull()).isTrue();
        assertThat(automatic.at("/widgets/0/cardHeight").isNull()).isTrue();
    }

    @Test void anotherAuthenticatedDeviceRestoresTheCompleteAccountPaletteFromTheDatabase() throws Exception {
        var firstDevice = new MockHttpSession();
        var secondDevice = new MockHttpSession();
        ObjectNode request = draft(owner);
        ((ObjectNode) request.at("/widgets/0")).put("title", "다른 컴퓨터에서도 유지").put("cardWidth", 310).put("cardHeight", 230)
                .put("cardColumns", 3).put("cardRows", 2)
                .put("textSize", "LARGE").put("textColor", "SKY").put("showPeriod", false).put("showDetails", false);
        ((ObjectNode) request.at("/layout/1")).put("w", 5).put("h", 7);
        ((ObjectNode) request.path("preferences")).put("receiptOcrPanelEnabled", false).put("highlightMode", "income");
        JsonNode committed = mapper.readTree(mvc.perform(put(PATH).session(firstDevice)
                        .with(user(AppUserPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        AppUser freshlyLoadedOwner = users.findById(owner.getId()).orElseThrow();
        JsonNode restored = mapper.readTree(mvc.perform(get(PATH).session(secondDevice)
                        .with(user(AppUserPrincipal.from(freshlyLoadedOwner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstDevice.getId()).isNotEqualTo(secondDevice.getId());
        assertThat(restored).isEqualTo(committed);
        assertThat(read(createUser()).at("/widgets/0/title").asText()).isNotEqualTo("다른 컴퓨터에서도 유지");
    }

    @Test void invalidDirectWidgetDimensionsCannotChangeSavedSettings() throws Exception {
        JsonNode before = read(owner);
        for (String field : List.of("cardWidth", "cardHeight")) for (int value : List.of(-1, 1, 1201)) {
            ObjectNode request = draft(owner);
            ((ObjectNode) request.at("/widgets/0")).put(field, value);
            write(owner, request, 400);
        }
        assertThat(read(owner)).isEqualTo(before);
    }

    @Test void gridCardDimensionsRoundTripWithoutErasingLegacySizesAndSurviveOlderClients() throws Exception {
        ObjectNode request = draft(owner);
        ((ObjectNode) request.at("/widgets/0")).put("cardColumns", 3).put("cardRows", 2)
                .put("cardWidth", 310).put("cardHeight", 230);
        JsonNode saved = write(owner, request, 200);
        assertThat(saved.at("/widgets/0/cardColumns").asInt()).isEqualTo(3);
        assertThat(read(owner).at("/widgets/0/cardRows").asInt()).isEqualTo(2);
        ObjectNode legacy = mapper.createObjectNode();
        JsonNode legacyWidgets = saved.path("widgets").deepCopy();
        for (JsonNode widget : legacyWidgets) ((ObjectNode) widget).remove(List.of("cardColumns", "cardRows"));
        legacy.set("widgets", legacyWidgets);
        mvc.perform(put("/api/account/preferences/household-aggregates").with(user(AppUserPrincipal.from(owner)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(legacy)))
                .andExpect(status().isOk());
        JsonNode restored = read(owner);
        assertThat(restored.at("/widgets/0/cardColumns").asInt()).isEqualTo(3);
        assertThat(restored.at("/widgets/0/cardRows").asInt()).isEqualTo(2);
        assertThat(restored.at("/widgets/0/cardWidth").asInt()).isEqualTo(310);
        assertThat(restored.at("/widgets/0/cardHeight").asInt()).isEqualTo(230);
        ObjectNode reset = (ObjectNode) restored;
        ((ObjectNode) reset.at("/widgets/0")).put("cardColumns", 0).put("cardRows", 0);
        JsonNode automatic = write(owner, reset, 200);
        assertThat(automatic.at("/widgets/0/cardColumns").isNull()).isTrue();
        assertThat(automatic.at("/widgets/0/cardRows").isNull()).isTrue();
        assertThat(automatic.at("/widgets/0/cardWidth").asInt()).isEqualTo(310);
    }

    @Test void invalidGridCardDimensionsCannotChangeTheAccountSettings() throws Exception {
        JsonNode before = read(owner);
        for (String field : List.of("cardColumns", "cardRows")) for (int value : List.of(-1, field.equals("cardColumns") ? 10 : 13)) {
            ObjectNode request = draft(owner);
            ((ObjectNode) request.at("/widgets/0")).put(field, value);
            write(owner, request, 400);
        }
        ObjectNode request = draft(owner);
        ((ObjectNode) request.at("/widgets/0")).put("kind", "MONTHLY_CUMULATIVE_CHART").put("cardColumns", 1);
        write(owner, request, 400);
        assertThat(read(owner)).isEqualTo(before);
    }

    @Test void queryPalettePositionRoundTripsAndSurvivesLegacyLayoutWrites() throws Exception {
        ObjectNode request = draft(owner);
        var query = mapper.createObjectNode().put("id", "calendar-query").put("x", 0).put("y", 9).put("w", 9).put("h", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("layout")).add(query);
        JsonNode saved = write(owner, request, 200);
        assertThat(saved.at("/layout/4")).isEqualTo(query);
        assertThat(read(owner).path("layout")).isEqualTo(saved.path("layout"));
        var legacyLayout = (com.fasterxml.jackson.databind.node.ArrayNode) saved.path("layout").deepCopy();
        legacyLayout.remove(4);
        var legacy = mapper.createObjectNode().put("version", 3);
        legacy.set("payload", legacyLayout);
        mvc.perform(put("/api/account/preferences/layout-settings/household-calendar")
                        .with(user(AppUserPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(legacy))).andExpect(status().isOk());
        assertThat(read(owner).at("/layout/4")).isEqualTo(query);
        // The older unified editor also sends four panels; it must not delete the fifth.
        JsonNode compatible = write(owner, draft(owner), 200);
        assertThat(compatible.at("/layout/4")).isEqualTo(query);
    }

    @Test void malformedQueryPalettesCannotChangeSavedSettings() throws Exception {
        JsonNode before = read(owner);
        for (String field : List.of("x", "w", "h", "y")) {
            ObjectNode request = draft(owner);
            var query = mapper.createObjectNode().put("id", "calendar-query").put("x", 0).put("y", 9).put("w", 9).put("h", 1);
            query.put(field, switch (field) { case "w" -> 8; case "h" -> 13; case "y" -> -1; default -> 1; });
            ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("layout")).add(query);
            write(owner, request, 400);
        }
        ObjectNode duplicate = draft(owner);
        var query = mapper.createObjectNode().put("id", "calendar-query").put("x", 0).put("y", 9).put("w", 9).put("h", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("layout")).add(query).add(query.deepCopy());
        write(owner, duplicate, 400);
        assertThat(read(owner)).isEqualTo(before);
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setLoginId("personal-" + UUID.randomUUID()); user.setDisplayName("개인화 검증"); user.setPasswordHash("test-only");
        return users.saveAndFlush(user);
    }
    private JsonNode read(AppUser user) throws Exception {
        return mapper.readTree(mvc.perform(get(PATH).with(user(AppUserPrincipal.from(user))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private ObjectNode draft(AppUser user) throws Exception {
        ObjectNode draft = (ObjectNode) read(user);
        draft.set("preferences", mapper.readTree("{\"scalePreset\":\"fit\",\"highlightMode\":\"net\",\"defaultWeekMode\":\"month\",\"aggregatePanelEnabled\":true,\"quickEntryPanelEnabled\":true,\"receiptOcrPanelEnabled\":true,\"transactionSheetViewMode\":\"modal\",\"shareAfterSaveEnabled\":false}"));
        draft.set("layout", mapper.readTree("[{\"id\":\"aggregate\",\"x\":0,\"y\":12,\"w\":9,\"h\":2},{\"id\":\"calendar\",\"x\":0,\"y\":0,\"w\":6,\"h\":6},{\"id\":\"quick-entry\",\"x\":6,\"y\":0,\"w\":3,\"h\":6},{\"id\":\"sheet\",\"x\":0,\"y\":15,\"w\":9,\"h\":3}]"));
        return draft;
    }
    private JsonNode write(AppUser user, ObjectNode draft, int expected) throws Exception {
        var result = mvc.perform(put(PATH).with(user(AppUserPrincipal.from(user))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().is(expected)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private int putStatus(AppUser user, ObjectNode draft) throws Exception {
        return mvc.perform(put(PATH).with(user(AppUserPrincipal.from(user))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(draft)))
                .andReturn().getResponse().getStatus();
    }
}
