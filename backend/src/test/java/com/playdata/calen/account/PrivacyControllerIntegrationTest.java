package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:privacy-controller-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class PrivacyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private TravelShareGroupRepository groups;
    @Autowired private TravelShareGroupMemberRepository groupMembers;
    @Autowired private TravelPlanRepository plans;
    @Autowired private TravelPlanShareRepository planShares;
    @Autowired private TravelExpenseRecordRepository records;
    @Autowired private TravelMediaAssetRepository media;

    @Test
    void withdrawalPreviewRequiresAuthenticationCsrfAndAFreshPinAndDoesNotDelete() throws Exception {
        String correctPin = objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"));
        mockMvc.perform(post("/api/privacy/withdrawal/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(correctPin))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = login("hana", "test1234", "12345678");
        mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(correctPin))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "00000000"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(correctPin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.withdrawalAvailable").value(false))
                .andExpect(jsonPath("$.requiredFamilyTransfers").isArray())
                .andExpect(jsonPath("$.requiredShareGroupTransfers").isArray())
                .andExpect(jsonPath("$.requiredTravelPlanTransfers").isArray())
                .andExpect(jsonPath("$.foreignTravelMedia.planCount").isNumber())
                .andExpect(jsonPath("$.foreignTravelMedia.mediaCount").isNumber());
        login("hana", "test1234", "12345678");
    }

    @Test
    @Transactional
    void withdrawalPreviewCountsOwnMediaInOtherPlansWithoutRevealingTheirNames() throws Exception {
        AppUser owner = users.findByLoginId("hana").orElseThrow();
        AppUser other = users.findByLoginId("minsu").orElseThrow();
        TravelPlan ownPlan = plan(owner, "내 여행");
        TravelPlan otherPlan = plan(other, "공유되지 않은 다른 회원 여행");
        TravelPlan anotherPlan = plan(other, "다른 회원의 두 번째 여행");
        TravelExpenseRecord ownRecord = record(ownPlan), otherRecord = record(otherPlan), anotherRecord = record(anotherPlan);
        TravelMediaAsset ownPhoto = media(ownPlan, ownRecord, owner, TravelMediaType.PHOTO);
        TravelMediaAsset sharedPhoto = media(otherPlan, otherRecord, owner, TravelMediaType.PHOTO);
        TravelMediaAsset sharedReceipt = media(otherPlan, otherRecord, owner, TravelMediaType.RECEIPT);
        TravelMediaAsset secondPhoto = media(anotherPlan, anotherRecord, owner, TravelMediaType.PHOTO);
        TravelMediaAsset someoneElsesPhoto = media(otherPlan, otherRecord, other, TravelMediaType.PHOTO);

        MockHttpSession session = login("hana", "test1234", "12345678");
        MvcResult response = mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignTravelMedia.planCount").value(2))
                .andExpect(jsonPath("$.foreignTravelMedia.mediaCount").value(3)).andReturn();
        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("공유되지 않은 다른 회원 여행", "다른 회원의 두 번째 여행");
        assertThat(media.findById(ownPhoto.getId())).isPresent();
        assertThat(media.findById(sharedPhoto.getId())).isPresent();
        assertThat(media.findById(sharedReceipt.getId())).isPresent();
        assertThat(media.findById(secondPhoto.getId())).isPresent();
        assertThat(media.findById(someoneElsesPhoto.getId())).isPresent();
    }

    private TravelPlan plan(AppUser owner, String name) {
        TravelPlan plan = new TravelPlan(); plan.setOwner(owner); plan.setName(name);
        plan.setStartDate(LocalDate.now()); plan.setEndDate(LocalDate.now());
        return plans.saveAndFlush(plan);
    }

    private TravelExpenseRecord record(TravelPlan plan) {
        TravelExpenseRecord record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(LocalDate.now()); record.setCategory("장소"); record.setTitle("기록"); record.setAmount(BigDecimal.ZERO);
        return records.saveAndFlush(record);
    }

    private TravelMediaAsset media(TravelPlan plan, TravelExpenseRecord record, AppUser uploader, TravelMediaType type) {
        String file = UUID.randomUUID() + ".jpg";
        TravelMediaAsset asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(uploader);
        asset.setMediaType(type); asset.setOriginalFileName(file); asset.setStoredFileName(file);
        asset.setStoragePath(plan.getOwner().getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
        asset.setContentType("image/jpeg"); asset.setFileSize(123L);
        return media.saveAndFlush(asset);
    }

    @Test
    @Transactional
    void withdrawalPreviewShowsOnlyTheOwnersSharedTravelPlans() throws Exception {
        AppUser owner = users.findByLoginId("hana").orElseThrow();
        AppUser member = users.findByLoginId("minsu").orElseThrow();
        TravelPlan owned = new TravelPlan();
        owned.setOwner(owner); owned.setName("함께한 여행");
        owned.setStartDate(LocalDate.now()); owned.setEndDate(LocalDate.now());
        plans.saveAndFlush(owned);
        TravelPlanShare shared = new TravelPlanShare();
        shared.setPlan(owned); shared.setSharedBy(owner); shared.setRecipient(member); shared.setCreatedAt(LocalDateTime.now());
        planShares.saveAndFlush(shared);
        TravelPlan unrelated = new TravelPlan();
        unrelated.setOwner(member); unrelated.setName("다른 사람 여행");
        unrelated.setStartDate(LocalDate.now()); unrelated.setEndDate(LocalDate.now());
        plans.saveAndFlush(unrelated);
        TravelPlanShare incoming = new TravelPlanShare();
        incoming.setPlan(unrelated); incoming.setSharedBy(member); incoming.setRecipient(owner); incoming.setCreatedAt(LocalDateTime.now());
        planShares.saveAndFlush(incoming);

        MockHttpSession session = login("hana", "test1234", "12345678");
        MvcResult response = mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode choices = objectMapper.readTree(response.getResponse().getContentAsString()).path("requiredTravelPlanTransfers");
        assertThat(StreamSupport.stream(choices.spliterator(), false).map(item -> item.path("planId").asLong()))
                .contains(owned.getId()).doesNotContain(unrelated.getId());
        JsonNode choice = StreamSupport.stream(choices.spliterator(), false)
                .filter(item -> item.path("planId").asLong() == owned.getId()).findFirst().orElseThrow();
        assertThat(choice.path("planName").asText()).isEqualTo("함께한 여행");
        assertThat(choice.path("members").get(0).path("userId").asLong()).isEqualTo(member.getId());
        assertThat(choice.path("members").get(0).path("eligible").asBoolean()).isTrue();
        assertThat(planShares.findById(shared.getId())).isPresent();
        assertThat(plans.findById(owned.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test
    @Transactional
    void withdrawalPreviewShowsPublicOnlyTravelWithoutInventingARecipient() throws Exception {
        AppUser owner = users.findByLoginId("hana").orElseThrow();
        TravelPlan plan = new TravelPlan();
        plan.setOwner(owner); plan.setName("공개 여행"); plan.setPublicShared(true);
        plan.setStartDate(LocalDate.now()); plan.setEndDate(LocalDate.now());
        plans.saveAndFlush(plan);
        MockHttpSession session = login("hana", "test1234", "12345678");
        MvcResult response = mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode choices = objectMapper.readTree(response.getResponse().getContentAsString()).path("requiredTravelPlanTransfers");
        JsonNode choice = StreamSupport.stream(choices.spliterator(), false)
                .filter(item -> item.path("planId").asLong() == plan.getId()).findFirst().orElseThrow();
        assertThat(choice.path("publiclyShared").asBoolean()).isTrue();
        assertThat(choice.path("members").isArray()).isTrue();
        assertThat(choice.path("members").size()).isZero();
    }

    @Test
    @Transactional
    void withdrawalPreviewShowsOnlyOwnedSharedGroupsWithoutChangingThem() throws Exception {
        AppUser owner = users.findByLoginId("hana").orElseThrow();
        AppUser member = users.findByLoginId("minsu").orElseThrow();
        TravelShareGroup owned = group(owner, "가족 공유");
        TravelShareGroup unrelated = group(member, "다른 사람 그룹");
        TravelShareGroupMember membership = new TravelShareGroupMember();
        membership.setGroup(owned);
        membership.setMember(member);
        membership.setCreatedAt(LocalDateTime.now());
        groupMembers.saveAndFlush(membership);
        MockHttpSession session = login("hana", "test1234", "12345678");
        MvcResult response = mockMvc.perform(post("/api/privacy/withdrawal/preview").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode choices = objectMapper.readTree(response.getResponse().getContentAsString()).path("requiredShareGroupTransfers");
        assertThat(StreamSupport.stream(choices.spliterator(), false).map(item -> item.path("groupId").asLong()))
                .contains(owned.getId()).doesNotContain(unrelated.getId());
        JsonNode choice = StreamSupport.stream(choices.spliterator(), false)
                .filter(item -> item.path("groupId").asLong() == owned.getId()).findFirst().orElseThrow();
        assertThat(choice.path("groupName").asText()).isEqualTo("가족 공유");
        assertThat(choice.path("members").get(0).path("userId").asLong()).isEqualTo(member.getId());
        assertThat(choice.path("members").get(0).path("eligible").asBoolean()).isTrue();
        assertThat(groups.findById(owned.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(groupMembers.existsByGroupIdAndMemberId(owned.getId(), member.getId())).isTrue();
    }

    private TravelShareGroup group(AppUser owner, String name) {
        TravelShareGroup value = new TravelShareGroup();
        value.setOwner(owner);
        value.setName(name);
        value.setCreatedAt(LocalDateTime.now());
        value.setUpdatedAt(LocalDateTime.now());
        return groups.saveAndFlush(value);
    }

    @Test
    void aiAnalysisHistoryDeletionRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(delete("/api/privacy/ai-analysis-history").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/privacy/ai-analysis-history").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/privacy/ai-analysis-history")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").isNumber())
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").value(0))
                .andExpect(jsonPath("$.travelPublicMediaSharesRevoked").value(0))
                .andExpect(jsonPath("$.photoLocationMetadataRemoved").value(0))
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void publicDownloadLinkRevocationRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(delete("/api/privacy/public-download-links").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/privacy/public-download-links").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/privacy/public-download-links")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").value(0))
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").isNumber())
                .andExpect(jsonPath("$.travelPublicMediaSharesRevoked").value(0))
                .andExpect(jsonPath("$.photoLocationMetadataRemoved").value(0))
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void travelPublicMediaShareRevocationRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(delete("/api/privacy/travel-public-media-shares").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/privacy/travel-public-media-shares").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/privacy/travel-public-media-shares")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").value(0))
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").value(0))
                .andExpect(jsonPath("$.travelPublicMediaSharesRevoked").isNumber())
                .andExpect(jsonPath("$.photoLocationMetadataRemoved").value(0))
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void photoLocationMetadataRemovalRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(delete("/api/privacy/photo-location-metadata").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/privacy/photo-location-metadata").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/privacy/photo-location-metadata")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").value(0))
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").value(0))
                .andExpect(jsonPath("$.travelPublicMediaSharesRevoked").value(0))
                .andExpect(jsonPath("$.photoLocationMetadataRemoved").isNumber())
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void sensitiveCleanupRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(post("/api/privacy/cleanup").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/privacy/cleanup").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/privacy/cleanup")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").isNumber())
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").isNumber())
                .andExpect(jsonPath("$.travelPublicMediaSharesRevoked").isNumber())
                .andExpect(jsonPath("$.photoLocationMetadataRemoved").isNumber())
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");
        AppUserPrincipal hanaPrincipal = new AppUserPrincipal(2L, "hana", "hana", "password", AppUserRole.USER, true);
        String payload = objectMapper.writeValueAsString(Map.of(
                "from", "2026-06-01",
                "to", "2026-06-30"
        ));

        mockMvc.perform(post("/api/privacy/data-export")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/privacy/data-export")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/privacy/data-export")
                        .with(user(hanaPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/privacy/data-export")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("travelledger-user-data")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/zip")));
    }

    private MockHttpSession login(String loginId, String password, String secondaryPin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId,
                                "password", password,
                                "secondaryPin", secondaryPin,
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
