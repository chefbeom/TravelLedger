package com.playdata.calen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:calen-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class LedgerEntryUserScopeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CategoryGroupRepository categoryGroupRepository;

    @Autowired
    private CategoryDetailRepository categoryDetailRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Test
    void entryCrudIsScopedPerUser() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        MockHttpSession minsuSession = loginAndGetSession("minsu", false);

        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();

        LedgerEntry hanaEntry = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        hana.getId(),
                        LocalDate.now().minusYears(1),
                        LocalDate.now().plusDays(1)
                ).stream()
                .findFirst()
                .orElseThrow();

        mockMvc.perform(delete("/api/entries/{id}", hanaEntry.getId()).with(csrf()).session(minsuSession))
                .andExpect(status().isNotFound());

        assertThat(ledgerEntryRepository.findById(hanaEntry.getId())).isPresent();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryDate", hanaEntry.getEntryDate().toString());
        payload.put("title", "Updated entry");
        payload.put("memo", "Updated by owner");
        payload.put("amount", hanaEntry.getAmount());
        payload.put("entryType", hanaEntry.getEntryType().name());
        payload.put("categoryGroupId", hanaEntry.getCategoryGroup().getId());
        payload.put("categoryDetailId", hanaEntry.getCategoryDetail() != null ? hanaEntry.getCategoryDetail().getId() : null);
        payload.put("paymentMethodId", hanaEntry.getPaymentMethod().getId());

        mockMvc.perform(put("/api/entries/{id}", hanaEntry.getId())
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated entry"))
                .andExpect(jsonPath("$.memo").value("Updated by owner"));

        mockMvc.perform(delete("/api/entries/{id}", hanaEntry.getId()).with(csrf()).session(hanaSession))
                .andExpect(status().isOk());

        assertThat(ledgerEntryRepository.findById(hanaEntry.getId())).isPresent();
        assertThat(ledgerEntryRepository.findById(hanaEntry.getId()).orElseThrow().getDeletedAt()).isNotNull();

        mockMvc.perform(get("/api/entries/trash").session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(hanaEntry.getId()));

        mockMvc.perform(post("/api/entries/{id}/restore", hanaEntry.getId()).with(csrf()).session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hanaEntry.getId()));

        assertThat(ledgerEntryRepository.findById(hanaEntry.getId()).orElseThrow().getDeletedAt()).isNull();

        mockMvc.perform(delete("/api/entries/{id}", hanaEntry.getId()).with(csrf()).session(hanaSession))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/entries/trash").with(csrf()).session(hanaSession))
                .andExpect(status().isOk());

        assertThat(ledgerEntryRepository.findById(hanaEntry.getId())).isEmpty();
    }

    @Test
    void rememberMeRestoresUserWithoutSession() throws Exception {
        Cookie rememberMeCookie = loginAndGetRememberMeCookie("hana");

        assertThat(rememberMeCookie).isNotNull();
        assertThat(rememberMeCookie.getValue()).isNotBlank();

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("hana"))
                .andExpect(jsonPath("$.displayName").isNotEmpty());
    }

    @Test
    void csvExportUsesVerifiedSecondaryPinFromSession() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", null);
        payload.put("to", null);

        MvcResult result = mockMvc.perform(post("/api/entries/export/csv")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ledger-all.csv.zip")))
                .andReturn();

        Path zipPath = Files.createTempFile("ledger-export-", ".zip");
        try {
            Files.write(zipPath, result.getResponse().getContentAsByteArray());

            try (ZipFile zipFile = new ZipFile(zipPath.toFile(), "12345678".toCharArray())) {
                assertThat(zipFile.isValidZipFile()).isTrue();
                assertThat(zipFile.isEncrypted()).isTrue();

                FileHeader fileHeader = zipFile.getFileHeader("ledger-all.csv");
                assertThat(fileHeader).isNotNull();
                String csvText = new String(zipFile.getInputStream(fileHeader).readAllBytes(), StandardCharsets.UTF_8);
                assertThat(csvText).contains("거래일");
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    void incomeEntriesUseDashPaymentMethod() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();

        LedgerEntry incomeTemplate = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        hana.getId(),
                        LocalDate.now().minusYears(5),
                        LocalDate.now().plusDays(1)
                ).stream()
                .filter(entry -> entry.getEntryType().name().equals("INCOME"))
                .findFirst()
                .orElseThrow();

        LedgerEntry paymentTemplate = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        hana.getId(),
                        LocalDate.now().minusYears(5),
                        LocalDate.now().plusDays(1)
                ).stream()
                .findFirst()
                .orElseThrow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryDate", incomeTemplate.getEntryDate().toString());
        payload.put("title", "income payment placeholder");
        payload.put("memo", "test");
        payload.put("amount", incomeTemplate.getAmount());
        payload.put("entryType", incomeTemplate.getEntryType().name());
        payload.put("categoryGroupId", incomeTemplate.getCategoryGroup().getId());
        payload.put("categoryDetailId", incomeTemplate.getCategoryDetail() != null ? incomeTemplate.getCategoryDetail().getId() : null);
        payload.put("paymentMethodId", paymentTemplate.getPaymentMethod().getId());

        MvcResult result = mockMvc.perform(post("/api/entries")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethodName").value("-"))
                .andReturn();

        Long createdEntryId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        LedgerEntry createdEntry = ledgerEntryRepository.findById(createdEntryId).orElseThrow();
        Long storedPaymentMethodId = createdEntry.getPaymentMethod().getId();
        assertThat(paymentMethodRepository.findById(storedPaymentMethodId).orElseThrow().getName()).isEqualTo("-");
    }

    @Test
    @Transactional
    void excelImportCommitNormalizesLongClassificationNames() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();
        LocalDate importDate = LocalDate.of(2040, 1, 2);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("selected", true);
        row.put("sourceSheetName", "long-values");
        row.put("entryDate", importDate.toString());
        row.put("entryTime", null);
        row.put("title", "long import row");
        row.put("memo", "created by import test");
        row.put("amount", "1234");
        row.put("entryType", "EXPENSE");
        row.put("paymentMethodName", "payment-method-name-".repeat(8));
        row.put("categoryGroupName", "category-group-name-".repeat(8));
        row.put("categoryDetailName", "category-detail-name-".repeat(8));
        row.put("sourceRowNumber", 7);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", List.of(row));

        mockMvc.perform(post("/api/entries/imports/excel/commit")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1));

        LedgerEntry imported = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        hana.getId(),
                        importDate,
                        importDate
                ).stream()
                .filter(entry -> entry.getTitle().equals("long import row"))
                .findFirst()
                .orElseThrow();

        assertThat(imported.getPaymentMethod().getName()).hasSizeLessThanOrEqualTo(80);
        assertThat(imported.getCategoryGroup().getName()).hasSizeLessThanOrEqualTo(80);
        assertThat(imported.getCategoryDetail()).isNotNull();
        assertThat(imported.getCategoryDetail().getName()).hasSizeLessThanOrEqualTo(80);
    }

    @Test
    @Transactional
    void excelImportCommitReusesFirstDuplicatePaymentAndCategoryName() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();
        LocalDate importDate = LocalDate.of(2040, 2, 4);

        CategoryGroup firstFoodGroup = new CategoryGroup();
        firstFoodGroup.setOwner(hana);
        firstFoodGroup.setName("중복식비-2040");
        firstFoodGroup.setEntryType(EntryType.EXPENSE);
        firstFoodGroup.setDisplayOrder(0);
        firstFoodGroup.setActive(true);
        categoryGroupRepository.save(firstFoodGroup);

        CategoryGroup duplicateFoodGroup = new CategoryGroup();
        duplicateFoodGroup.setOwner(hana);
        duplicateFoodGroup.setName("중복식비-2040");
        duplicateFoodGroup.setEntryType(EntryType.EXPENSE);
        duplicateFoodGroup.setDisplayOrder(1);
        duplicateFoodGroup.setActive(true);
        categoryGroupRepository.save(duplicateFoodGroup);

        CategoryDetail firstLunchDetail = new CategoryDetail();
        firstLunchDetail.setGroup(firstFoodGroup);
        firstLunchDetail.setName("중복점심-2040");
        firstLunchDetail.setDisplayOrder(0);
        firstLunchDetail.setActive(true);
        categoryDetailRepository.save(firstLunchDetail);

        CategoryDetail duplicateLunchDetail = new CategoryDetail();
        duplicateLunchDetail.setGroup(firstFoodGroup);
        duplicateLunchDetail.setName("중복점심-2040");
        duplicateLunchDetail.setDisplayOrder(1);
        duplicateLunchDetail.setActive(true);
        categoryDetailRepository.save(duplicateLunchDetail);

        PaymentMethod firstLotteCard = new PaymentMethod();
        firstLotteCard.setOwner(hana);
        firstLotteCard.setName("중복롯데카드-2040");
        firstLotteCard.setKind(PaymentMethodKind.CARD);
        firstLotteCard.setDisplayOrder(0);
        firstLotteCard.setActive(true);
        paymentMethodRepository.save(firstLotteCard);

        PaymentMethod duplicateLotteCard = new PaymentMethod();
        duplicateLotteCard.setOwner(hana);
        duplicateLotteCard.setName("중복롯데카드-2040");
        duplicateLotteCard.setKind(PaymentMethodKind.CARD);
        duplicateLotteCard.setDisplayOrder(1);
        duplicateLotteCard.setActive(true);
        paymentMethodRepository.save(duplicateLotteCard);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("selected", true);
        row.put("sourceSheetName", "6월");
        row.put("entryDate", importDate.toString());
        row.put("entryTime", null);
        row.put("title", "플래에서(머리115000+헤어제품15000) 현대아울렛에서 미선이랑 점심식사");
        row.put("memo", null);
        row.put("amount", "20000");
        row.put("entryType", "EXPENSE");
        row.put("paymentMethodName", "중복롯데카드-2040");
        row.put("categoryGroupName", "중복식비-2040");
        row.put("categoryDetailName", "중복점심-2040");
        row.put("sourceRowNumber", 17);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", List.of(row));

        mockMvc.perform(post("/api/entries/imports/excel/commit")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1));

        LedgerEntry imported = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        hana.getId(),
                        importDate,
                        importDate
                ).stream()
                .filter(entry -> entry.getTitle().startsWith("플래에서"))
                .findFirst()
                .orElseThrow();

        assertThat(imported.getCategoryGroup().getId()).isEqualTo(firstFoodGroup.getId());
        assertThat(imported.getCategoryDetail()).isNotNull();
        assertThat(imported.getCategoryDetail().getId()).isEqualTo(firstLunchDetail.getId());
        assertThat(imported.getPaymentMethod().getId()).isEqualTo(firstLotteCard.getId());
    }

    @Test
    @Transactional
    void bulkUpdateEntriesChangesOnlySelectedSearchRows() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();
        LocalDate entryDate = LocalDate.of(2041, 4, 9);

        PaymentMethod originalPayment = savePaymentMethod(hana, "bulk-original-card-2041", true);
        PaymentMethod targetPayment = savePaymentMethod(hana, "bulk-target-card-2041", true);
        CategoryGroup originalGroup = saveCategoryGroup(hana, "bulk-original-group-2041", true);
        CategoryGroup targetGroup = saveCategoryGroup(hana, "bulk-target-group-2041", true);
        CategoryDetail originalDetail = saveCategoryDetail(originalGroup, "bulk-original-detail-2041", true);
        CategoryDetail targetDetail = saveCategoryDetail(targetGroup, "bulk-target-detail-2041", true);

        LedgerEntry firstSelected = saveLedgerEntry(hana, entryDate, "bulk selected one", originalPayment, originalGroup, originalDetail);
        LedgerEntry secondSelected = saveLedgerEntry(hana, entryDate, "bulk selected two", originalPayment, originalGroup, originalDetail);
        LedgerEntry notSelected = saveLedgerEntry(hana, entryDate, "bulk not selected", originalPayment, originalGroup, originalDetail);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryIds", List.of(firstSelected.getId(), secondSelected.getId()));
        payload.put("categoryGroupId", targetGroup.getId());
        payload.put("categoryDetailId", targetDetail.getId());
        payload.put("paymentMethodId", targetPayment.getId());

        mockMvc.perform(patch("/api/entries/bulk")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));

        LedgerEntry updatedFirst = ledgerEntryRepository.findById(firstSelected.getId()).orElseThrow();
        LedgerEntry updatedSecond = ledgerEntryRepository.findById(secondSelected.getId()).orElseThrow();
        LedgerEntry untouched = ledgerEntryRepository.findById(notSelected.getId()).orElseThrow();

        assertThat(updatedFirst.getCategoryGroup().getId()).isEqualTo(targetGroup.getId());
        assertThat(updatedFirst.getCategoryDetail().getId()).isEqualTo(targetDetail.getId());
        assertThat(updatedFirst.getPaymentMethod().getId()).isEqualTo(targetPayment.getId());
        assertThat(updatedSecond.getCategoryGroup().getId()).isEqualTo(targetGroup.getId());
        assertThat(updatedSecond.getCategoryDetail().getId()).isEqualTo(targetDetail.getId());
        assertThat(updatedSecond.getPaymentMethod().getId()).isEqualTo(targetPayment.getId());
        assertThat(untouched.getCategoryGroup().getId()).isEqualTo(originalGroup.getId());
        assertThat(untouched.getCategoryDetail().getId()).isEqualTo(originalDetail.getId());
        assertThat(untouched.getPaymentMethod().getId()).isEqualTo(originalPayment.getId());
    }

    @Test
    @Transactional
    void entryChangeHistoryCanRestorePreviousSearchEdit() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();
        LocalDate entryDate = LocalDate.of(2041, 5, 10);

        PaymentMethod originalPayment = savePaymentMethod(hana, "history-original-card-2041", true);
        PaymentMethod updatedPayment = savePaymentMethod(hana, "history-updated-card-2041", true);
        CategoryGroup originalGroup = saveCategoryGroup(hana, "history-original-group-2041", true);
        CategoryGroup updatedGroup = saveCategoryGroup(hana, "history-updated-group-2041", true);
        CategoryDetail originalDetail = saveCategoryDetail(originalGroup, "history-original-detail-2041", true);
        CategoryDetail updatedDetail = saveCategoryDetail(updatedGroup, "history-updated-detail-2041", true);

        LedgerEntry entry = saveLedgerEntry(hana, entryDate, "history original title", originalPayment, originalGroup, originalDetail);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryDate", entryDate.plusDays(1).toString());
        payload.put("entryTime", "13:20");
        payload.put("title", "history updated title");
        payload.put("memo", "history updated memo");
        payload.put("amount", "2500");
        payload.put("entryType", "EXPENSE");
        payload.put("categoryGroupId", updatedGroup.getId());
        payload.put("categoryDetailId", updatedDetail.getId());
        payload.put("paymentMethodId", updatedPayment.getId());

        mockMvc.perform(put("/api/entries/{id}", entry.getId())
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("history updated title"));

        MvcResult historyResult = mockMvc.perform(get("/api/entries/history")
                        .session(hanaSession)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.content[0].entryCount").value(1))
                .andReturn();

        Long historyId = objectMapper.readTree(historyResult.getResponse().getContentAsString())
                .get("content")
                .get(0)
                .get("id")
                .asLong();

        mockMvc.perform(get("/api/entries/history/{historyId}", historyId)
                        .session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].entryId").value(entry.getId()))
                .andExpect(jsonPath("$.changes[0].fields[*].field", org.hamcrest.Matchers.hasItems("제목", "금액", "대분류", "결제수단")));

        mockMvc.perform(post("/api/entries/history/{historyId}/restore", historyId)
                        .with(csrf())
                        .session(hanaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESTORE"));

        LedgerEntry restored = ledgerEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(restored.getEntryDate()).isEqualTo(entryDate);
        assertThat(restored.getTitle()).isEqualTo("history original title");
        assertThat(restored.getCategoryGroup().getId()).isEqualTo(originalGroup.getId());
        assertThat(restored.getCategoryDetail().getId()).isEqualTo(originalDetail.getId());
        assertThat(restored.getPaymentMethod().getId()).isEqualTo(originalPayment.getId());
    }

    @Test
    @Transactional
    void searchEntriesCanFilterOtherInactiveClassifications() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);
        AppUser hana = appUserRepository.findByLoginId("hana").orElseThrow();
        LocalDate searchDate = LocalDate.of(2041, 3, 15);

        PaymentMethod activePayment = savePaymentMethod(hana, "active-card-2041", true);
        PaymentMethod inactivePayment = savePaymentMethod(hana, "inactive-card-2041", false);
        CategoryGroup activeGroup = saveCategoryGroup(hana, "active-group-2041", true);
        CategoryGroup inactiveGroup = saveCategoryGroup(hana, "inactive-group-2041", false);
        CategoryDetail activeDetail = saveCategoryDetail(activeGroup, "active-detail-2041", true);
        CategoryDetail inactiveDetail = saveCategoryDetail(activeGroup, "inactive-detail-2041", false);

        saveLedgerEntry(hana, searchDate, "normal active row", activePayment, activeGroup, activeDetail);
        saveLedgerEntry(hana, searchDate, "other payment row", inactivePayment, activeGroup, activeDetail);
        saveLedgerEntry(hana, searchDate, "other group row", activePayment, inactiveGroup, null);
        saveLedgerEntry(hana, searchDate, "other detail row", activePayment, activeGroup, inactiveDetail);
        saveLedgerEntry(hana, searchDate, "empty detail row", activePayment, activeGroup, null);

        mockMvc.perform(get("/api/entries/search")
                        .session(hanaSession)
                        .param("from", searchDate.toString())
                        .param("to", searchDate.toString())
                        .param("paymentMethodOther", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.count").value(1))
                .andExpect(jsonPath("$.content[0].title").value("other payment row"));

        mockMvc.perform(get("/api/entries/search")
                        .session(hanaSession)
                        .param("from", searchDate.toString())
                        .param("to", searchDate.toString())
                        .param("categoryGroupOther", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.count").value(1))
                .andExpect(jsonPath("$.content[0].title").value("other group row"));

        mockMvc.perform(get("/api/entries/search")
                        .session(hanaSession)
                        .param("from", searchDate.toString())
                        .param("to", searchDate.toString())
                        .param("categoryDetailOther", "true")
                        .param("sortBy", "DATE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.count").value(3))
                .andExpect(jsonPath("$.content[*].title", org.hamcrest.Matchers.containsInAnyOrder(
                        "other group row",
                        "other detail row",
                        "empty detail row"
                )));
    }

    @Test
    @Transactional
    void managementRejectsDuplicateClassificationNames() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", false);

        Map<String, Object> groupPayload = new LinkedHashMap<>();
        groupPayload.put("name", "duplicate-management-group-2042");
        groupPayload.put("entryType", "EXPENSE");
        groupPayload.put("displayOrder", 0);

        MvcResult groupResult = mockMvc.perform(post("/api/categories/groups")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupPayload)))
                .andExpect(status().isOk())
                .andReturn();

        Long groupId = objectMapper.readTree(groupResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/categories/groups")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 있는 분류입니다."));

        Map<String, Object> detailPayload = new LinkedHashMap<>();
        detailPayload.put("groupId", groupId);
        detailPayload.put("name", "duplicate-management-detail-2042");
        detailPayload.put("displayOrder", 0);

        mockMvc.perform(post("/api/categories/details")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(detailPayload)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/categories/details")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(detailPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 있는 분류입니다."));

        Map<String, Object> paymentPayload = new LinkedHashMap<>();
        paymentPayload.put("name", "duplicate-management-payment-2042");
        paymentPayload.put("kind", "CARD");
        paymentPayload.put("displayOrder", 0);

        mockMvc.perform(post("/api/payment-methods")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentPayload)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payment-methods")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 있는 결제수단입니다."));
    }

    private PaymentMethod savePaymentMethod(AppUser owner, String name, boolean active) {
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(name);
        paymentMethod.setKind(PaymentMethodKind.CARD);
        paymentMethod.setDisplayOrder(0);
        paymentMethod.setActive(active);
        return paymentMethodRepository.save(paymentMethod);
    }

    private CategoryGroup saveCategoryGroup(AppUser owner, String name, boolean active) {
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(name);
        group.setEntryType(EntryType.EXPENSE);
        group.setDisplayOrder(0);
        group.setActive(active);
        return categoryGroupRepository.save(group);
    }

    private CategoryDetail saveCategoryDetail(CategoryGroup group, String name, boolean active) {
        CategoryDetail detail = new CategoryDetail();
        detail.setGroup(group);
        detail.setName(name);
        detail.setDisplayOrder(0);
        detail.setActive(active);
        return categoryDetailRepository.save(detail);
    }

    private LedgerEntry saveLedgerEntry(
            AppUser owner,
            LocalDate entryDate,
            String title,
            PaymentMethod paymentMethod,
            CategoryGroup categoryGroup,
            CategoryDetail categoryDetail
    ) {
        LedgerEntry entry = new LedgerEntry();
        entry.setOwner(owner);
        entry.setEntryDate(entryDate);
        entry.setTitle(title);
        entry.setAmount(new java.math.BigDecimal("1000"));
        entry.setEntryType(EntryType.EXPENSE);
        entry.setPaymentMethod(paymentMethod);
        entry.setCategoryGroup(categoryGroup);
        entry.setCategoryDetail(categoryDetail);
        return ledgerEntryRepository.save(entry);
    }

    private MockHttpSession loginAndGetSession(String loginId, boolean rememberDevice) throws Exception {
        MvcResult result = login(loginId, rememberDevice);
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private Cookie loginAndGetRememberMeCookie(String loginId) throws Exception {
        MvcResult result = login(loginId, true);
        return result.getResponse().getCookie("CALEN_REMEMBER_ME");
    }

    private MvcResult login(String loginId, boolean rememberDevice) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loginId", loginId);
        payload.put("password", "test1234");
        payload.put("secondaryPin", "hana".equals(loginId) ? "12345678" : "87654321");
        payload.put("rememberDevice", rememberDevice);

        return mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(loginId))
                .andReturn();
    }
}
