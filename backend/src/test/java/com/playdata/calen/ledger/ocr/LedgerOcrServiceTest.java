package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerOcrEntrySuggestionResponse;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteLineItem;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository.ExistingEntryStyleAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class LedgerOcrServiceTest {

    private static final Long USER_ID = 7L;
    private static final String UNCATEGORIZED = "\uBBF8\uBD84\uB958";

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerOcrRemoteClient remoteClient;

    @Mock
    private UserNotificationService userNotificationService;

    @Mock
    private LedgerImageAnalysisRequestRepository imageAnalysisRequestRepository;

    @Mock
    private CategoryGroupRepository categoryGroupRepository;

    @Mock
    private CategoryDetailRepository categoryDetailRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerOcrService service;

    @BeforeEach
    void setUp() {
        LedgerOcrProperties properties = new LedgerOcrProperties();
        properties.setEnabled(true);
        properties.setWorkflowUrl("https://ocr.example.internal/webhook");
        properties.setMaxFileSize(DataSize.ofBytes(4));

        LedgerAiAnalysisProperties aiProperties = new LedgerAiAnalysisProperties();
        aiProperties.setEnabled(true);
        aiProperties.setProvider("lmstudio");
        aiProperties.setLmStudioBaseUrl("http://localhost:1234");

        service = new LedgerOcrService(
                appUserService,
                properties,
                aiProperties,
                remoteClient,
                imageAnalysisRequestRepository,
                categoryGroupRepository,
                categoryDetailRepository,
                ledgerEntryRepository,
                new ObjectMapper(),
                userNotificationService
        );
    }

    @Test
    void startAnalyzeReturnsProcessingAndQueuesBackgroundTask() {
        stubUser();
        AtomicReference<LedgerImageAnalysisRequest> savedHistory = new AtomicReference<>();
        when(imageAnalysisRequestRepository.save(any(LedgerImageAnalysisRequest.class)))
                .thenAnswer(invocation -> {
                    LedgerImageAnalysisRequest request = invocation.getArgument(0);
                    request.setId(42L);
                    savedHistory.set(request);
                    return request;
                });
        CapturingTaskExecutor executor = new CapturingTaskExecutor();
        ReflectionTestUtils.setField(service, "ledgerOcrTaskExecutor", executor);

        LedgerOcrAnalyzeResponse response = service.startAnalyze(
                USER_ID,
                validJpeg("receipt.jpg"),
                "AUTO",
                "client-1",
                "",
                false
        );

        assertThat(response.analysisId()).isEqualTo(42L);
        assertThat(response.clientRequestId()).isEqualTo("client-1");
        assertThat(response.analysisStatus()).isEqualTo("PROCESSING");
        assertThat(executor.queuedTasks).hasSize(1);
        verify(remoteClient, never()).analyze(any(), anyString(), anyString());

        when(imageAnalysisRequestRepository.findByIdAndOwnerId(42L, USER_ID)).thenReturn(Optional.of(savedHistory.get()));
        assertThat(service.cancelHistory(USER_ID, 42L).status()).isEqualTo("CANCELLED");
        assertThat((FutureTask<?>) executor.queuedTasks.get(0)).isCancelled();
    }
    @Test
    void analyzeRejectsEmptyFileBeforeRemoteCallOrNotification() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                new byte[] {}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient, userNotificationService);
    }

    @Test
    void analyzeRejectsOversizedFileBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                new byte[] {1, 2, 3, 4, 5}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsImageExtensionWithNonImageMimeBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.jpg",
                "application/pdf",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsImageMimeWithNonImageExtensionBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.txt",
                "image/png",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsMismatchedImageMimeAndExtensionBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.jpg",
                "image/png",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsFakeImageBytesBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                new byte[] {'n', 'o', 't'}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(remoteClient, userNotificationService);
    }

    @Test
    void analyzeRecordsInvalidFileMetricWhenUploadValidationFails() {
        stubUser();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "meterRegistry", meterRegistry);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.txt",
                "image/png",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(meterRegistry.get("calen.ledger.ocr.requests")
                .tag("status", "failure")
                .tag("reason", "invalid_file")
                .counter()
                .count()).isEqualTo(1.0);
        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeCreatesBoundedNotificationForRemoteFailureWithoutMaskingOriginalError() {
        stubUser();
        MockMultipartFile file = validJpeg("receipt.jpg");
        stubHistoryPersistence(99L);
        when(remoteClient.analyze(file, "RECEIPT", null))
                .thenThrow(new BadRequestException("OCR analysis server is unavailable. Check the OCR service and network."));
        doThrow(new IllegalStateException("notification unavailable"))
                .when(userNotificationService)
                .createSystemNotification(
                        eq(USER_ID),
                        eq("AI_IMAGE_ANALYSIS_FAILED"),
                        anyString(),
                        anyString(),
                        eq("/calendar?receiptOcr=1"),
                        eq("{\"reason\":\"bad_request\"}")
                );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT", "ocr-test-request", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OCR analysis server is unavailable. Check the OCR service and network.");

        verify(userNotificationService).createSystemNotification(
                eq(USER_ID),
                eq("AI_IMAGE_ANALYSIS_FAILED"),
                anyString(),
                anyString(),
                eq("/calendar?receiptOcr=1"),
                eq("{\"reason\":\"bad_request\"}")
        );
    }

    @Test
    void analyzeReturnsOneSuggestionPerVisibleNaverPayPaymentRow() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("naver-pay.jpg");
        stubHistoryPersistence(101L);
        CategoryGroup hobby = categoryGroup(10L, user, "취미", EntryType.EXPENSE, true);
        CategoryDetail content = categoryDetail(11L, hobby, "콘텐츠", true);
        when(categoryGroupRepository.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(USER_ID, EntryType.EXPENSE, "취미"))
                .thenReturn(Optional.of(hobby));
        when(categoryDetailRepository.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(10L, "콘텐츠"))
                .thenReturn(Optional.of(content));
        List<RemoteParsedResult> entries = List.of(
                naverPayEntry("네이버페이 : 웹툰·시리즈 쿠키 59개", "결제완료 / 7. 5. 15:15 결제", "웹툰·시리즈 쿠키 59개", "4900", LocalDate.of(2026, 7, 5), LocalTime.of(15, 15)),
                naverPayEntry("네이버페이 : 메가MGC커피 모바일금액권 1만원권", "구매확정완료 / 7. 4. 18:32 결제", "메가MGC커피 모바일금액권 1만원권", "8730", LocalDate.of(2026, 7, 4), LocalTime.of(18, 32)),
                naverPayEntry("네이버페이 : 메가MGC커피 모바일금액권 3만원권", "구매확정완료 / 7. 4. 18:32 결제", "메가MGC커피 모바일금액권 3만원권", "27000", LocalDate.of(2026, 7, 4), LocalTime.of(18, 32)),
                naverPayEntry("네이버페이 : 노브랜드버거 모바일금액권 1만원권", "구매확정완료 / 7. 4. 18:29 결제", "노브랜드버거 모바일금액권 1만원권", "7300", LocalDate.of(2026, 7, 4), LocalTime.of(18, 29))
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", "네이버페이 결제내역 4건", entries.get(0), entries, Map.of()));

        List<LedgerOcrEntrySuggestionResponse> suggestions = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "naver-pay-4", null)
                .suggestedEntries();

        assertThat(suggestions).hasSize(4);
        assertThat(suggestions).extracting(LedgerOcrEntrySuggestionResponse::amount)
                .containsExactly(new BigDecimal("4900"), new BigDecimal("8730"), new BigDecimal("27000"), new BigDecimal("7300"));
        assertThat(suggestions).extracting(LedgerOcrEntrySuggestionResponse::entryDate)
                .containsExactly(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 4));
        assertThat(suggestions).extracting(LedgerOcrEntrySuggestionResponse::entryTime)
                .containsExactly(LocalTime.of(15, 15), LocalTime.of(18, 32), LocalTime.of(18, 32), LocalTime.of(18, 29));
        assertThat(suggestions).extracting(LedgerOcrEntrySuggestionResponse::title)
                .containsExactly(
                        "네이버페이 : 웹툰·시리즈 쿠키 59개",
                        "네이버페이 : 메가MGC커피 모바일금액권 1만원권",
                        "네이버페이 : 메가MGC커피 모바일금액권 3만원권",
                        "네이버페이 : 노브랜드버거 모바일금액권 1만원권"
                );
        assertThat(suggestions).allSatisfy(suggestion -> {
            assertThat(suggestion.title()).doesNotContain("결제완료", "구매확정완료");
            assertThat(suggestion.paymentMethodId()).isNull();
            assertThat(suggestion.paymentMethodName()).isNull();
            assertThat(suggestion.categoryGroupId()).isEqualTo(10L);
            assertThat(suggestion.categoryGroupName()).isEqualTo("취미");
            assertThat(suggestion.categoryDetailId()).isEqualTo(11L);
            assertThat(suggestion.categoryDetailName()).isEqualTo("콘텐츠");
        });
        assertThat(suggestions.get(0).memo()).contains("웹툰·시리즈 쿠키 59개(4,900원)", "결제완료", "15:15");
        assertThat(suggestions.get(1).memo()).contains("메가MGC커피 모바일금액권 1만원권(8,730원)", "구매확정완료", "18:32");
    }
    @Test
    void analyzeAppliesPromptPlatformAndCleansPaymentCaptureTitleNoise() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("naver-pay-prompt.jpg");
        stubHistoryPersistence(103L);
        CategoryGroup uncategorized = categoryGroup(88L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));

        String prompt = "\uB124\uC774\uBC84\uD398\uC774 \uB2E4\uAC74 \uACB0\uC81C \uCEA1\uCC98\uC785\uB2C8\uB2E4.";
        String itemName = "\uBA54\uAC00MGC\uCEE4\uD53C \uBAA8\uBC14\uC77C\uCFE0\uD3F0 1\uB9CC\uC6D0\uAD8C";
        String noisyTitle = "[EVENT] " + itemName + " \uAD6C\uB9E4\uD655\uC815\uC804\uC6D4";
        String memo = "\uAD6C\uB9E4\uD655\uC815\uC644\uB8CC / 7. 4. 18:32 \uACB0\uC81C";
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 7, 4),
                LocalTime.of(18, 32),
                EntryType.EXPENSE,
                noisyTitle,
                memo,
                new BigDecimal("8730"),
                "",
                "",
                "",
                "",
                "",
                List.of(new RemoteLineItem(itemName, BigDecimal.ONE, "", new BigDecimal("8730"))),
                0.82,
                List.of()
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", prompt))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", itemName + " 8,730\uC6D0", parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "naver-pay-prompt", prompt)
                .suggestedEntries()
                .get(0);

        assertThat(suggestion.title()).isEqualTo("\uB124\uC774\uBC84\uD398\uC774 : [EVENT] " + itemName);
        assertThat(suggestion.title()).doesNotContain("\uAD6C\uB9E4\uD655\uC815", "\uAD6C\uB9E4\uAC74");
        assertThat(suggestion.memo()).contains(itemName + "(8,730\uC6D0)", memo);
        assertThat(suggestion.paymentMethodId()).isNull();
        assertThat(suggestion.paymentMethodName()).isNull();
        assertThat(suggestion.categoryGroupId()).isEqualTo(88L);
        assertThat(suggestion.categoryGroupName()).isEqualTo(UNCATEGORIZED);
    }


    @Test
    void analyzeFallsBackToUncategorizedWhenSuggestedCategoryDoesNotExist() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("unknown-category.jpg");
        stubHistoryPersistence(102L);
        CategoryGroup uncategorized = categoryGroup(99L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(USER_ID, EntryType.EXPENSE, "구독"))
                .thenReturn(Optional.empty());
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));
        when(categoryDetailRepository.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(99L, "정기결제"))
                .thenReturn(Optional.empty());
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 7, 5),
                LocalTime.of(15, 15),
                EntryType.EXPENSE,
                "네이버페이 : 웹툰·시리즈 쿠키 59개",
                "결제완료 / 분류는 추정값",
                new BigDecimal("4900"),
                "네이버페이",
                "",
                "구독",
                "정기결제",
                "",
                List.of(new RemoteLineItem("웹툰·시리즈 쿠키 59개", BigDecimal.ONE, "건", new BigDecimal("4900"))),
                0.82,
                List.of()
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", "네이버페이 결제내역", parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "uncategorized", null)
                .suggestedEntries()
                .get(0);

        assertThat(suggestion.categoryGroupId()).isEqualTo(99L);
        assertThat(suggestion.categoryGroupName()).isEqualTo(UNCATEGORIZED);
        assertThat(suggestion.categoryDetailId()).isNull();
        assertThat(suggestion.categoryDetailName()).isNull();
        verify(categoryGroupRepository, never()).save(any(CategoryGroup.class));
    }

    @Test
    void analyzeAddsExistingEntryStyleExamplesToPromptWhenEnabled() {
        stubUser();
        MockMultipartFile file = validJpeg("style-context.jpg");
        stubHistoryPersistence(104L);
        ExistingEntryStyleAggregate existingExample = existingStyleExample(
                "쿠팡 : 사과 1개",
                "정기배송 아님. 빨간 사과 1개.",
                "생활",
                "식료품",
                "국민카드",
                new BigDecimal("12000"),
                EntryType.EXPENSE
        );
        when(ledgerEntryRepository.findRecentEntriesForOcrStyle(eq(USER_ID), any()))
                .thenReturn(List.of(existingExample));
        when(remoteClient.analyze(eq(file), eq("PAYMENT_CAPTURE"), any()))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", "", null, List.of(), Map.of()));

        service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "style-on", "쿠팡 캡처입니다.", true);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(remoteClient).analyze(eq(file), eq("PAYMENT_CAPTURE"), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("쿠팡 캡처입니다.")
                .contains("현재 입력데이터 기반 데이터 추출 보정")
                .contains("쿠팡 : 사과 1개")
                .contains("생활/식료품")
                .contains("국민카드")
                .contains("이미지에서 확인되는 사실이 최우선");
    }

    @Test
    void analyzeAddsCurrentCategoryCriteriaToPrompt() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("category-criteria.jpg");
        stubHistoryPersistence(110L);
        CategoryGroup food = categoryGroup(31L, user, "식비", EntryType.EXPENSE, true);
        CategoryGroup hobby = categoryGroup(32L, user, "취미", EntryType.EXPENSE, true);
        CategoryGroup salary = categoryGroup(33L, user, "급여", EntryType.INCOME, true);
        CategoryDetail grocery = categoryDetail(41L, food, "식재료", true);
        CategoryDetail cafe = categoryDetail(42L, food, "카페", true);
        CategoryDetail inactive = categoryDetail(43L, food, "비활성", false);
        CategoryDetail game = categoryDetail(44L, hobby, "게임", true);
        when(categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(food, hobby, salary));
        when(categoryDetailRepository.findAllByGroupIdAndActiveTrueOrderByDisplayOrderAscIdAsc(31L))
                .thenReturn(List.of(grocery, cafe, inactive));
        when(categoryDetailRepository.findAllByGroupIdAndActiveTrueOrderByDisplayOrderAscIdAsc(32L))
                .thenReturn(List.of(game));
        when(categoryDetailRepository.findAllByGroupIdAndActiveTrueOrderByDisplayOrderAscIdAsc(33L))
                .thenReturn(List.of());
        when(remoteClient.analyze(eq(file), eq("PAYMENT_CAPTURE"), any()))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", "", null, List.of(), Map.of()));

        service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "category-criteria", "네이버페이 캡처입니다.", false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(remoteClient).analyze(eq(file), eq("PAYMENT_CAPTURE"), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("네이버페이 캡처입니다.")
                .contains("현재 사용자 분류 기준")
                .contains("정확한 이름")
                .contains("지출:\n- 식비: 식재료, 카페")
                .contains("- 취미: 게임")
                .contains("수입:\n- 급여")
                .doesNotContain("비활성");
        verify(ledgerEntryRepository, never()).findRecentEntriesForOcrStyle(any(), any());
    }
    @Test
    void analyzeDoesNotLoadExistingEntryStyleExamplesWhenDisabled() {
        stubUser();
        MockMultipartFile file = validJpeg("style-context-disabled.jpg");
        stubHistoryPersistence(105L);
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", "사용자 요청"))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", "", null, List.of(), Map.of()));

        service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "style-off", "사용자 요청", false);

        verify(ledgerEntryRepository, never()).findRecentEntriesForOcrStyle(any(), any());
        verify(remoteClient).analyze(file, "PAYMENT_CAPTURE", "사용자 요청");
    }

    @Test
    void analyzeCorrectsOrderHistoryAmountFromVisibleProductAmount() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("order-history.jpg");
        stubHistoryPersistence(107L);
        CategoryGroup uncategorized = categoryGroup(21L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));

        String itemName = "비바스 내추럴99% 시카 천연샴푸 1000g_2개";
        String memo = itemName
                + " / 주문일자: 2026-04-23 / 상품금액(부가): 25,020원 / 주문처(판매자): 해두엔 / 구매확정";
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 4, 23),
                LocalTime.of(15, 34),
                EntryType.EXPENSE,
                "네이버페이 : 해두엔 : " + itemName,
                memo,
                new BigDecimal("50020"),
                "해두엔",
                "",
                "",
                "",
                "",
                List.of(new RemoteLineItem(itemName, new BigDecimal("2"), "개", new BigDecimal("25020"))),
                0.77,
                List.of()
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", memo, parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "order-history", null)
                .suggestedEntries()
                .get(0);

        assertThat(suggestion.amount()).isEqualByComparingTo("25020");
        assertThat(suggestion.memo()).contains("상품금액(부가): 25,020원", "해두엔", "구매확정");
    }

    @Test
    void analyzeCorrectsOrderHistoryAmountFromLineItemMemoAmountWithoutLabel() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("order-history-unlabeled.jpg");
        stubHistoryPersistence(108L);
        CategoryGroup uncategorized = categoryGroup(22L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));

        String itemName = "비바스 내추럴99% 시카 천연샴푸 1000g_2개";
        String memo = itemName + " x2개(25,020원) / 주문일자: 2026-04-23 / 주문처(판매자): 해두엔 / 구매확정";
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 4, 23),
                LocalTime.of(15, 34),
                EntryType.EXPENSE,
                "네이버페이 : 해두엔 : " + itemName,
                memo,
                new BigDecimal("50020"),
                "해두엔",
                "",
                "",
                "",
                "",
                List.of(new RemoteLineItem(itemName, new BigDecimal("2"), "개", new BigDecimal("25020"))),
                0.77,
                List.of()
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", memo, parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "order-history-unlabeled", null)
                .suggestedEntries()
                .get(0);

        assertThat(suggestion.amount()).isEqualByComparingTo("25020");
        assertThat(suggestion.memo()).contains("25,020원", "해두엔", "구매확정");
    }

    @Test
    void analyzeChoosesAmountNearMatchingOrderHistoryRowWhenMemoContainsMultipleRows() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("order-history-multiple-rows.jpg");
        stubHistoryPersistence(109L);
        CategoryGroup uncategorized = categoryGroup(23L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));

        String firstItem = "비바스 내추럴99% 시카 천연샴푸 1000g_2개";
        String secondItem = "초강력 무선 에어건 Aero X10 130000rpm";
        String memo = firstItem + " / 상품금액(부가): 25,020원 / 주문처(판매자): 해두엔 / 구매확정\n"
                + secondItem + " / 상품금액(부가): 37,370원 / 주문처(판매자): 은큰 / 구매확정";
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 1, 23),
                null,
                EntryType.EXPENSE,
                "네이버페이 : 은큰 : " + secondItem,
                memo,
                new BigDecimal("50020"),
                "은큰",
                "",
                "",
                "",
                "",
                List.of(),
                0.71,
                List.of()
        );
        when(remoteClient.analyze(file, "PAYMENT_CAPTURE", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "PAYMENT_CAPTURE", memo, parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "PAYMENT_CAPTURE", "order-history-multiple-rows", null)
                .suggestedEntries()
                .get(0);

        assertThat(suggestion.amount()).isEqualByComparingTo("37370");
        assertThat(suggestion.title()).contains("초강력 무선 에어건");
    }
    @Test
    void analyzeUsesLineItemTitleWhenSalesSlipTitleIsGeneric() {
        AppUser user = stubUser();
        MockMultipartFile file = validJpeg("sales-slip.jpg");
        stubHistoryPersistence(106L);
        CategoryGroup uncategorized = categoryGroup(20L, user, UNCATEGORIZED, EntryType.EXPENSE, true);
        when(categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(USER_ID, EntryType.EXPENSE))
                .thenReturn(List.of(uncategorized));
        RemoteParsedResult parsed = new RemoteParsedResult(
                LocalDate.of(2026, 6, 24),
                LocalTime.of(15, 15),
                EntryType.EXPENSE,
                "SALES SLIP",
                "CARD TYPE Hyundai Card / ITEM Lost Ark 80,000 Royal Crystal / TOTAL 80,000",
                new BigDecimal("80000"),
                "Hyundai Card",
                "Hyundai Card",
                "",
                "",
                "",
                List.of(new RemoteLineItem("Lost Ark 80,000 Royal Crystal", BigDecimal.ONE, "item", new BigDecimal("80000"))),
                0.9,
                List.of()
        );
        when(remoteClient.analyze(file, "RECEIPT", null))
                .thenReturn(new RemoteAnalyzeResponse(true, null, "RECEIPT", "SALES SLIP ITEM Lost Ark 80,000 Royal Crystal", parsed, List.of(parsed), Map.of()));

        LedgerOcrEntrySuggestionResponse suggestion = service.analyze(USER_ID, file, "RECEIPT", "sales-slip-item", null)
                .suggestedEntry();

        assertThat(suggestion.title()).isEqualTo("Lost Ark 80,000 Royal Crystal");
        assertThat(suggestion.amount()).isEqualByComparingTo("80000");
        assertThat(suggestion.entryDate()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(suggestion.entryTime()).isEqualTo(LocalTime.of(15, 15));
        assertThat(suggestion.memo()).contains("Lost Ark 80,000 Royal Crystal", "80,000");
    }
    private RemoteParsedResult naverPayEntry(
            String title,
            String memo,
            String itemName,
            String amount,
            LocalDate date,
            LocalTime time
    ) {
        return new RemoteParsedResult(
                date,
                time,
                EntryType.EXPENSE,
                title,
                memo,
                new BigDecimal(amount),
                "네이버페이",
                "",
                "취미",
                "콘텐츠",
                "",
                List.of(new RemoteLineItem(itemName, BigDecimal.ONE, "건", new BigDecimal(amount))),
                0.9,
                List.of()
        );
    }

    private void stubHistoryPersistence(long id) {
        when(imageAnalysisRequestRepository.save(any(LedgerImageAnalysisRequest.class)))
                .thenAnswer(invocation -> {
                    LedgerImageAnalysisRequest request = invocation.getArgument(0);
                    if (request.getId() == null) {
                        request.setId(id);
                    }
                    return request;
                });
        when(imageAnalysisRequestRepository.findById(id)).thenReturn(Optional.empty());
    }

    private MockMultipartFile validJpeg(String fileName) {
        return new MockMultipartFile(
                "file",
                fileName,
                "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );
    }

    private CategoryGroup categoryGroup(long id, AppUser owner, String name, EntryType entryType, boolean active) {
        CategoryGroup group = new CategoryGroup();
        group.setId(id);
        group.setOwner(owner);
        group.setName(name);
        group.setEntryType(entryType);
        group.setDisplayOrder(1);
        group.setActive(active);
        return group;
    }

    private CategoryDetail categoryDetail(long id, CategoryGroup group, String name, boolean active) {
        CategoryDetail detail = new CategoryDetail();
        detail.setId(id);
        detail.setGroup(group);
        detail.setName(name);
        detail.setDisplayOrder(1);
        detail.setActive(active);
        return detail;
    }

    private ExistingEntryStyleAggregate existingStyleExample(
            String title,
            String memo,
            String categoryGroupName,
            String categoryDetailName,
            String paymentMethodName,
            BigDecimal amount,
            EntryType entryType
    ) {
        ExistingEntryStyleAggregate example = org.mockito.Mockito.mock(ExistingEntryStyleAggregate.class);
        when(example.getTitle()).thenReturn(title);
        when(example.getMemo()).thenReturn(memo);
        when(example.getCategoryGroupName()).thenReturn(categoryGroupName);
        when(example.getCategoryDetailName()).thenReturn(categoryDetailName);
        when(example.getPaymentMethodName()).thenReturn(paymentMethodName);
        when(example.getAmount()).thenReturn(amount);
        when(example.getEntryType()).thenReturn(entryType);
        return example;
    }
    private static class CapturingTaskExecutor extends ThreadPoolTaskExecutor {
        private final List<Runnable> queuedTasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            queuedTasks.add(task);
        }
    }
    private AppUser stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
        return user;
    }
}
