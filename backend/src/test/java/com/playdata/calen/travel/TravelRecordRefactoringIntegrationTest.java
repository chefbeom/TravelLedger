package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.application.port.out.TravelRecordStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:travel-record-refactoring-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelRecordRefactoringIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository media;
    @Autowired TravelDriveLinkService drive;
    @Autowired DriveItemRepository driveItems;
    @Autowired WriteTravelRecordUseCase writes;
    @Autowired TravelRecordStore store;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator clusters;
    @MockitoBean ExchangeRateService rates;
    private final LocalDate date = LocalDate.of(2026,10,15);
    private record Photo(long id, String path, String contentType) {}

    @BeforeEach void clearMocks() { clearInvocations(redis, storage, clusters, rates); }

    @Test void expenseJsonRoundingMidnightAndLegacyNullKindUpdateStayCompatible() throws Exception {
        long plan = plan(); clearMocks();
        byte[] raw = postRecord(plan, TravelRecordType.LEDGER);
        long id = json.readTree(raw).path("id").asLong();
        var expected = new TravelExpenseRecordResponse(id, plan, "Record trip", "#ABCDEF", TravelRecordType.LEDGER, date, LocalTime.MIDNIGHT,
                "Food", "Lunch", new BigDecimal("1.23"), "USD", new BigDecimal("3.000000"), new BigDecimal("3.70"),
                "Japan", null, "Cafe", new BigDecimal("37.1234568"), new BigDecimal("127.1234568"), "note");
        assertThat(raw).isEqualTo(json.writeValueAsBytes(expected));
        verifyInvalidations(2);
        verifyNoInteractions(clusters, storage, rates);
        var legacy = records.findById(id).orElseThrow(); legacy.setRecordType(null); legacy.setSharedWithCommunity(true); records.saveAndFlush(legacy);
        clearMocks();
        raw = mvc.perform(put("/api/travel/records/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(expenseBody()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(raw).isEqualTo(json.writeValueAsBytes(expected));
        assertThat(records.findById(id).orElseThrow().getSharedWithCommunity()).isFalse();
        verifyInvalidations(2);
        verifyNoInteractions(clusters, rates, storage);
    }

    @Test void memoryJsonPreservesNullTimeAndUpdatesSharingAndClusterState() throws Exception {
        long plan = plan(); clearMocks();
        byte[] raw = postRecord(plan, TravelRecordType.MEMORY);
        long id = json.readTree(raw).path("id").asLong();
        var expected = new TravelMemoryRecordResponse(id, plan, "Record trip", "#ABCDEF", TravelRecordType.MEMORY, date, null,
                "Spot", "Memory", "Japan", null, "Cafe", null, null, false, "note");
        assertThat(raw).isEqualTo(json.writeValueAsBytes(expected));
        var saved = records.findById(id).orElseThrow();
        assertThat(saved.getAmount()).isEqualByComparingTo("0"); assertThat(saved.getCurrencyCode()).isEqualTo("KRW");
        assertThat(saved.getExchangeRateToKrw()).isEqualByComparingTo("1");
        verify(clusters).markDirtyInCurrentTransaction(userId()); verify(clusters).dispatch(userId());
        clearMocks();
        var body = memoryBody(); body.put("sharedWithCommunity", true); body.put("memoryTime", "09:30:15");
        raw = mvc.perform(put("/api/travel/memories/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        expected = new TravelMemoryRecordResponse(id, plan, "Record trip", "#ABCDEF", TravelRecordType.MEMORY, date, LocalTime.of(9,30,15),
                "Spot", "Memory", "Japan", null, "Cafe", null, null, true, "note");
        assertThat(raw).isEqualTo(json.writeValueAsBytes(expected));
        assertThat(records.findById(id).orElseThrow().getSharedWithCommunity()).isTrue();
        verifyInvalidations(2); verify(clusters).dispatch(userId()); verifyNoInteractions(rates, storage);
    }

    @Test void missingForeignRateUsesExistingRateProviderAdapter() throws Exception {
        long plan = plan(); clearMocks();
        when(rates.getRequiredRateToKrw("USD")).thenReturn(new BigDecimal("3.1234567"));
        var body = expenseBody(); body.remove("exchangeRateToKrw");
        mvc.perform(post("/api/travel/plans/{id}/records", plan).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.exchangeRateToKrw").value(3.123457));
        verify(rates).getRequiredRateToKrw("USD"); verifyNoInteractions(clusters);
    }

    @Test void ownerKindAuthenticationAndCoordinateValidationArePreserved() throws Exception {
        long plan = plan();
        long expense = json.readTree(postRecord(plan, TravelRecordType.LEDGER)).path("id").asLong();
        long memory = json.readTree(postRecord(plan, TravelRecordType.MEMORY)).path("id").asLong(); clearMocks();
        mvc.perform(put("/api/travel/records/{id}", memory).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(expenseBody())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Travel ledger record not found."));
        mvc.perform(put("/api/travel/memories/{id}", expense).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(memoryBody())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Travel memory not found."));
        mvc.perform(delete("/api/travel/records/{id}", memory).with(as("hana")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/memories/{id}", expense).with(as("hana")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/records/{id}", expense).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/travel/plans/{id}/memories", plan).with(as("hana")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(memoryBody()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/travel/plans/{id}/memories", plan).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(memoryBody()))).andExpect(status().isUnauthorized());
        var invalid = memoryBody(); invalid.put("latitude", 91); invalid.put("longitude", 127);
        mvc.perform(put("/api/travel/memories/{id}", memory).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Latitude must be between -90 and 90."));
        invalid.remove("longitude");
        mvc.perform(put("/api/travel/memories/{id}", memory).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Latitude and longitude must be provided together."));
        assertThat(records.findById(memory).orElseThrow().getLatitude()).isNull();
        verifyNoInteractions(redis, clusters, rates, storage);
    }

    @ParameterizedTest @EnumSource(TravelRecordType.class)
    void failedDeleteKeepsRecordPhotoAndDriveLinkThenCommittedDeleteCleansOnlyThatRecord(TravelRecordType type) throws Exception {
        long plan = plan(), id = json.readTree(postRecord(plan, type)).path("id").asLong();
        long other = json.readTree(postRecord(plan, type)).path("id").asLong();
        Photo photo = photo(id), otherPhoto = photo(other); clearMocks();
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            deleteRecord(type, id); records.flush(); assertThat(records.existsById(id)).isFalse(); verifyNoInteractions(storage);
            throw new IllegalStateException("failure after SQL deletion");
        })).hasMessage("failure after SQL deletion");
        assertThat(records.existsById(id)).isTrue(); assertThat(media.existsById(photo.id())).isTrue();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(userId(), TravelDriveLinkService.SOURCE_TRAVEL_MEDIA, ""+photo.id())).isPresent();
        verifyNoInteractions(storage); verify(clusters, never()).dispatch(any()); clearMocks();
        transaction.executeWithoutResult(tx -> { deleteRecord(type, id); records.flush(); verifyNoInteractions(storage); });
        assertThat(records.existsById(id)).isFalse(); assertThat(media.existsById(photo.id())).isFalse();
        assertThat(records.existsById(other)).isTrue(); assertThat(media.existsById(otherPhoto.id())).isTrue();
        assertThat(plans.existsById(plan)).isTrue();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(userId(), TravelDriveLinkService.SOURCE_TRAVEL_MEDIA, ""+photo.id())).isEmpty();
        verify(storage).deleteImageWithThumbnailsQuietly(photo.path(), photo.contentType());
        verify(storage, never()).deleteImageWithThumbnailsQuietly(otherPhoto.path(), otherPhoto.contentType());
        verifyInvalidations(2);
        if (type == TravelRecordType.MEMORY) verify(clusters).dispatch(userId()); else verifyNoInteractions(clusters);
        clearMocks();
        mvc.perform(delete("/api/travel/"+(type == TravelRecordType.MEMORY ? "memories/" : "records/")+other).with(as("hana")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(records.existsById(other)).isFalse();
        verify(storage).deleteImageWithThumbnailsQuietly(otherPhoto.path(), otherPhoto.contentType());
        verifyInvalidations(2);
    }

    @Test void clusterDirtyFailureRollsBackMemoryDeletionBeforePhysicalCleanup() throws Exception {
        long plan = plan(), id = json.readTree(postRecord(plan, TravelRecordType.MEMORY)).path("id").asLong();
        Photo photo = photo(id); clearMocks();
        doThrow(new IllegalStateException("dirty failure")).when(clusters).markDirtyInCurrentTransaction(userId());
        assertThatThrownBy(() -> writes.deleteMemory(userId(), id)).hasMessage("dirty failure");
        assertThat(records.existsById(id)).isTrue(); assertThat(media.existsById(photo.id())).isTrue();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(userId(), TravelDriveLinkService.SOURCE_TRAVEL_MEDIA, ""+photo.id())).isPresent();
        verifyNoInteractions(storage); verify(clusters, never()).dispatch(any()); verifyInvalidations(1);
    }

    @Test void recordAdapterRejectsCallsWithoutUseCaseTransaction() {
        assertThatThrownBy(() -> store.ownedRecord(userId(), 1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void deleteRecord(TravelRecordType type, long id) {
        if (type == TravelRecordType.MEMORY) writes.deleteMemory(userId(), id); else writes.deleteExpense(userId(), id);
    }
    private Photo photo(long id) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var record = records.findById(id).orElseThrow(); var asset = new TravelMediaAsset(); asset.setRecord(record); asset.setPlan(record.getPlan());
            asset.setUploadedBy(record.getPlan().getOwner()); asset.setOriginalFileName("photo.jpg"); asset.setStoredFileName("photo.jpg");
            asset.setStoragePath("test-only/record-"+id+".jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(1L);
            media.saveAndFlush(asset); drive.linkMediaAsset(asset); return new Photo(asset.getId(), asset.getStoragePath(), asset.getContentType());
        });
    }
    private void verifyInvalidations(int times) {
        verify(redis, times(times)).delete(TravelSummaryCache.plansKey(userId()), TravelSummaryCache.portfolioKey(userId()), TravelSummaryCache.mapKey(userId()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }
    private long userId() { return users.findByLoginId("hana").orElseThrow().getId(); }
    private RequestPostProcessor as(String login) { return user(AppUserPrincipal.from(users.findByLoginId(login).orElseThrow())); }
    private long plan() throws Exception {
        return json.readTree(mvc.perform(post("/api/travel/plans").with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name","Record trip", "startDate","2026-10-10", "endDate","2026-10-15", "homeCurrency","KRW", "headCount",1,
                        "status","COMPLETED", "colorHex","#abcdef")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
    }
    private byte[] postRecord(long plan, TravelRecordType type) throws Exception {
        return mvc.perform(post("/api/travel/plans/{id}/"+(type == TravelRecordType.MEMORY ? "memories" : "records"), plan).with(as("hana")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(type == TravelRecordType.MEMORY ? memoryBody() : expenseBody())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private Map<String,Object> expenseBody() {
        Map<String,Object> body = new LinkedHashMap<>(Map.of("expenseDate",date.toString(), "category"," Food ", "title"," Lunch ", "amount",new BigDecimal("1.2345"),
                "currencyCode","usd", "exchangeRateToKrw",new BigDecimal("3"), "country"," Japan ", "region"," ", "placeName"," Cafe ", "memo"," note "));
        body.put("latitude",new BigDecimal("37.12345675")); body.put("longitude",new BigDecimal("127.12345675"));
        return body;
    }
    private Map<String,Object> memoryBody() {
        return new LinkedHashMap<>(Map.of("memoryDate",date.toString(), "category"," Spot ", "title"," Memory ", "country"," Japan ", "region"," ", "placeName"," Cafe ", "memo"," note "));
    }
}
