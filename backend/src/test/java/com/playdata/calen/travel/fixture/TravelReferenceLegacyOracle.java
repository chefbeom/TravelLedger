package com.playdata.calen.travel.fixture;

import com.playdata.calen.travel.adapter.support.TravelOwnedPlansMapper;
import com.playdata.calen.ledger.adapter.support.LedgerTravelMapper;
import com.playdata.calen.travel.adapter.support.TravelLedgerReflectionMapper;
import com.playdata.calen.travel.adapter.support.TravelAtlasMapper;
import com.playdata.calen.travel.adapter.support.TravelMapLinkMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelMediaUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelSharingMapper;
import com.playdata.calen.travel.adapter.support.TravelExhibitMapper;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase;
import com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.travel.adapter.support.TravelBudgetMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.domain.policy.TravelMoneyPolicy;
import com.playdata.calen.travel.adapter.support.TravelPlanMapper;
import com.playdata.calen.travel.adapter.support.TravelRecordMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.domain.TravelBudgetItem;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.dto.TravelCategoryCatalogResponse;
import com.playdata.calen.travel.dto.TravelCommunityFeedPageResponse;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.dto.TravelExpenseRecordRequest;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailBundleResponse;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterPageResponse;
import com.playdata.calen.travel.dto.TravelMemoryRecordRequest;
import com.playdata.calen.travel.dto.TravelMemoryRecordResponse;
import com.playdata.calen.travel.dto.TravelMapShareLinkRequest;
import com.playdata.calen.travel.dto.TravelMapShareLinkResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.TravelPhotoFrameMediaResponse;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import com.playdata.calen.travel.dto.TravelPlanPublicShareResponse;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanShareResponse;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.dto.TravelPortfolioResponse;
import com.playdata.calen.travel.dto.TravelPublicMapShareResponse;
import com.playdata.calen.travel.dto.TravelPublicTripsOverviewResponse;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadAbortRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelRouteSegmentRequest;
import com.playdata.calen.travel.dto.TravelRouteSegmentResponse;
import com.playdata.calen.travel.dto.TravelShareGroupRequest;
import com.playdata.calen.travel.dto.TravelShareGroupResponse;
import com.playdata.calen.travel.dto.TravelShareRecipientResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Frozen catalog and currency request behavior before reference-data extraction. */
@RequiredArgsConstructor
public class TravelReferenceLegacyOracle {
    private static final String KRW = "KRW";
    private static final List<String> DEFAULT_CURRENCIES = List.of("KRW", "USD", "JPY", "CNY", "EUR");
    private static final List<String> DEFAULT_BUDGET_CATEGORIES = List.of(
            "Flight",
            "Stay",
            "Transport",
            "Food",
            "Shopping",
            "Ticket",
            "Insurance",
            "Etc"
    );
    private static final List<String> DEFAULT_MEMORY_CATEGORIES = List.of(
            "Spot",
            "Cafe",
            "View",
            "Meal",
            "Hotel",
            "Transit",
            "Shopping"
    );
    private final AppUserService appUserService;
    private final TravelBudgetItemRepository travelBudgetItemRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final com.playdata.calen.travel.service.ExchangeRateService exchangeRateService;

    public TravelCategoryCatalogResponse getCategoryCatalog(Long userId) {
        appUserService.getRequiredUser(userId);

        List<TravelBudgetItem> budgetItems = travelBudgetItemRepository.findAllByPlanOwnerId(userId);
        List<TravelExpenseRecord> records = travelExpenseRecordRepository.findAllByPlanOwnerId(userId);

        List<String> budgetCategories = distinctSorted(
                DEFAULT_BUDGET_CATEGORIES.stream(),
                budgetItems.stream().map(TravelBudgetItem::getCategory)
        );
        List<String> expenseCategories = distinctSorted(
                DEFAULT_BUDGET_CATEGORIES.stream(),
                records.stream().filter(this::isLedgerRecord).map(TravelExpenseRecord::getCategory)
        );
        List<String> memoryCategories = distinctSorted(
                DEFAULT_MEMORY_CATEGORIES.stream(),
                records.stream().filter(this::isMemoryRecord).map(TravelExpenseRecord::getCategory)
        );

        return new TravelCategoryCatalogResponse(
                List.of(TravelPlanStatus.PLANNED.name(), TravelPlanStatus.COMPLETED.name(), TravelPlanStatus.SAMPLE.name()),
                budgetCategories,
                expenseCategories,
                memoryCategories,
                distinctSorted(records.stream().map(TravelExpenseRecord::getCountry)),
                distinctSorted(records.stream().map(TravelExpenseRecord::getRegion)),
                distinctSorted(records.stream().map(TravelExpenseRecord::getPlaceName))
        );
    }

    public List<TravelExchangeRateResponse> getExchangeRates(Long userId, String currencies) {
        appUserService.getRequiredUser(userId);
        Set<String> codes = new LinkedHashSet<>();
        if (currencies == null || currencies.isBlank()) {
            codes.addAll(DEFAULT_CURRENCIES);
        } else {
            for (String currency : currencies.split(",")) {
                codes.add(normalizeCurrencyCode(currency));
            }
            codes.add(KRW);
        }
        return exchangeRateService.getLatestRates(codes);
    }

    @SafeVarargs
    private final List<String> distinctSorted(java.util.stream.Stream<String>... streams) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (java.util.stream.Stream<String> stream : streams) {
            stream.map(this::trimToNull)
                    .filter(value -> value != null)
                    .sorted(String::compareToIgnoreCase)
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private String normalizeCurrencyCode(String value) {
        return TravelMoneyPolicy.currency(value);
    }

    private boolean isLedgerRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.LEDGER);
    }

    private boolean isMemoryRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
