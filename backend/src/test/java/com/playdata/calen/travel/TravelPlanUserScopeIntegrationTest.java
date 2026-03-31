package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:travel-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TravelPlanUserScopeIntegrationTest {

    private static final byte[] TEST_JPEG_BYTES = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46,
            0x49, 0x46, 0x00, 0x01
    };
    private static final Pattern PUBLIC_MEDIA_URL_PATTERN =
            Pattern.compile("/api/travel/public/media/(\\d+)/content\\?token=(.+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void travelPlanCrudIsScopedPerUser() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");
        MockHttpSession minsuSession = loginAndGetSession("minsu");

        Long planId = createTravelPlan(hanaSession, "Osaka trip");
        Long budgetItemId = createBudgetItem(hanaSession, planId, "Flight", "Round trip ticket", "450000", "KRW", "1");
        Long recordId = createExpenseRecord(hanaSession, planId, "Food", "Takoyaki", "1200", "JPY", "9.100000");

        assertPresignFallsBackToServer(hanaSession, recordId);
        uploadTravelMedia(hanaSession, recordId);

        mockMvc.perform(get("/api/travel/plans/{planId}", planId).session(minsuSession))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/travel/budget-items/{itemId}", budgetItemId).with(csrf()).session(minsuSession))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/travel/plans/{planId}", planId).session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Osaka trip"))
                .andExpect(jsonPath("$.headCount").value(4))
                .andExpect(jsonPath("$.colorHex").value("#FF6B6B"))
                .andExpect(jsonPath("$.budgetItems.length()").value(1))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].expenseTime").value("18:40:00"))
                .andExpect(jsonPath("$.records[0].id").value(recordId))
                .andExpect(jsonPath("$.records[0].planColorHex").value("#FF6B6B"))
                .andExpect(jsonPath("$.records[0].country").value("Japan"))
                .andExpect(jsonPath("$.records[0].placeName").value("Dotonbori"))
                .andExpect(jsonPath("$.mediaItems.length()").value(1))
                .andExpect(jsonPath("$.mediaItems[0].mediaType").value("PHOTO"))
                .andExpect(jsonPath("$.mediaItems[0].planColorHex").value("#FF6B6B"));

        MvcResult portfolioResult = mockMvc.perform(get("/api/travel/portfolio").session(hanaSession))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode portfolio = objectMapper.readTree(portfolioResult.getResponse().getContentAsString());
        assertThat(portfolio.path("scopeType").asText()).isEqualTo("ALL");
        assertThat(portfolio.path("includedPlanCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(portfolio.path("mediaItemCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(portfolio.path("records").isArray()).isTrue();
        boolean containsRecord = false;
        for (JsonNode recordNode : portfolio.path("records")) {
            if (recordNode.path("id").asLong() == recordId) {
                containsRecord = true;
                break;
            }
        }
        assertThat(containsRecord).isTrue();

        boolean containsPlan = false;
        for (JsonNode planNode : portfolio.path("plans")) {
            if (planNode.path("id").asLong() == planId) {
                containsPlan = true;
                break;
            }
        }
        assertThat(containsPlan).isTrue();

        mockMvc.perform(delete("/api/travel/plans/{planId}", planId).with(csrf()).session(hanaSession))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/travel/plans/{planId}", planId).session(hanaSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void travelMemoryCrudIsScopedPerUser() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");
        MockHttpSession minsuSession = loginAndGetSession("minsu");

        Long planId = createTravelPlan(hanaSession, "Kyoto memory trip");
        Long memoryId = createMemoryRecord(hanaSession, planId, "Spot", "Fushimi Inari");

        uploadTravelMemoryMedia(hanaSession, memoryId);

        mockMvc.perform(delete("/api/travel/memories/{memoryId}", memoryId).with(csrf()).session(minsuSession))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/travel/plans/{planId}", planId).session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryRecords.length()").value(1))
                .andExpect(jsonPath("$.memoryRecords[0].title").value("Fushimi Inari"))
                .andExpect(jsonPath("$.mediaItems[0].recordType").value("MEMORY"))
                .andExpect(jsonPath("$.mediaItems[0].mediaType").value("PHOTO"));
    }

    @Test
    void communityMediaRequiresIssuedToken() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");
        MockHttpSession minsuSession = loginAndGetSession("minsu");

        Long planId = createTravelPlan(hanaSession, "Community media trip", "COMPLETED");
        Long memoryId = createMemoryRecord(hanaSession, planId, "Spot", "Shared spot", true);
        uploadTravelMemoryMedia(hanaSession, memoryId);

        MvcResult feedResult = mockMvc.perform(get("/api/travel/community-feed").session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].heroPhotoUrl").isNotEmpty())
                .andReturn();

        JsonNode feed = objectMapper.readTree(feedResult.getResponse().getContentAsString());
        String heroPhotoUrl = feed.get(0).get("heroPhotoUrl").asText();
        Matcher matcher = PUBLIC_MEDIA_URL_PATTERN.matcher(heroPhotoUrl);
        assertThat(matcher.matches()).isTrue();

        Long mediaId = Long.parseLong(matcher.group(1));
        String token = matcher.group(2);

        mockMvc.perform(get("/api/travel/public/media/{mediaId}/content", mediaId)
                        .session(minsuSession)
                        .param("token", "invalid-token"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/travel/public/media/{mediaId}/content", mediaId)
                        .session(minsuSession)
                        .param("token", token))
                .andExpect(status().isOk());
    }

    @Test
    void travelRoutePreservesPointOrderAndMetadataForEditing() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");

        Long planId = createTravelPlan(hanaSession, "Route metadata trip");
        Long startMemoryId = createMemoryRecord(hanaSession, planId, "Spot", "Start place");
        Long endMemoryId = createMemoryRecord(hanaSession, planId, "Spot", "End place");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeDate", "2026-05-05");
        payload.put("title", "Day 1 route");
        payload.put("transportMode", "WALK");
        payload.put("distanceKm", "3.450");
        payload.put("durationMinutes", 55);
        payload.put("stepCount", 4800);
        payload.put("sourceType", "MANUAL");
        payload.put("startPlaceName", "Start place");
        payload.put("endPlaceName", "End place");
        payload.put("lineColorHex", "#3182F6");
        payload.put("lineStyle", "SOLID");
        payload.put("memo", "Connected route");
        payload.put("points", List.of(
                Map.of(
                        "latitude", "34.9671400",
                        "longitude", "135.7726710",
                        "pointType", "MEMORY",
                        "linkedMemoryId", startMemoryId,
                        "label", "후시미 이나리"
                ),
                Map.of(
                        "latitude", "34.9685000",
                        "longitude", "135.7740000",
                        "pointType", "ROUTE",
                        "label", "경로 핀 1"
                ),
                Map.of(
                        "latitude", "34.9699000",
                        "longitude", "135.7763000",
                        "pointType", "MEMORY",
                        "linkedMemoryId", endMemoryId,
                        "label", "교토역"
                )
        ));

        mockMvc.perform(post("/api/travel/plans/{planId}/routes", planId)
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].pointType").value("MEMORY"))
                .andExpect(jsonPath("$.points[0].linkedMemoryId").value(startMemoryId))
                .andExpect(jsonPath("$.points[0].label").value("후시미 이나리"))
                .andExpect(jsonPath("$.points[1].pointType").value("ROUTE"))
                .andExpect(jsonPath("$.points[1].label").value("경로 핀 1"))
                .andExpect(jsonPath("$.points[2].pointType").value("MEMORY"))
                .andExpect(jsonPath("$.points[2].linkedMemoryId").value(endMemoryId));

        mockMvc.perform(get("/api/travel/plans/{planId}", planId).session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeSegments.length()").value(1))
                .andExpect(jsonPath("$.routeSegments[0].points.length()").value(3))
                .andExpect(jsonPath("$.routeSegments[0].points[0].pointType").value("MEMORY"))
                .andExpect(jsonPath("$.routeSegments[0].points[1].pointType").value("ROUTE"))
                .andExpect(jsonPath("$.routeSegments[0].points[2].pointType").value("MEMORY"))
                .andExpect(jsonPath("$.routeSegments[0].points[1].label").value("경로 핀 1"));
    }

    @Test
    void completedTravelCanBeSharedAsReadOnlyExhibit() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");
        MockHttpSession minsuSession = loginAndGetSession("minsu");

        Long planId = createTravelPlan(hanaSession, "Shared Kyoto", "COMPLETED");
        Long memoryId = createMemoryRecord(hanaSession, planId, "Spot", "Arashiyama");
        uploadTravelMemoryMedia(hanaSession, memoryId);

        Map<String, Object> sharePayload = new LinkedHashMap<>();
        sharePayload.put("loginId", "minsu");

        MvcResult shareResult = mockMvc.perform(post("/api/travel/plans/{planId}/shares", planId)
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sharePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.recipientLoginId").value("minsu"))
                .andReturn();

        Long shareId = readId(shareResult);

        mockMvc.perform(get("/api/travel/shared-exhibits").session(minsuSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(shareId))
                .andExpect(jsonPath("$[0].planId").value(planId))
                .andExpect(jsonPath("$[0].sharedByLoginId").value("hana"));

        MvcResult detailResult = mockMvc.perform(get("/api/travel/shared-exhibits/{shareId}", shareId).session(minsuSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shareId))
                .andExpect(jsonPath("$.travelPlan.id").value(planId))
                .andExpect(jsonPath("$.travelPlan.memoryRecords.length()").value(1))
                .andExpect(jsonPath("$.travelPlan.mediaItems.length()").value(1))
                .andReturn();

        JsonNode detailBody = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        JsonNode mediaNode = detailBody.path("travelPlan").path("mediaItems").path(0);
        Long mediaId = mediaNode.path("id").asLong();
        String contentUrl = mediaNode.path("contentUrl").asText();
        assertThat(contentUrl).isEqualTo("/api/travel/shared-exhibits/" + shareId + "/media/" + mediaId + "/content");

        mockMvc.perform(get("/api/travel/shared-exhibits/{shareId}/media/{mediaId}/content", shareId, mediaId).session(minsuSession))
                .andExpect(status().isOk());
    }

    @Test
    void plannedTravelCannotBeShared() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana");

        Long planId = createTravelPlan(hanaSession, "Planned trip", "PLANNED");

        Map<String, Object> sharePayload = new LinkedHashMap<>();
        sharePayload.put("loginId", "minsu");

        mockMvc.perform(post("/api/travel/plans/{planId}/shares", planId)
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sharePayload)))
                .andExpect(status().isBadRequest());
    }

    private MockHttpSession loginAndGetSession(String loginId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loginId", loginId);
        payload.put("password", "test1234");
        payload.put("secondaryPin", "hana".equals(loginId) ? "12345678" : "87654321");
        payload.put("rememberDevice", false);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private Long createTravelPlan(MockHttpSession session, String name) throws Exception {
        return createTravelPlan(session, name, "PLANNED");
    }

    private Long createTravelPlan(MockHttpSession session, String name, String status) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("destination", "Japan");
        payload.put("startDate", "2026-05-03");
        payload.put("endDate", "2026-05-07");
        payload.put("homeCurrency", "JPY");
        payload.put("headCount", 4);
        payload.put("status", status);
        payload.put("colorHex", "#FF6B6B");
        payload.put("memo", "Scope test trip");

        return readId(mockMvc.perform(post("/api/travel/plans")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Long createBudgetItem(
            MockHttpSession session,
            Long planId,
            String category,
            String title,
            String amount,
            String currencyCode,
            String exchangeRate
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("title", title);
        payload.put("amount", amount);
        payload.put("currencyCode", currencyCode);
        payload.put("exchangeRateToKrw", exchangeRate);
        payload.put("memo", "Planned budget");
        payload.put("displayOrder", 1);

        return readId(mockMvc.perform(post("/api/travel/plans/{planId}/budget-items", planId)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Long createExpenseRecord(
            MockHttpSession session,
            Long planId,
            String category,
            String title,
            String amount,
            String currencyCode,
            String exchangeRate
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expenseDate", "2026-05-04");
        payload.put("expenseTime", "18:40");
        payload.put("category", category);
        payload.put("title", title);
        payload.put("amount", amount);
        payload.put("currencyCode", currencyCode);
        payload.put("exchangeRateToKrw", exchangeRate);
        payload.put("country", "Japan");
        payload.put("region", "Osaka");
        payload.put("placeName", "Dotonbori");
        payload.put("latitude", "34.668736");
        payload.put("longitude", "135.501285");
        payload.put("memo", "Actual spend");

        return readId(mockMvc.perform(post("/api/travel/plans/{planId}/records", planId)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Long createMemoryRecord(
            MockHttpSession session,
            Long planId,
            String category,
            String title
    ) throws Exception {
        return createMemoryRecord(session, planId, category, title, false);
    }

    private Long createMemoryRecord(
            MockHttpSession session,
            Long planId,
            String category,
            String title,
            boolean sharedWithCommunity
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memoryDate", "2026-05-05");
        payload.put("memoryTime", "08:20");
        payload.put("category", category);
        payload.put("title", title);
        payload.put("country", "Japan");
        payload.put("region", "Kyoto");
        payload.put("placeName", "Fushimi Inari Shrine");
        payload.put("latitude", "34.967140");
        payload.put("longitude", "135.772671");
        payload.put("sharedWithCommunity", sharedWithCommunity);
        payload.put("memo", "Morning walk");

        return readId(mockMvc.perform(post("/api/travel/plans/{planId}/memories", planId)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void assertPresignFallsBackToServer(MockHttpSession session, Long recordId) throws Exception {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("originalFileName", "takoyaki.jpg");
        file.put("contentType", "image/jpeg");
        file.put("fileSize", 10);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mediaType", "PHOTO");
        payload.put("caption", "Takoyaki stand");
        payload.put("files", List.of(file));

        mockMvc.perform(post("/api/travel/records/{recordId}/media/presign", recordId)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadMode").value("SERVER"))
                .andExpect(jsonPath("$.uploads.length()").value(0));
    }

    private void uploadTravelMedia(MockHttpSession session, Long recordId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "takoyaki.jpg",
                "image/jpeg",
                TEST_JPEG_BYTES
        );

        mockMvc.perform(multipart("/api/travel/records/{recordId}/media", recordId)
                        .file(file)
                        .param("mediaType", "PHOTO")
                        .param("caption", "Takoyaki stand")
                        .with(csrf())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mediaType").value("PHOTO"))
                .andExpect(jsonPath("$[0].uploadedBy").isNotEmpty());
    }

    private void uploadTravelMemoryMedia(MockHttpSession session, Long memoryId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "fushimi.jpg",
                "image/jpeg",
                TEST_JPEG_BYTES
        );

        mockMvc.perform(multipart("/api/travel/memories/{memoryId}/media", memoryId)
                        .file(file)
                        .param("caption", "Shrine gate")
                        .with(csrf())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mediaType").value("PHOTO"))
                .andExpect(jsonPath("$[0].recordType").value("MEMORY"));
    }

    private Long readId(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }
}
