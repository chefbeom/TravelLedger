package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.CategoryBreakdownItemResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse;
import com.playdata.calen.ledger.dto.PeriodComparisonItemResponse;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.service.StatisticsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LedgerAiAnalysisServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate JUNE_18 = LocalDate.of(2026, 6, 18);
    private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_30 = LocalDate.of(2026, 6, 30);

    @Mock
    private AppUserService appUserService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LedgerAiAnalysisHistoryRepository historyRepository;

    @Mock
    private LedgerAiRemoteClient remoteClient;

    private LedgerAiAnalysisProperties properties;
    private LedgerAiAnalysisService service;

    @BeforeEach
    void setUp() {
        properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setWorkflowUrl("http://127.0.0.1:5678/webhook/travelledger-ledger-ai");
        properties.setModel("gemma4:e12b");

        service = new LedgerAiAnalysisService(
                appUserService,
                statisticsService,
                ledgerEntryRepository,
                historyRepository,
                properties,
                remoteClient,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void statusDoesNotExposeProviderUrlsOrApiKeys() throws Exception {
        properties.setProvider("lmstudio");
        properties.setWorkflowUrl("https://n8n.example.internal/webhook/travelledger-secret");
        properties.setApiKey("n8n-secret-token");
        properties.setLmStudioBaseUrl("http://172.18.240.1:1234");
        properties.setLmStudioApiKey("lmstudio-secret-token");

        LedgerAiAnalysisStatusResponse status = service.getStatus();
        String json = new ObjectMapper().writeValueAsString(status);
        JsonNode node = new ObjectMapper().readTree(json);

        assertThat(status.configured()).isTrue();
        assertThat(status.apiKeyConfigured()).isTrue();
        assertThat(status.lmStudioConfigured()).isTrue();
        assertThat(node.has("workflowUrl")).isFalse();
        assertThat(node.has("lmStudioBaseUrl")).isFalse();
        assertThat(node.has("apiKey")).isFalse();
        assertThat(node.has("lmStudioApiKey")).isFalse();
        assertThat(json)
                .doesNotContain("https://n8n.example.internal")
                .doesNotContain("172.18.240.1")
                .doesNotContain("n8n-secret-token")
                .doesNotContain("lmstudio-secret-token");
    }

    @Test
    void analyzeBuildsMonthlyPayloadAndStoresCompletedHistory() {
        stubUser();
        stubMonthlyDataset();
        when(remoteClient.analyze(any())).thenReturn(remoteResponse());
        when(historyRepository.save(any())).thenAnswer(invocation -> {
            LedgerAiAnalysisHistory history = invocation.getArgument(0);
            history.setId(44L);
            return history;
        });

        var response = service.analyze(USER_ID, new LedgerAiAnalysisRequest(
                LedgerAiAnalysisMode.PERIOD,
                LedgerAiAnalysisPeriod.MONTH,
                null,
                JUNE_18,
                null,
                null,
                null,
                null
        ));

        assertThat(response.historyId()).isEqualTo(44L);
        assertThat(response.from()).isEqualTo(JUNE_1);
        assertThat(response.to()).isEqualTo(JUNE_30);
        assertThat(response.totalExpense()).isEqualByComparingTo("300000");
        assertThat(response.averageDailyExpense()).isEqualByComparingTo("10000.00");
        assertThat(response.report().keySummary()).isEqualTo("key summary");
        assertThat(response.report().fullReport()).contains("full report");
        assertThat(response.summary()).isEqualTo("6월 지출은 식비 중심으로 증가했습니다.");
        assertThat(response.highlights()).containsExactly("식비 비중이 가장 높습니다.");
        assertThat(response.recommendations()).containsExactly("외식 횟수를 주 1회 줄여보세요.");

        ArgumentCaptor<LedgerAiAnalysisService.LedgerAiN8nPayload> payloadCaptor =
                ArgumentCaptor.forClass(LedgerAiAnalysisService.LedgerAiN8nPayload.class);
        verify(remoteClient).analyze(payloadCaptor.capture());
        LedgerAiAnalysisService.LedgerAiN8nPayload payload = payloadCaptor.getValue();
        assertThat(payload.schemaVersion()).isEqualTo("travelledger.ledger-ai-analysis.v2");
        assertThat(payload.model()).isEqualTo("gemma4:e12b");
        assertThat(payload.mode()).isEqualTo(LedgerAiAnalysisMode.PERIOD);
        assertThat(payload.periodType()).isEqualTo(LedgerAiAnalysisPeriod.MONTH);
        assertThat(payload.from()).isEqualTo(JUNE_1);
        assertThat(payload.to()).isEqualTo(JUNE_30);
        assertThat(payload.compareFrom()).isNull();
        assertThat(payload.expenseEntries()).hasSize(2);
        assertThat(payload.expenseEntries().get(0).title()).isEqualTo("점심 식사");
        assertThat(payload.expenseEntries().get(0).memo()).isEqualTo("회사 근처 식당");
        assertThat(payload.topExpenses()).hasSize(2);
        assertThat(payload.expensePaymentBreakdown()).hasSize(1);
        assertThat(payload.expensePaymentBreakdown().get(0).totalAmount()).isEqualByComparingTo("30000");
        assertThat(payload.recurringExpenseCandidates()).hasSize(1);
        assertThat(payload.recurringExpenseCandidates().get(0).title()).isEqualTo("점심 식사");
        assertThat(payload.recurringExpenseCandidates().get(0).occurrenceCount()).isEqualTo(2);
        assertThat(payload.recurringExpenseCandidates().get(0).totalAmount()).isEqualByComparingTo("30000");
        assertThat(payload.recurringExpenseCandidates().get(0).averageAmount()).isEqualByComparingTo("15000.00");
        assertThat(payload.outputContract()).contains("JSON only");

        ArgumentCaptor<LedgerAiAnalysisHistory> historyCaptor = ArgumentCaptor.forClass(LedgerAiAnalysisHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        LedgerAiAnalysisHistory history = historyCaptor.getValue();
        assertThat(history.getStatus()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
        assertThat(history.getTitle()).isEqualTo("AI 지출 분석 - 2026-06-01 ~ 2026-06-30");
        assertThat(history.getSummary()).isEqualTo("6월 지출은 식비 중심으로 증가했습니다.");
        assertThat(history.getRequestPayloadJson()).contains("점심 식사");
        assertThat(history.getResultJson()).contains("외식 횟수");
    }

    @Test
    void analyzeStoresFailedHistoryWhenN8nRequestFails() {
        stubUser();
        stubMonthlyDataset();
        when(remoteClient.analyze(any())).thenThrow(new BadRequestException("n8n 연결 실패"));
        when(historyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.analyze(USER_ID, new LedgerAiAnalysisRequest(
                LedgerAiAnalysisMode.PERIOD,
                LedgerAiAnalysisPeriod.MONTH,
                null,
                JUNE_18,
                null,
                null,
                null,
                null
        ))).isInstanceOf(BadRequestException.class)
                .hasMessage("n8n 연결 실패");

        ArgumentCaptor<LedgerAiAnalysisHistory> historyCaptor = ArgumentCaptor.forClass(LedgerAiAnalysisHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        LedgerAiAnalysisHistory failedHistory = historyCaptor.getValue();
        assertThat(failedHistory.getStatus()).isEqualTo(LedgerAiAnalysisStatus.FAILED);
        assertThat(failedHistory.getSummary()).isEqualTo("AI 분석 요청 실패");
        assertThat(failedHistory.getErrorMessage()).isEqualTo("n8n 연결 실패");
        assertThat(failedHistory.getRequestPayloadJson()).contains("점심 식사");
    }

    private void stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setLoginId("owner");
        user.setDisplayName("Owner");
        user.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
    }

    private void stubMonthlyDataset() {
        when(statisticsService.getOverview(USER_ID, JUNE_1, JUNE_30)).thenReturn(new OverviewResponse(
                JUNE_1,
                JUNE_30,
                BigDecimal.ZERO,
                new BigDecimal("300000"),
                new BigDecimal("-300000"),
                12
        ));
        when(statisticsService.getCategoryBreakdown(USER_ID, JUNE_1, JUNE_30, EntryType.EXPENSE))
                .thenReturn(List.of(new CategoryBreakdownItemResponse("지출", "식비", new BigDecimal("180000"), 8)));
        when(statisticsService.getPaymentBreakdown(USER_ID, JUNE_1, JUNE_30))
                .thenReturn(List.of(new PaymentBreakdownItemResponse("현대 카드", PaymentMethodKind.CARD, new BigDecimal("200000"), 6)));
        when(statisticsService.compare(eq(USER_ID), eq(JUNE_30), any(), anyInt()))
                .thenReturn(List.of(new PeriodComparisonItemResponse(
                        "2026-06",
                        JUNE_1,
                        JUNE_30,
                        BigDecimal.ZERO,
                        new BigDecimal("300000"),
                        new BigDecimal("-300000")
                )));
        List<LedgerEntryRepository.AiExpenseEntryAggregate> entries = List.of(
                expenseEntry(LocalDate.of(2026, 6, 12), "점심 식사", "회사 근처 식당", "15000"),
                expenseEntry(LocalDate.of(2026, 6, 19), "점심 식사", "회사 근처 식당", "15000")
        );
        when(ledgerEntryRepository.findExpenseEntriesForAiAnalysis(USER_ID, JUNE_1, JUNE_30, EntryType.EXPENSE))
                .thenReturn(entries);
        when(ledgerEntryRepository.findTopExpenseEntriesForAiAnalysis(
                eq(USER_ID),
                eq(JUNE_1),
                eq(JUNE_30),
                eq(EntryType.EXPENSE),
                any(Pageable.class)
        )).thenReturn(entries);
    }

    private LedgerAiRemoteResponse remoteResponse() {
        return new LedgerAiRemoteResponse(
                true,
                null,
                "6월 지출은 식비 중심으로 증가했습니다.",
                List.of("식비 비중이 가장 높습니다."),
                List.of("외식 지출이 평소보다 높습니다."),
                List.of(),
                List.of("외식 횟수를 주 1회 줄여보세요."),
                List.of("식비가 전체 지출의 절반 이상입니다."),
                List.of("카드 사용 비중이 높습니다."),
                List.of("월 후반 지출이 집중됩니다."),
                List.of("점심 식사 반복 지출이 보입니다."),
                List.of("구독성 결제는 확인되지 않았습니다."),
                "다음 달도 식비가 주요 위험 항목입니다.",
                "충동 소비보다 반복 식비 관리가 필요합니다.",
                new LedgerAiAnalysisReportResponse(
                        "key summary",
                        "full report text",
                        "average insight",
                        List.of("notable"),
                        List.of("regular"),
                        List.of("abnormal"),
                        "top payment",
                        List.of("subscription"),
                        List.of("fixed"),
                        List.of("improvement"),
                        List.of()
                )        );
    }

    private LedgerEntryRepository.AiExpenseEntryAggregate expenseEntry(
            LocalDate entryDate,
            String title,
            String memo,
            String amount
    ) {
        return new LedgerEntryRepository.AiExpenseEntryAggregate() {
            @Override
            public LocalDate getEntryDate() {
                return entryDate;
            }

            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getMemo() {
                return memo;
            }

            @Override
            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }

            @Override
            public String getCategoryGroupName() {
                return "지출";
            }

            @Override
            public String getCategoryDetailName() {
                return "식비";
            }

            @Override
            public String getPaymentMethodName() {
                return "현대 카드";
            }
        };
    }
}
