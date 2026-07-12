package com.playdata.calen.ledger.ai;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public final class LedgerAiRemoteResponseValidator {

    private static final int MAX_TEXT_VALUE_LENGTH = 2000;
    private static final int MAX_COLLECTION_SIZE = 20;
    private static final String EMPTY_RESPONSE_MESSAGE = " AI analysis response was empty.";
    private static final String PROVIDER_FAILURE_MESSAGE = " AI analysis request failed.";
    private static final String UNUSABLE_RESPONSE_MESSAGE = " AI analysis response did not contain usable analysis content.";
    private static final String SECRET_LIKE_MESSAGE = " AI analysis response contained secret-like content.";
    private static final String PROMPT_INJECTION_MESSAGE = " AI analysis response echoed prompt-injection instructions.";
    private static final String MUTATION_CLAIM_MESSAGE = " AI analysis response claimed ledger data was changed.";
    private static final String SCHEMA_MESSAGE = " AI analysis response did not match the expected schema.";
    private static final String SAFE_BOUNDS_MESSAGE = " AI analysis response exceeded safe response bounds.";

    private static final Pattern SECRET_DISCLOSURE_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile(
            "(?i)(?:authorization\\s*[:=]\\s*(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}|\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,})"
    );
    private static final Pattern SECRET_BEARING_URL_PATTERN = Pattern.compile(
            "(?i)https?://\\S*(?:(?:[?&](?:X-Amz-Signature|X-Amz-Credential|X-Amz-Security-Token|X-Goog-Signature|AWSAccessKeyId|Signature|token|access_token|api_key)=)|/webhook/)\\S*"
    );
    private static final Pattern PROMPT_INJECTION_ECHO_PATTERN = Pattern.compile(
            "(?i)(ignore|disregard|override|bypass).{0,40}(previous|above|system|developer).{0,40}(instruction|prompt|message)"
    );
    private static final Pattern ENGLISH_MUTATION_CLAIM_PATTERN = Pattern.compile(
            "(?i)(?:(created|updated|deleted|modified|saved|categorized|reclassified).{0,60}"
                    + "(transaction|ledger entr(?:y|ies)|expense|income)"
                    + "|(transaction|ledger entr(?:y|ies)|expense|income).{0,60}"
                    + "(created|updated|deleted|modified|saved|categorized|reclassified))"
    );

    private static final Pattern KOREAN_MUTATION_CLAIM_PATTERN = Pattern.compile(
            "(?:(?:\uAC70\uB798|\uAC00\uACC4\uBD80|\uC9C0\uCD9C|\uC218\uC785).{0,60}"
                    + "(?:\uC0DD\uC131|\uC218\uC815|\uC0AD\uC81C|\uC800\uC7A5|\uBD84\uB958|\uC7AC\uBD84\uB958).{0,20}"
                    + "(?:\uD588\uC2B5\uB2C8\uB2E4|\uD558\uC600\uC2B5\uB2C8\uB2E4|\uB418\uC5C8\uC2B5\uB2C8\uB2E4|\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4|\uB428)"
                    + "|(?:\uC0DD\uC131|\uC218\uC815|\uC0AD\uC81C|\uC800\uC7A5|\uBD84\uB958|\uC7AC\uBD84\uB958).{0,60}"
                    + "(?:\uAC70\uB798|\uAC00\uACC4\uBD80|\uC9C0\uCD9C|\uC218\uC785).{0,20}"
                    + "(?:\uD588\uC2B5\uB2C8\uB2E4|\uD558\uC600\uC2B5\uB2C8\uB2E4|\uB418\uC5C8\uC2B5\uB2C8\uB2E4|\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4|\uB428))"
    );
    private LedgerAiRemoteResponseValidator() {
    }

    public static LedgerAiRemoteResponse requireUsable(LedgerAiRemoteResponse response, String providerName) {
        String provider = hasText(providerName) ? providerName : "AI provider";
        if (response == null) {
            throw new BadRequestException(provider + EMPTY_RESPONSE_MESSAGE);
        }
        if (Boolean.FALSE.equals(response.ok())) {
            throw new BadRequestException(hasText(response.error())
                    ? response.error()
                    : provider + PROVIDER_FAILURE_MESSAGE);
        }
        if (!hasUsableAnalysis(response)) {
            throw new BadRequestException(provider + UNUSABLE_RESPONSE_MESSAGE);
        }
        rejectMalformedTextCollections(response, provider);
        rejectOversizedContent(response, provider);
        rejectUnsafeContent(response, provider);
        return response;
    }

    private static void rejectUnsafeContent(LedgerAiRemoteResponse response, String provider) {
        for (String value : allTextValues(response)) {
            if (!hasText(value)) {
                continue;
            }
            if (containsSecretLikeContent(value)) {
                throw new BadRequestException(provider + SECRET_LIKE_MESSAGE);
            }
            if (PROMPT_INJECTION_ECHO_PATTERN.matcher(value).find()) {
                throw new BadRequestException(provider + PROMPT_INJECTION_MESSAGE);
            }
            if (ENGLISH_MUTATION_CLAIM_PATTERN.matcher(value).find() || KOREAN_MUTATION_CLAIM_PATTERN.matcher(value).find()) {
                throw new BadRequestException(provider + MUTATION_CLAIM_MESSAGE);
            }
        }
    }

    private static void rejectMalformedTextCollections(LedgerAiRemoteResponse response, String provider) {
        for (Collection<String> values : allTextCollections(response)) {
            for (String value : values) {
                if (!hasText(value)) {
                    throw new BadRequestException(provider + SCHEMA_MESSAGE);
                }
            }
        }
    }

    private static void rejectOversizedContent(LedgerAiRemoteResponse response, String provider) {
        for (Collection<String> values : allTextCollections(response)) {
            if (values.size() > MAX_COLLECTION_SIZE) {
                throw new BadRequestException(provider + SAFE_BOUNDS_MESSAGE);
            }
        }
        for (String value : allTextValues(response)) {
            if (value != null && value.length() > MAX_TEXT_VALUE_LENGTH) {
                throw new BadRequestException(provider + SAFE_BOUNDS_MESSAGE);
            }
        }
    }

    private static List<Collection<String>> allTextCollections(LedgerAiRemoteResponse response) {
        List<Collection<String>> collections = new ArrayList<>();
        addCollection(collections, response.highlights());
        addCollection(collections, response.warnings());
        addCollection(collections, response.risks());
        addCollection(collections, response.recommendations());
        addCollection(collections, response.categoryInsights());
        addCollection(collections, response.paymentInsights());
        addCollection(collections, response.trendInsights());
        addCollection(collections, response.unusualSpendingInsights());
        addCollection(collections, response.fixedCostInsights());
        addReportCollections(collections, response.report());
        return collections;
    }

    private static void addReportCollections(List<Collection<String>> collections, LedgerAiAnalysisReportResponse report) {
        if (report == null) {
            return;
        }
        addCollection(collections, report.notableSpending());
        addCollection(collections, report.regularSpending());
        addCollection(collections, report.abnormalSpending());
        addCollection(collections, report.subscriptions());
        addCollection(collections, report.fixedExpenses());
        addCollection(collections, report.improvementActions());
        addCollection(collections, report.comparisonFocus());
    }

    private static void addCollection(List<Collection<String>> collections, Collection<String> source) {
        if (source != null) {
            collections.add(source);
        }
    }

    private static boolean containsSecretLikeContent(String value) {
        return SECRET_DISCLOSURE_PATTERN.matcher(value).find()
                || AUTHORIZATION_HEADER_PATTERN.matcher(value).find()
                || SECRET_BEARING_URL_PATTERN.matcher(value).find();
    }

    private static List<String> allTextValues(LedgerAiRemoteResponse response) {
        List<String> values = new ArrayList<>();
        add(values, response.error());
        add(values, response.summary());
        add(values, response.nextPeriodForecast());
        add(values, response.habitAssessment());
        addAll(values, response.highlights());
        addAll(values, response.warnings());
        addAll(values, response.risks());
        addAll(values, response.recommendations());
        addAll(values, response.categoryInsights());
        addAll(values, response.paymentInsights());
        addAll(values, response.trendInsights());
        addAll(values, response.unusualSpendingInsights());
        addAll(values, response.fixedCostInsights());
        addReport(values, response.report());
        return values;
    }

    private static void addReport(List<String> values, LedgerAiAnalysisReportResponse report) {
        if (report == null) {
            return;
        }
        add(values, report.keySummary());
        add(values, report.fullReport());
        add(values, report.averageAmountInsight());
        add(values, report.topPaymentMethod());
        addAll(values, report.notableSpending());
        addAll(values, report.regularSpending());
        addAll(values, report.abnormalSpending());
        addAll(values, report.subscriptions());
        addAll(values, report.fixedExpenses());
        addAll(values, report.improvementActions());
        addAll(values, report.comparisonFocus());
    }

    private static void add(List<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private static void addAll(List<String> values, Collection<String> source) {
        if (source != null) {
            values.addAll(source);
        }
    }

    private static boolean hasUsableAnalysis(LedgerAiRemoteResponse response) {
        return hasText(response.summary())
                || hasText(response.nextPeriodForecast())
                || hasText(response.habitAssessment())
                || hasAny(response.highlights())
                || hasAny(response.warnings())
                || hasAny(response.risks())
                || hasAny(response.recommendations())
                || hasAny(response.categoryInsights())
                || hasAny(response.paymentInsights())
                || hasAny(response.trendInsights())
                || hasAny(response.unusualSpendingInsights())
                || hasAny(response.fixedCostInsights())
                || hasUsableReport(response.report());
    }

    private static boolean hasUsableReport(LedgerAiAnalysisReportResponse report) {
        if (report == null) {
            return false;
        }
        return hasText(report.keySummary())
                || hasText(report.fullReport())
                || hasText(report.averageAmountInsight())
                || hasText(report.topPaymentMethod())
                || hasAny(report.notableSpending())
                || hasAny(report.regularSpending())
                || hasAny(report.abnormalSpending())
                || hasAny(report.subscriptions())
                || hasAny(report.fixedExpenses())
                || hasAny(report.improvementActions())
                || hasAny(report.comparisonFocus());
    }

    private static boolean hasAny(Collection<String> values) {
        return values != null && values.stream().anyMatch(LedgerAiRemoteResponseValidator::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}