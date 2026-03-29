package com.playdata.calen.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesResponse;
import com.playdata.calen.account.dto.HouseholdAggregateWidgetRequest;
import com.playdata.calen.account.dto.HouseholdAggregateWidgetResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseholdAggregatePreferenceService {

    private static final int MAX_WIDGETS = 4;
    private static final Set<String> ALLOWED_KINDS = Set.of("TOTAL", "PAYMENT_METHOD");
    private static final Set<String> ALLOWED_PERIODS = Set.of("MONTH", "WEEK", "DAY");

    private final AppUserRepository appUserRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ObjectMapper objectMapper;

    public HouseholdAggregatePreferencesResponse getPreferences(Long userId) {
        AppUser user = getRequiredActiveUser(userId);
        return toResponse(normalizeWidgets(readWidgets(user.getHouseholdAggregateSettingsJson()), userId));
    }

    @Transactional
    public HouseholdAggregatePreferencesResponse savePreferences(
            Long userId,
            HouseholdAggregatePreferencesRequest request
    ) {
        AppUser user = getRequiredActiveUser(userId);
        List<StoredWidget> normalizedWidgets = normalizeWidgets(mapRequestWidgets(request), userId);
        user.setHouseholdAggregateSettingsJson(writeWidgets(normalizedWidgets));
        return toResponse(normalizedWidgets);
    }

    private AppUser getRequiredActiveUser(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
    }

    private List<StoredWidget> mapRequestWidgets(HouseholdAggregatePreferencesRequest request) {
        if (request == null || request.widgets() == null || request.widgets().isEmpty()) {
            return List.of();
        }

        if (request.widgets().size() > MAX_WIDGETS) {
            throw new BadRequestException("사용자 설정 집계는 최대 4개까지만 저장할 수 있습니다.");
        }

        return request.widgets().stream()
                .map(this::toStoredWidget)
                .toList();
    }

    private StoredWidget toStoredWidget(HouseholdAggregateWidgetRequest widget) {
        if (widget == null) {
            return new StoredWidget(null, null, null);
        }

        return new StoredWidget(widget.kind(), widget.period(), widget.paymentMethodId());
    }

    private List<StoredWidget> readWidgets(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }

        try {
            StoredWidget[] widgets = objectMapper.readValue(rawJson, StoredWidget[].class);
            return List.of(widgets);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String writeWidgets(List<StoredWidget> widgets) {
        try {
            return objectMapper.writeValueAsString(widgets);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("사용자 설정 집계를 저장하지 못했습니다.", exception);
        }
    }

    private HouseholdAggregatePreferencesResponse toResponse(List<StoredWidget> widgets) {
        return new HouseholdAggregatePreferencesResponse(
                widgets.stream()
                        .map(widget -> new HouseholdAggregateWidgetResponse(
                                widget.kind(),
                                widget.period(),
                                widget.paymentMethodId()
                        ))
                        .toList()
        );
    }

    private List<StoredWidget> normalizeWidgets(List<StoredWidget> widgets, Long userId) {
        List<Long> validPaymentMethodIds = paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(userId)
                .stream()
                .map(PaymentMethod::getId)
                .toList();
        Long fallbackPaymentMethodId = validPaymentMethodIds.isEmpty() ? null : validPaymentMethodIds.get(0);
        List<StoredWidget> defaultWidgets = buildDefaultWidgets(fallbackPaymentMethodId);
        List<StoredWidget> normalizedWidgets = new ArrayList<>(MAX_WIDGETS);

        for (int index = 0; index < MAX_WIDGETS; index++) {
            StoredWidget baseWidget = defaultWidgets.get(index);
            StoredWidget requestedWidget = index < widgets.size() ? widgets.get(index) : baseWidget;
            String kind = ALLOWED_KINDS.contains(requestedWidget.kind()) ? requestedWidget.kind() : baseWidget.kind();
            String period = ALLOWED_PERIODS.contains(requestedWidget.period()) ? requestedWidget.period() : baseWidget.period();
            Long paymentMethodId = null;

            if ("PAYMENT_METHOD".equals(kind)) {
                Long candidatePaymentMethodId = requestedWidget.paymentMethodId();
                paymentMethodId = validPaymentMethodIds.contains(candidatePaymentMethodId)
                        ? candidatePaymentMethodId
                        : fallbackPaymentMethodId;
            }

            normalizedWidgets.add(new StoredWidget(kind, period, paymentMethodId));
        }

        return normalizedWidgets;
    }

    private List<StoredWidget> buildDefaultWidgets(Long defaultPaymentMethodId) {
        return List.of(
                new StoredWidget("TOTAL", "MONTH", null),
                new StoredWidget("TOTAL", "WEEK", null),
                new StoredWidget("TOTAL", "DAY", null),
                new StoredWidget("PAYMENT_METHOD", "MONTH", defaultPaymentMethodId)
        );
    }

    private record StoredWidget(
            String kind,
            String period,
            Long paymentMethodId
    ) {
    }
}
