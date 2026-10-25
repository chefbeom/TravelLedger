package com.playdata.calen.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesResponse;
import com.playdata.calen.account.dto.HouseholdPersonalizationRequest;
import com.playdata.calen.account.dto.HouseholdPersonalizationResponse;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.PreferenceConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One atomic edit session over the existing user-scoped stores; no duplicate settings table. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseholdPersonalizationService {
    private final AppUserRepository users;
    private final HouseholdAggregatePreferenceService aggregates;
    private final UserLayoutSettingService layouts;
    private final ObjectMapper mapper;

    public HouseholdPersonalizationResponse get(Long ownerId) {
        var preferences = layouts.getLayoutSetting(ownerId, "household-calendar-view").payload();
        var layout = layouts.getLayoutSetting(ownerId, "household-calendar").payload();
        var widgets = aggregates.getPreferences(ownerId).widgets();
        try {
            var state = mapper.createObjectNode();
            state.put("ownerId", ownerId);
            state.set("preferences", preferences);
            state.set("layout", layout);
            state.set("widgets", mapper.valueToTree(widgets));
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(state).getBytes(StandardCharsets.UTF_8)));
            return new HouseholdPersonalizationResponse(revision, preferences, layout, widgets);
        } catch (Exception failure) {
            throw new IllegalStateException("개인 설정 상태를 확인하지 못했습니다.", failure);
        }
    }

    @Transactional
    public HouseholdPersonalizationResponse save(Long ownerId, HouseholdPersonalizationRequest request) {
        if (request == null || request.revision() == null || request.preferences() == null || !request.preferences().isObject()
                || request.layout() == null || !request.layout().isArray() || request.widgets() == null || request.widgets().size() != 6)
            throw new BadRequestException("개인 설정 형식을 확인해 주세요.");
        validateLayout(request.layout());
        validatePreferences(request.preferences());
        for (var widget : request.widgets()) {
            if (widget == null || widget.kind() == null || !Set.of("NONE", "TOTAL", "PAYMENT_METHOD", "MONTHLY_CUMULATIVE_CHART", "MONTHLY_GOAL").contains(widget.kind())
                    || widget.period() == null || !Set.of("YEAR", "QUARTER", "MONTH", "WEEK", "DAY").contains(widget.period())
                    || widget.amountType() == null || !Set.of("NET", "INCOME", "EXPENSE").contains(widget.amountType())
                    || (widget.title() != null && widget.title().length() > 80)
                    || (widget.displaySize() != null && !Set.of("SMALL", "MEDIUM", "LARGE").contains(widget.displaySize())))
                throw new BadRequestException("위젯 설정값을 확인해 주세요.");
            if ((widget.layoutW() != null && (widget.layoutW() < 1 || widget.layoutW() > 4))
                    || (widget.layoutH() != null && widget.layoutH() != 1)
                    || (widget.layoutX() != null && (widget.layoutX() < 1 || widget.layoutX() > 8))
                    || (widget.monthlyExpenseTarget() != null && widget.monthlyExpenseTarget() < 0)
                    || (widget.singleExpenseLimit() != null && widget.singleExpenseLimit() < 0)
                    || (widget.chartMaxAmount() != null && widget.chartMaxAmount() < 0)
                    || (widget.cardWidth() != null && widget.cardWidth() != 0 && (widget.cardWidth() < 200 || widget.cardWidth() > 1200))
                    || (widget.cardHeight() != null && widget.cardHeight() != 0 && (widget.cardHeight() < 160 || widget.cardHeight() > 800))
                    || (widget.cardColumns() != null && widget.cardColumns() != 0 && (widget.cardColumns() < ("MONTHLY_CUMULATIVE_CHART".equals(widget.kind()) ? 2 : 1) || widget.cardColumns() > 9))
                    || (widget.cardRows() != null && widget.cardRows() != 0 && (widget.cardRows() < 1 || widget.cardRows() > 12)))
                throw new BadRequestException("위젯 크기와 금액 범위를 확인해 주세요.");
        }
        lockOwner(ownerId);
        if (!get(ownerId).revision().equals(request.revision())) throw new PreferenceConflictException();
        aggregates.savePreferences(ownerId, new HouseholdAggregatePreferencesRequest(request.widgets()));
        layouts.saveLayoutSetting(ownerId, "household-calendar", new UserLayoutSettingRequest(3, request.layout()));
        layouts.saveLayoutSetting(ownerId, "household-calendar-view", new UserLayoutSettingRequest(3, request.preferences()));
        return get(ownerId);
    }

    @Transactional
    public HouseholdAggregatePreferencesResponse saveCompatibleWidgets(Long ownerId, HouseholdAggregatePreferencesRequest request) {
        lockOwner(ownerId);
        return aggregates.savePreferences(ownerId, request);
    }

    @Transactional
    public UserLayoutSettingResponse saveCompatibleLayout(Long ownerId, String scope, UserLayoutSettingRequest request) {
        lockOwner(ownerId);
        if ("household-calendar-view".equals(scope) && request != null && request.payload() instanceof ObjectNode incoming) {
            JsonNode current = layouts.getLayoutSetting(ownerId, scope).payload();
            ObjectNode merged = incoming.deepCopy();
            // A legacy full-document PUT cannot know the new editor's optional preferences.
            if (current != null) for (String key : Set.of("quickEntryPanelEnabled", "defaultWeekMode")) {
                if (!merged.has(key) && current.has(key)) merged.set(key, current.get(key));
            }
            request = new UserLayoutSettingRequest(request.version(), merged);
        }
        return layouts.saveLayoutSetting(ownerId, scope, request);
    }

    private void lockOwner(Long ownerId) {
        users.findActiveForPreferenceUpdate(ownerId).orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
    }

    private void validateLayout(JsonNode layout) {
        var ids = new HashSet<String>();
        for (var panel : layout) {
            String id = panel.path("id").asText();
            if (!Set.of("aggregate", "calendar", "quick-entry", "sheet").contains(id) || !ids.add(id))
                throw new BadRequestException("위젯 배치 구성이 올바르지 않습니다.");
            for (String key : Set.of("x", "y", "w", "h")) if (!panel.path(key).isIntegralNumber() || !panel.path(key).canConvertToInt())
                throw new BadRequestException("배치 위치와 크기는 정수여야 합니다.");
            int x = panel.path("x").asInt(), y = panel.path("y").asInt(), w = panel.path("w").asInt(), h = panel.path("h").asInt();
            if (x < 0 || y < 0 || y > 200 || w < 1 || w > 9 || x + w > 9 || h < 1 || h > 12)
                throw new BadRequestException("위젯 배치 범위를 확인해 주세요.");
        }
        if (ids.size() != 4) throw new BadRequestException("기본 위젯 배치 정보가 누락되었습니다.");
    }

    private void validatePreferences(JsonNode preferences) {
        var choices = Map.of("scalePreset", Set.of("default", "fit"), "highlightMode", Set.of("net", "expense", "income"),
                "transactionSheetViewMode", Set.of("inline", "modal"), "defaultWeekMode", Set.of("month", "current", "previous"));
        for (var choice : choices.entrySet()) if (!choice.getValue().contains(preferences.path(choice.getKey()).asText()))
            throw new BadRequestException("달력 표시 설정값을 확인해 주세요.");
        for (String key : Set.of("aggregatePanelEnabled", "receiptOcrPanelEnabled", "quickEntryPanelEnabled", "shareAfterSaveEnabled"))
            if (!preferences.path(key).isBoolean()) throw new BadRequestException("표시 여부는 켜짐/꺼짐으로 선택해 주세요.");
    }
}
