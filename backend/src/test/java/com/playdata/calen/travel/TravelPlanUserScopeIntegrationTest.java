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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
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
class TravelPlanUserScopeIntegrationTest {

    private static final byte[] TEST_JPEG_BYTES = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46,
            0x49, 0x46, 0x00, 0x01
    };

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

        mockMvc.perform(get("/api/travel/portfolio").session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeType").value("ALL"))
                .andExpect(jsonPath("$.includedPlanCount").value(1))
                .andExpect(jsonPath("$.mediaItemCount").value(1))
                .andExpect(jsonPath("$.records.length()").value(1));

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

    private MockHttpSession loginAndGetSession(String loginId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loginId", loginId);
        payload.put("password", "test1234");
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("destination", "Japan");
        payload.put("startDate", "2026-05-03");
        payload.put("endDate", "2026-05-07");
        payload.put("homeCurrency", "JPY");
        payload.put("headCount", 4);
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
