package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WeeklySpendingDigestService {

    private static final Logger log = LoggerFactory.getLogger(WeeklySpendingDigestService.class);
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final String HOUSEHOLD_LAYOUT_SCOPE = "household";
    private static final Duration MANUAL_DUPLICATE_WINDOW = Duration.ofMinutes(5);
    private static final String DIGEST_FOCUS_PROMPT = """
            Write a concise Korean weekly spending brief. Use complete-period totals and category/payment
            aggregates to describe spending patterns and main usage areas. Compare the prior complete
            week, note meaningful changes, and never infer beyond provided data. Individual transactions
            are a limited sample; treat aggregates as authoritative.
            """.strip();

    private final UserLayoutSettingRepository userLayoutSettingRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final LedgerAiAnalysisService analysisService;
    private final AppUserService appUserService;
    private final ObjectMapper objectMapper;

    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Seoul")
    public void scheduleWeeklyDigest() {
        scheduleWeeklyDigest(LocalDate.now(KOREA_ZONE));
    }

    void scheduleWeeklyDigest(LocalDate anchorDate) {
        try {
            var status = analysisService.getStatus();
            if (!status.enabled() || !status.configured()) {
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("Weekly spending digest scheduler could not read AI status.");
            return;
        }

        List<UserLayoutSetting> settings;
        try {
            settings = userLayoutSettingRepository.findActiveSettingsByLayoutScope(HOUSEHOLD_LAYOUT_SCOPE);
        } catch (RuntimeException exception) {
            log.warn("Weekly spending digest scheduler could not read dashboard settings.");
            return;
        }

        for (UserLayoutSetting setting : settings) {
            Long userId = setting.getOwner() == null ? null : setting.getOwner().getId();
            if (userId == null || !hasVisibleAutomaticDigest(setting)) {
                continue;
            }
            try {
                scheduleForUser(userId, anchorDate);
            } catch (RuntimeException exception) {
                log.warn("Weekly spending digest could not be queued for userId={}.", userId);
            }
        }
    }

    public LedgerAiAnalysisHistoryDetailResponse getLatest(Long userId) {
        return getLatest(userId, LocalDate.now(KOREA_ZONE));
    }

    LedgerAiAnalysisHistoryDetailResponse getLatest(Long userId, LocalDate anchorDate) {
        appUserService.getRequiredUser(userId);
        return findLatestDigest(userId, weekRanges(anchorDate))
                .map(history -> analysisService.getHistory(userId, history.getId()))
                .orElse(null);
    }

    public LedgerAiAnalysisHistoryDetailResponse requestNow(Long userId) {
        return requestNow(userId, LocalDate.now(KOREA_ZONE));
    }

    LedgerAiAnalysisHistoryDetailResponse requestNow(Long userId, LocalDate anchorDate) {
        appUserService.getRequiredUser(userId);
        synchronized (userLocks.computeIfAbsent(userId, ignored -> new Object())) {
            WeeklyDateRanges ranges = weekRanges(anchorDate);
            Optional<LedgerAiAnalysisHistory> existing = findLatestDigest(userId, ranges);
            if (existing.isPresent()) {
                LedgerAiAnalysisHistory history = existing.get();
                if (history.getStatus() == LedgerAiAnalysisStatus.PROCESSING || isRecentlyCompleted(history)) {
                    return analysisService.getHistory(userId, history.getId());
                }
            }

            if (countEntries(userId, ranges) == 0) {
                throw new BadRequestException("\uC9C0\uB09C \uC644\uB8CC \uC8FC\uAC04\uC5D0 \uAC00\uACC4\uBD80 \uAE30\uB85D\uC774 \uC5C6\uC5B4 \uC8FC\uAC04 \uBE0C\uB9AC\uD551\uC744 \uC0DD\uC131\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.");
            }
            return analysisService.startAnalyze(userId, weeklyRequest(anchorDate));
        }
    }

    static WeeklyDateRanges weekRanges(LocalDate anchorDate) {
        LocalDate currentWeekStart = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = currentWeekStart.minusWeeks(1);
        LocalDate to = from.plusDays(6);
        return new WeeklyDateRanges(from, to, from.minusWeeks(1), from.minusDays(1));
    }

    static LedgerAiAnalysisRequest weeklyRequest(LocalDate anchorDate) {
        return new LedgerAiAnalysisRequest(
                LedgerAiAnalysisMode.COMPARISON,
                LedgerAiAnalysisPeriod.WEEK,
                LedgerAiComparisonPreset.WEEKLY_DIGEST,
                anchorDate,
                null,
                null,
                null,
                null,
                DIGEST_FOCUS_PROMPT,
                null
        );
    }

    private void scheduleForUser(Long userId, LocalDate anchorDate) {
        synchronized (userLocks.computeIfAbsent(userId, ignored -> new Object())) {
            WeeklyDateRanges ranges = weekRanges(anchorDate);
            if (findLatestDigest(userId, ranges).isPresent() || countEntries(userId, ranges) == 0) {
                return;
            }
            analysisService.startAnalyze(userId, weeklyRequest(anchorDate));
        }
    }

    private Optional<LedgerAiAnalysisHistory> findLatestDigest(Long userId, WeeklyDateRanges ranges) {
        return historyRepository.findTopByOwnerIdAndModeAndPeriodTypeAndComparisonPresetAndFromDateAndToDateAndCompareFromDateAndCompareToDateOrderByCreatedAtDescIdDesc(
                userId,
                LedgerAiAnalysisMode.COMPARISON,
                LedgerAiAnalysisPeriod.WEEK,
                LedgerAiComparisonPreset.WEEKLY_DIGEST,
                ranges.from(),
                ranges.to(),
                ranges.compareFrom(),
                ranges.compareTo()
        );
    }

    private long countEntries(Long userId, WeeklyDateRanges ranges) {
        return ledgerEntryRepository.countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                userId,
                ranges.from(),
                ranges.to()
        );
    }

    private boolean isRecentlyCompleted(LedgerAiAnalysisHistory history) {
        return history.getStatus() == LedgerAiAnalysisStatus.COMPLETED
                && history.getCreatedAt() != null
                && !history.getCreatedAt().isBefore(LocalDateTime.now(KOREA_ZONE).minus(MANUAL_DUPLICATE_WINDOW));
    }

    private boolean hasVisibleAutomaticDigest(UserLayoutSetting setting) {
        try {
            JsonNode root = objectMapper.readTree(setting.getPayloadJson());
            if (root == null || !root.path("presets").isArray()) {
                return false;
            }

            String activePresetId = root.path("currentPresetId").asText("");
            if (activePresetId.isBlank()) {
                return false;
            }
            for (JsonNode preset : root.path("presets")) {
                if (!activePresetId.equals(preset.path("id").asText("")) || !preset.path("palettes").isArray()) {
                    continue;
                }
                for (JsonNode palette : preset.path("palettes")) {
                    if ("weekly-digest".equals(palette.path("type").asText())
                            && palette.path("visible").asBoolean(false)
                            && palette.path("options").path("autoEnabled").asBoolean(false)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    record WeeklyDateRanges(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {
    }
}
