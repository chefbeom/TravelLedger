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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseholdAggregatePreferenceService {

    private static final int MAX_WIDGETS = 6;
    private static final Set<String> ALLOWED_KINDS = Set.of("NONE", "TOTAL", "PAYMENT_METHOD", "MONTHLY_CUMULATIVE_CHART", "MONTHLY_GOAL");
    private static final Set<String> ALLOWED_PERIODS = Set.of("YEAR", "QUARTER", "MONTH", "WEEK", "DAY");
    private static final Set<String> ALLOWED_AMOUNT_TYPES = Set.of("NET", "INCOME", "EXPENSE");

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
            throw new BadRequestException("사용자 설정 집계는 최대 6개까지 저장할 수 있습니다.");
        }

        return request.widgets().stream()
                .map(this::toStoredWidget)
                .toList();
    }

    private StoredWidget toStoredWidget(HouseholdAggregateWidgetRequest widget) {
        if (widget == null) {
            return new StoredWidget(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        return new StoredWidget(
                widget.kind(),
                widget.period(),
                widget.paymentMethodId(),
                widget.amountType(),
                widget.monthlyExpenseTarget(),
                widget.singleExpenseLimit(),
                widget.showIncomeCumulative(),
                widget.showExpenseCumulative(),
                widget.comparePreviousPeriod(),
                widget.layoutX(),
                widget.layoutY(),
                widget.layoutW(),
                widget.layoutH(),
                widget.layoutOrder()
        );
    }

    private List<StoredWidget> readWidgets(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }

        try {
            StoredWidget[] widgets = objectMapper.readValue(rawJson, StoredWidget[].class);
            if (widgets == null || widgets.length == 0) {
                return List.of();
            }
            return Arrays.stream(widgets)
                    .filter(Objects::nonNull)
                    .toList();
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
                                widget.paymentMethodId(),
                                widget.amountType(),
                                widget.monthlyExpenseTarget(),
                                widget.singleExpenseLimit(),
                                widget.showIncomeCumulative(),
                                widget.showExpenseCumulative(),
                                widget.comparePreviousPeriod(),
                                widget.layoutX(),
                                widget.layoutY(),
                                widget.layoutW(),
                                widget.layoutH(),
                                widget.layoutOrder()
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
        List<StoredWidget> defaultWidgets = buildDefaultWidgets();
        List<StoredWidget> normalizedWidgets = new ArrayList<>(MAX_WIDGETS);

        for (int index = 0; index < MAX_WIDGETS; index++) {
            StoredWidget baseWidget = defaultWidgets.get(index);
            StoredWidget requestedWidget = index < widgets.size() && widgets.get(index) != null ? widgets.get(index) : baseWidget;
            String requestedKind = requestedWidget.kind();
            String requestedPeriod = requestedWidget.period();
            String requestedAmountType = requestedWidget.amountType();
            String kind = requestedKind != null && ALLOWED_KINDS.contains(requestedKind) ? requestedKind : baseWidget.kind();
            String period = requestedPeriod != null && ALLOWED_PERIODS.contains(requestedPeriod) ? requestedPeriod : baseWidget.period();
            String amountType = requestedAmountType != null && ALLOWED_AMOUNT_TYPES.contains(requestedAmountType)
                    ? requestedAmountType
                    : baseWidget.amountType();
            Long paymentMethodId = null;
            Long monthlyExpenseTarget = normalizePositiveAmount(requestedWidget.monthlyExpenseTarget());
            Long singleExpenseLimit = normalizePositiveAmount(requestedWidget.singleExpenseLimit());
            Boolean showIncomeCumulative = normalizeBoolean(requestedWidget.showIncomeCumulative(), baseWidget.showIncomeCumulative());
            Boolean showExpenseCumulative = normalizeBoolean(requestedWidget.showExpenseCumulative(), baseWidget.showExpenseCumulative());
            Boolean comparePreviousPeriod = normalizeBoolean(requestedWidget.comparePreviousPeriod(), baseWidget.comparePreviousPeriod());
            Integer layoutW = normalizeGridSpan(requestedWidget.layoutW(), baseWidget.layoutW(), 8);
            Integer layoutX = normalizeGridPosition(requestedWidget.layoutX(), baseWidget.layoutX(), Math.max(1, 9 - layoutW));
            Integer layoutH = normalizeGridSpan(requestedWidget.layoutH(), baseWidget.layoutH(), 1);
            Integer layoutY = normalizeGridPosition(requestedWidget.layoutY(), baseWidget.layoutY(), 1);
            Integer layoutOrder = normalizeLayoutOrder(requestedWidget.layoutOrder(), baseWidget.layoutOrder() != null ? baseWidget.layoutOrder() : index + 1);

            if ("PAYMENT_METHOD".equals(kind)) {
                Long candidatePaymentMethodId = requestedWidget.paymentMethodId();
                paymentMethodId = validPaymentMethodIds.contains(candidatePaymentMethodId)
                        ? candidatePaymentMethodId
                        : fallbackPaymentMethodId;
            }

            if ("MONTHLY_CUMULATIVE_CHART".equals(kind)) {
                amountType = "NET";
                paymentMethodId = null;
                monthlyExpenseTarget = null;
                singleExpenseLimit = null;
                if (!Boolean.TRUE.equals(showIncomeCumulative) && !Boolean.TRUE.equals(showExpenseCumulative)) {
                    showExpenseCumulative = true;
                }
                layoutW = Math.min(4, Math.max(2, normalizeGridSpan(layoutW, 2, 4)));
                layoutX = normalizeGridPosition(layoutX, 1, Math.max(1, 9 - layoutW));
                layoutH = 1;
                layoutY = 1;
            } else {
                layoutW = 1;
                layoutH = 1;
                layoutY = 1;
            }

            if ("MONTHLY_GOAL".equals(kind)) {
                period = "MONTH";
                amountType = "EXPENSE";
                paymentMethodId = null;
            } else {
                monthlyExpenseTarget = null;
                singleExpenseLimit = null;
            }

            if ("NONE".equals(kind)) {
                period = "MONTH";
                amountType = "EXPENSE";
                paymentMethodId = null;
            }

            layoutX = normalizeGridPosition(layoutX, baseWidget.layoutX(), Math.max(1, 9 - layoutW));
            layoutY = normalizeGridPosition(layoutY, baseWidget.layoutY(), 1);

            if (!"MONTHLY_CUMULATIVE_CHART".equals(kind)) {
                showIncomeCumulative = null;
                showExpenseCumulative = null;
                comparePreviousPeriod = null;
            }

            normalizedWidgets.add(new StoredWidget(
                    kind,
                    period,
                    paymentMethodId,
                    amountType,
                    monthlyExpenseTarget,
                    singleExpenseLimit,
                    showIncomeCumulative,
                    showExpenseCumulative,
                    comparePreviousPeriod,
                    layoutX,
                    layoutY,
                    layoutW,
                    layoutH,
                    layoutOrder
            ));
        }

        return packNormalizedWidgets(normalizedWidgets);
    }


    private List<StoredWidget> packNormalizedWidgets(List<StoredWidget> widgets) {
        boolean[][] occupied = new boolean[2][9];
        StoredWidget[] packedWidgets = widgets.toArray(new StoredWidget[0]);
        List<Integer> orderedIndexes = new ArrayList<>();
        for (int index = 0; index < packedWidgets.length; index++) {
            orderedIndexes.add(index);
        }
        orderedIndexes.sort((left, right) -> {
            int leftOrder = normalizeLayoutOrder(packedWidgets[left].layoutOrder(), left + 1);
            int rightOrder = normalizeLayoutOrder(packedWidgets[right].layoutOrder(), right + 1);
            if (leftOrder != rightOrder) {
                return Integer.compare(leftOrder, rightOrder);
            }
            return Integer.compare(left, right);
        });

        for (Integer index : orderedIndexes) {
            StoredWidget widget = packedWidgets[index];
            int width = normalizeGridSpan(widget.layoutW(), 1, 8);
            int height = 1;
            int preferredColumn = normalizeGridPosition(widget.layoutX(), 1, Math.max(1, 9 - width));
            int preferredRow = normalizeGridPosition(widget.layoutY(), 1, 1);
            int[] slot = findGridSlot(occupied, preferredColumn, preferredRow, width, height);
            reserveGridSlot(occupied, slot[0], slot[1], width, height);
            packedWidgets[index] = new StoredWidget(
                    widget.kind(),
                    widget.period(),
                    widget.paymentMethodId(),
                    widget.amountType(),
                    widget.monthlyExpenseTarget(),
                    widget.singleExpenseLimit(),
                    widget.showIncomeCumulative(),
                    widget.showExpenseCumulative(),
                    widget.comparePreviousPeriod(),
                    slot[0],
                    slot[1],
                    width,
                    height,
                    normalizeLayoutOrder(widget.layoutOrder(), index + 1)
            );
        }

        return Arrays.asList(packedWidgets);
    }

    private int[] findGridSlot(boolean[][] occupied, int preferredColumn, int preferredRow, int width, int height) {
        if (isGridSlotFree(occupied, preferredColumn, preferredRow, width, height)) {
            return new int[]{preferredColumn, preferredRow};
        }
        int maxColumn = Math.max(1, 9 - width);
        int maxRow = 1;
        for (int row = 1; row <= maxRow; row++) {
            for (int column = 1; column <= maxColumn; column++) {
                if (isGridSlotFree(occupied, column, row, width, height)) {
                    return new int[]{column, row};
                }
            }
        }
        return new int[]{preferredColumn, preferredRow};
    }

    private boolean isGridSlotFree(boolean[][] occupied, int column, int row, int width, int height) {
        for (int y = row; y < row + height; y++) {
            for (int x = column; x < column + width; x++) {
                if (occupied[y][x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private void reserveGridSlot(boolean[][] occupied, int column, int row, int width, int height) {
        for (int y = row; y < row + height; y++) {
            for (int x = column; x < column + width; x++) {
                occupied[y][x] = true;
            }
        }
    }

    private Long normalizePositiveAmount(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Integer normalizeGridPosition(Integer value, Integer fallback, int max) {
        int candidate = value != null ? value : (fallback != null ? fallback : 1);
        return Math.min(max, Math.max(1, candidate));
    }

    private Integer normalizeGridSpan(Integer value, Integer fallback, int max) {
        int candidate = value != null ? value : (fallback != null ? fallback : 2);
        return Math.min(max, Math.max(1, candidate));
    }

    private Integer normalizeLayoutOrder(Integer value, Integer fallback) {
        int candidate = value != null ? value : (fallback != null ? fallback : 1);
        return Math.min(99, Math.max(1, candidate));
    }

    private Boolean normalizeBoolean(Boolean value, Boolean fallback) {
        return value != null ? value : Boolean.TRUE.equals(fallback);
    }

    private List<StoredWidget> buildDefaultWidgets() {
        return List.of(
                new StoredWidget("TOTAL", "MONTH", null, "EXPENSE", null, null, null, null, null, 1, 1, 1, 1, 1),
                new StoredWidget("MONTHLY_GOAL", "MONTH", null, "EXPENSE", null, null, null, null, null, 2, 1, 1, 1, 2),
                new StoredWidget("PAYMENT_METHOD", "MONTH", null, "EXPENSE", null, null, null, null, null, 3, 1, 1, 1, 3),
                new StoredWidget("MONTHLY_CUMULATIVE_CHART", "MONTH", null, "NET", null, null, true, true, false, 4, 1, 2, 1, 4),
                new StoredWidget("NONE", "MONTH", null, "EXPENSE", null, null, null, null, null, 6, 1, 1, 1, 5),
                new StoredWidget("NONE", "MONTH", null, "EXPENSE", null, null, null, null, null, 7, 1, 1, 1, 6)
        );
    }

    private record StoredWidget(
            String kind,
            String period,
            Long paymentMethodId,
            String amountType,
            Long monthlyExpenseTarget,
            Long singleExpenseLimit,
            Boolean showIncomeCumulative,
            Boolean showExpenseCumulative,
            Boolean comparePreviousPeriod,
            Integer layoutX,
            Integer layoutY,
            Integer layoutW,
            Integer layoutH,
            Integer layoutOrder
    ) {
    }
}