package com.playdata.calen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
