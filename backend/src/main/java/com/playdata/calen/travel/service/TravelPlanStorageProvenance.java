package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelPlan;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates exact source paths; a former owner is accepted only if a transfer recorded it. */
@Component
@RequiredArgsConstructor
public class TravelPlanStorageProvenance {
    private static final int MAX_FORMER_OWNERS = 1024;
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-fA-F-]{36}-[a-zA-Z0-9._-]+");
    private final ObjectMapper json;
    @Value("${app.travel.media-object-prefix:travel-media}") private String configuredPrefix;

    public Set<Long> allowedOrigins(TravelPlan plan) {
        if (plan == null || plan.getId() == null || plan.getId() <= 0 || plan.getOwner() == null
                || plan.getOwner().getId() == null || plan.getOwner().getId() <= 0) throw invalid();
        Set<Long> result = new LinkedHashSet<>();
        String history = plan.getStorageOwnerHistoryJson();
        if (history != null && !history.isBlank()) {
            JsonNode node;
            try { node = json.readTree(history); }
            catch (JsonProcessingException failure) { throw invalid(); }
            if (node == null || !node.isArray() || node.size() > MAX_FORMER_OWNERS) throw invalid();
            for (JsonNode item : node) {
                if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() <= 0
                        || !result.add(item.longValue())) throw invalid();
            }
        }
        result.add(plan.getOwner().getId());
        return Set.copyOf(result);
    }

    /** Invoke in the same transaction immediately before changing the plan owner. */
    public void rememberCurrentOwner(TravelPlan plan) {
        Set<Long> origins = allowedOrigins(plan);
        if (origins.size() > MAX_FORMER_OWNERS) throw invalid();
        try { plan.setStorageOwnerHistoryJson(json.writeValueAsString(origins.stream().sorted().toList())); }
        catch (JsonProcessingException failure) { throw invalid(); }
    }

    public long requirePath(TravelPlan plan, Long source, boolean route, String path,
            String storedName, String contentType, Long size) {
        if (source == null || source <= 0 || path == null || path.length() > 600
                || contentType == null || contentType.isBlank() || contentType.length() > 120
                || contentType.chars().anyMatch(Character::isISOControl) || size == null || size < 0) throw invalid();
        String prefix = normalizedPrefix() + "/";
        String local = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        for (Long origin : allowedOrigins(plan)) {
            String relative = origin + "/" + plan.getId() + "/" + (route ? "routes/" : "") + source + "/";
            if (!local.startsWith(relative)) continue;
            String file = local.substring(relative.length());
            if (!FILE_NAME.matcher(file).matches() || (storedName != null && !storedName.equals(file))) throw invalid();
            try {
                if (!UUID.fromString(file.substring(0, 36)).toString().equalsIgnoreCase(file.substring(0, 36))) throw invalid();
            } catch (IllegalArgumentException failure) { throw invalid(); }
            return origin;
        }
        throw invalid();
    }

    private String normalizedPrefix() {
        String prefix = configuredPrefix == null ? "" : configuredPrefix.trim().replace('\\', '/')
                .replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        if (prefix.isEmpty()) prefix = "travel-media";
        for (String part : prefix.split("/")) if (part.equals(".") || part.equals("..")) throw invalid();
        if (prefix.chars().anyMatch(Character::isISOControl)) throw invalid();
        return prefix;
    }

    private BadRequestException invalid() {
        return new BadRequestException("여행 파일의 소유권 또는 경로를 안전하게 확인할 수 없습니다.");
    }
}
