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
        JsonNode layout = preserveCalendarQuery(ownerId, request.layout());
        validateLayout(layout);
        layouts.saveLayoutSetting(ownerId, "household-calendar", new UserLayoutSettingRequest(4, layout));
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
        if ("household-calendar".equals(scope) && request != null && request.payload() != null && request.payload().isArray()) {
            request = new UserLayoutSettingRequest(request.version(), preserveCalendarQuery(ownerId, request.payload()));
        }
        if ("household-calendar-view".equals(scope) && request != null && request.payload() instanceof ObjectNode incoming) {
            JsonNode current = layouts.getLayoutSetting(ownerId, scope).payload();
            ObjectNode merged = incoming.deepCopy();
            // A legacy full-document PUT cannot know the new editor's optional preferences.
            if (current != null) for (String key : Set.of("quickEntryPanelEnabled", "defaultWeekMode")) {
                if (!merged.has(key) && current.has(key)) merged.set(key, current.get(key));
            }
            request = new UserLayoutSettingRequest(request.version(), merged);
        }
        if ("main-dashboard".equals(scope) && request != null && request.payload() instanceof ObjectNode incoming) {
            JsonNode current = layouts.getLayoutSetting(ownerId, scope).payload();
            ObjectNode merged = incoming.deepCopy();
            JsonNode sectionNode = merged.remove("_layoutSection");
            String section = sectionNode == null ? "" : sectionNode.asText();
            if (!section.isEmpty() && !Set.of("desktop", "1", "3").contains(section)) {
                throw new BadRequestException("대시보드 배치 구분을 확인해 주세요.");
            }
            if (Set.of("1", "3").contains(section)) {
                JsonNode submitted = incoming.path("compactLayouts").path(section);
                if (!submitted.isArray()) throw new BadRequestException("모바일 대시보드 배치를 확인해 주세요.");
                if (current instanceof ObjectNode existing) {
                    // A stale mobile tab must never replace the current desktop or other mobile profile.
                    merged = existing.deepCopy();
                    ObjectNode compact = merged.path("compactLayouts") instanceof ObjectNode object
                            ? object.deepCopy() : mapper.createObjectNode();
                    compact.set(section, submitted.deepCopy());
                    merged.set("compactLayouts", compact);
                }
            } else if (current != null && current.has("compactLayouts") && ("desktop".equals(section) || !incoming.has("compactLayouts"))) {
                // Old clients have no compact profiles; desktop edits must keep the latest profiles too.
                merged.set("compactLayouts", current.get("compactLayouts").deepCopy());
            }
            request = new UserLayoutSettingRequest(request.version(), merged);
        }
        return layouts.saveLayoutSetting(ownerId, scope, request);
    }

    private void lockOwner(Long ownerId) {
        users.findActiveForPreferenceUpdate(ownerId).orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
    }

    private JsonNode preserveCalendarQuery(Long ownerId, JsonNode incoming) {
        var merged = (com.fasterxml.jackson.databind.node.ArrayNode) incoming.deepCopy();
        JsonNode current = layouts.getLayoutSetting(ownerId, "household-calendar").payload();
        if (current != null && current.isArray()) {
            for (var existing : current) {
                String id = existing.path("id").asText();
                ObjectNode matching = null;
                for (var candidate : merged) if (id.equals(candidate.path("id").asText()) && candidate instanceof ObjectNode object) {
                    matching = object;
                    break;
                }
                if (matching == null && "calendar-query".equals(id)) {
                    merged.add(existing.deepCopy());
                } else if (matching != null) {
                    // Older clients do not know these fields. Missing means
                    // preserve; an explicit null intentionally clears them.
                    for (String key : Set.of("mobileOrder", "mobileHeight", "mobileWidgetOrder", "mobileWidgetRows")) {
                        if (!matching.has(key) && existing.has(key)) matching.set(key, existing.get(key).deepCopy());
                    }
                }
            }
        }
        return merged;
    }

    private void validateLayout(JsonNode layout) {
        var ids = new HashSet<String>();
        for (var panel : layout) {
            String id = panel.path("id").asText();
            if (!Set.of("aggregate", "calendar", "quick-entry", "sheet", "calendar-query").contains(id) || !ids.add(id))
                throw new BadRequestException("위젯 배치 구성이 올바르지 않습니다.");
            for (String key : Set.of("x", "y", "w", "h")) if (!panel.path(key).isIntegralNumber() || !panel.path(key).canConvertToInt())
                throw new BadRequestException("배치 위치와 크기는 정수여야 합니다.");
            int x = panel.path("x").asInt(), y = panel.path("y").asInt(), w = panel.path("w").asInt(), h = panel.path("h").asInt();
            if (x < 0 || y < 0 || y > 200 || w < 1 || w > 9 || x + w > 9 || h < 1 || h > 12)
                throw new BadRequestException("위젯 배치 범위를 확인해 주세요.");
            if ("calendar-query".equals(id) && (x != 0 || w != 9))
                throw new BadRequestException("날짜 조회 영역은 전체 9열을 사용해야 합니다.");
            for (String key : Set.of("mobileOrder", "mobileHeight")) {
                JsonNode value = panel.get(key);
                if (value == null || value.isNull()) continue;
                int minimum = "mobileHeight".equals(key) ? 1 : 0;
                int maximum = "mobileHeight".equals(key) ? 12 : 200;
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum)
                    throw new BadRequestException("모바일 배치 범위를 확인해 주세요.");
            }
            for (String key : Set.of("mobileWidgetOrder", "mobileWidgetRows")) {
                JsonNode values = panel.get(key);
                if (values == null || values.isNull()) continue;
                if (!"aggregate".equals(id) || !values.isArray() || values.size() != 6)
                    throw new BadRequestException("모바일 위젯 배치를 확인해 주세요.");
                Set<Integer> seen = new HashSet<>();
                for (JsonNode value : values) {
                    int max = "mobileWidgetOrder".equals(key) ? 5 : 12;
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0 || value.asInt() > max
                            || ("mobileWidgetOrder".equals(key) && !seen.add(value.asInt())))
                        throw new BadRequestException("모바일 위젯 배치를 확인해 주세요.");
                }
            }
        }
        if (!ids.containsAll(Set.of("aggregate", "calendar", "quick-entry", "sheet"))) throw new BadRequestException("기본 위젯 배치 정보가 누락되었습니다.");
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
