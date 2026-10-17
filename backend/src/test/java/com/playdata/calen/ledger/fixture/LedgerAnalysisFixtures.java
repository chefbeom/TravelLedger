package com.playdata.calen.ledger.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.LedgerAiN8nPayload;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisBoundaryMapper;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;

public final class LedgerAnalysisFixtures {
    private LedgerAnalysisFixtures() {}
    public static final String RESULT = "{\"ok\":true,\"summary\":\"fixture summary\",\"report\":{\"fullReport\":\"fixture analysis\"}}";
    public static final String INPUT = """
            {"schemaVersion":"travelledger.ledger-ai-analysis.v2","language":"ko","model":"fixture-model",
             "mode":"COMPARISON","periodType":"MONTH","comparisonPreset":"CUSTOM",
             "from":"2026-09-01","to":"2026-09-30","compareFrom":"2026-08-01","compareTo":"2026-08-31",
             "overview":{"from":"2026-09-01","to":"2026-09-30","income":1000.00,"expense":99.50,"balance":900.50,"entryCount":2},
             "categoryBreakdown":[{"groupName":"fixture group","detailName":"fixture detail","totalAmount":99.50,"entryCount":2}],
             "paymentBreakdown":[{"paymentMethodName":"fixture card","kind":"CARD","totalAmount":99.50,"entryCount":2}],
             "expensePaymentBreakdown":[{"paymentMethodName":"fixture card","totalAmount":99.50,"entryCount":2}],
             "periodComparison":[{"label":"fixture period","startDate":"2026-09-01","endDate":"2026-09-30","income":1000.00,"expense":99.50,"balance":900.50}],
             "expenseEntries":[{"entryDate":"2026-09-12","title":"fixture expense","memo":"fixture memo","amount":49.75,"categoryGroupName":"fixture group","categoryDetailName":"fixture detail","paymentMethodName":"fixture card"}],
             "topExpenses":[{"entryDate":"2026-09-12","title":"fixture expense","memo":null,"amount":49.75,"categoryGroupName":"fixture group","categoryDetailName":"fixture detail","paymentMethodName":"fixture card"}],
             "recurringExpenseCandidates":[{"title":"fixture repeat","categoryGroupName":"fixture group","categoryDetailName":"fixture detail","paymentMethodName":"fixture card","occurrenceCount":2,"totalAmount":99.50,"averageAmount":49.75,"firstDate":"2026-09-12","lastDate":"2026-09-13","dates":["2026-09-12","2026-09-13"]}],
             "comparisonOverview":{"from":"2026-08-01","to":"2026-08-31","income":800.00,"expense":50.00,"balance":750.00,"entryCount":1},
             "comparisonCategoryBreakdown":[{"groupName":"fixture group","detailName":null,"totalAmount":50.00,"entryCount":1}],
             "comparisonPaymentBreakdown":[{"paymentMethodName":"fixture cash","kind":"CASH","totalAmount":50.00,"entryCount":1}],
             "comparisonExpenseEntries":[{"entryDate":"2026-08-12","title":"fixture prior","memo":null,"amount":50.00,"categoryGroupName":"fixture group","categoryDetailName":null,"paymentMethodName":"fixture cash"}],
             "focusPrompt":"fixture focus","payloadMinimization":{"expenseEntryTotalCount":2,"expenseEntrySentCount":1,"expenseEntryOverflowCount":1,"comparisonExpenseEntryTotalCount":1,"comparisonExpenseEntrySentCount":1,"comparisonExpenseEntryOverflowCount":0,"textLimit":80,"memoLimit":160},
             "outputContract":"JSON only. Fixture advisory analysis contract."}
            """;

    public static LedgerAiN8nPayload legacy() {
        try { return new ObjectMapper().findAndRegisterModules().readValue(INPUT,LedgerAiN8nPayload.class); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    public static Request request() { return LedgerAnalysisBoundaryMapper.toRequest(legacy()); }
}
