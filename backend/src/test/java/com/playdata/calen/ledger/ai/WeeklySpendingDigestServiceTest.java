package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.UserLayoutSetting;
import com.playdata.calen.account.repository.UserLayoutSettingRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDetailResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklySpendingDigestServiceTest {

    private static final Long USER_ID = 41L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    @Mock
    private UserLayoutSettingRepository userLayoutSettingRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LedgerAiAnalysisHistoryRepository historyRepository;

    @Mock
    private LedgerAiAnalysisService analysisService;

    @Mock
    private AppUserService appUserService;

    private WeeklySpendingDigestService service;

    @BeforeEach
    void setUp() {
        service = new WeeklySpendingDigestService(
                userLayoutSettingRepository,
                ledgerEntryRepository,
                historyRepository,
                analysisService,
                appUserService,
                new ObjectMapper()
        );
    }

    @Test
    void weeklyRangesUseTheLastCompleteMondayThroughSundayAndTheWeekBefore() {
        WeeklySpendingDigestService.WeeklyDateRanges ranges = WeeklySpendingDigestService.weekRanges(MONDAY);

        assertThat(ranges.from()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(ranges.to()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(ranges.compareFrom()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(ranges.compareTo()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    void scheduledRunRequiresVisibleAutomaticPaletteInTheActivePresetAndPriorWeekEntries() {
        when(analysisService.getStatus()).thenReturn(aiStatus());
        when(userLayoutSettingRepository.findActiveSettingsByLayoutScope("household")).thenReturn(List.of(
                setting(51L, 3, 3, true, true),
                setting(52L, 3, 3, false, true),
                setting(53L, 3, 3, true, false),
                setting(54L, 3, 2, true, true)
        ));
        when(historyRepository.findTopByOwnerIdAndModeAndPeriodTypeAndComparisonPresetAndFromDateAndToDateAndCompareFromDateAndCompareToDateOrderByCreatedAtDescIdDesc(
                51L,
                LedgerAiAnalysisMode.COMPARISON,
                LedgerAiAnalysisPeriod.WEEK,
                LedgerAiComparisonPreset.WEEKLY_DIGEST,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 13)
        )).thenReturn(Optional.empty());
        when(ledgerEntryRepository.countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                51L,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20)
        )).thenReturn(2L);

        service.scheduleWeeklyDigest(MONDAY);

        ArgumentCaptor<LedgerAiAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(LedgerAiAnalysisRequest.class);
        verify(analysisService).startAnalyze(eq(51L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().mode()).isEqualTo(LedgerAiAnalysisMode.COMPARISON);
        assertThat(requestCaptor.getValue().periodType()).isEqualTo(LedgerAiAnalysisPeriod.WEEK);
        assertThat(requestCaptor.getValue().comparisonPreset()).isEqualTo(LedgerAiComparisonPreset.WEEKLY_DIGEST);
        assertThat(requestCaptor.getValue().anchorDate()).isEqualTo(MONDAY);
        assertThat(requestCaptor.getValue().focusPrompt()).contains("concise Korean weekly spending brief");
        verify(ledgerEntryRepository).countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                51L,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20)
        );
    }

    @Test
    void manualRunRejectsWeeksWithoutLedgerEntries() {
        stubUser();
        stubNoDigest();
        when(ledgerEntryRepository.countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                USER_ID,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20)
        )).thenReturn(0L);

        assertThatThrownBy(() -> service.requestNow(USER_ID, MONDAY))
                .isInstanceOf(BadRequestException.class);

        verify(analysisService, never()).startAnalyze(any(), any());
    }

    @Test
    void manualRunReusesAnExistingProcessingDigest() {
        stubUser();
        LedgerAiAnalysisHistory processing = new LedgerAiAnalysisHistory();
        processing.setId(901L);
        processing.setStatus(LedgerAiAnalysisStatus.PROCESSING);
        when(historyRepository.findTopByOwnerIdAndModeAndPeriodTypeAndComparisonPresetAndFromDateAndToDateAndCompareFromDateAndCompareToDateOrderByCreatedAtDescIdDesc(
                USER_ID,
                LedgerAiAnalysisMode.COMPARISON,
                LedgerAiAnalysisPeriod.WEEK,
                LedgerAiComparisonPreset.WEEKLY_DIGEST,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 13)
        )).thenReturn(Optional.of(processing));
        LedgerAiAnalysisHistoryDetailResponse expected = new LedgerAiAnalysisHistoryDetailResponse(null, null);
        when(analysisService.getHistory(USER_ID, 901L)).thenReturn(expected);

        assertThat(service.requestNow(USER_ID, MONDAY)).isSameAs(expected);

        verify(ledgerEntryRepository, never()).countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(any(), any(), any());
        verify(analysisService, never()).startAnalyze(any(), any());
    }

    @Test
    void manualRunUsesWeeklyDigestPresetAndFixedWeekAnchor() {
        stubUser();
        stubNoDigest();
        when(ledgerEntryRepository.countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                USER_ID,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20)
        )).thenReturn(4L);
        LedgerAiAnalysisHistoryDetailResponse expected = new LedgerAiAnalysisHistoryDetailResponse(null, null);
        when(analysisService.startAnalyze(eq(USER_ID), any())).thenReturn(expected);

        assertThat(service.requestNow(USER_ID, MONDAY)).isSameAs(expected);

        ArgumentCaptor<LedgerAiAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(LedgerAiAnalysisRequest.class);
        verify(analysisService).startAnalyze(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().comparisonPreset()).isEqualTo(LedgerAiComparisonPreset.WEEKLY_DIGEST);
        assertThat(requestCaptor.getValue().anchorDate()).isEqualTo(MONDAY);
    }

    private void stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
    }

    private void stubNoDigest() {
        when(historyRepository.findTopByOwnerIdAndModeAndPeriodTypeAndComparisonPresetAndFromDateAndToDateAndCompareFromDateAndCompareToDateOrderByCreatedAtDescIdDesc(
                USER_ID,
                LedgerAiAnalysisMode.COMPARISON,
                LedgerAiAnalysisPeriod.WEEK,
                LedgerAiComparisonPreset.WEEKLY_DIGEST,
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 13)
        )).thenReturn(Optional.empty());
    }

    private UserLayoutSetting setting(Long userId, int activePresetId, int palettePresetId, boolean visible, boolean autoEnabled) {
        AppUser owner = new AppUser();
        owner.setId(userId);
        owner.setActive(true);

        UserLayoutSetting setting = new UserLayoutSetting();
        setting.setOwner(owner);
        setting.setLayoutScope("household");
        setting.setPayloadJson("""
                {"currentPresetId":%d,"presets":[{"id":%d,"palettes":[{"type":"weekly-digest","visible":%s,"options":{"autoEnabled":%s}}]}]}
                """.formatted(activePresetId, palettePresetId, visible, autoEnabled));
        return setting;
    }

    private LedgerAiAnalysisStatusResponse aiStatus() {
        return new LedgerAiAnalysisStatusResponse(
                true,
                true,
                "openai",
                false,
                true,
                false,
                true,
                "test-model",
                "ready"
        );
    }
}
