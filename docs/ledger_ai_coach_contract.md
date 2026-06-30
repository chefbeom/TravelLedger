# Ledger AI Coach Contract

Updated: 2026-06-30

This contract turns the existing ledger AI analysis report into a user-facing coaching surface without giving the model authority to mutate ledger data. It complements `docs/ledger_ai_safety_hardening.md` and keeps the feature focused on advice, review, and explicit user confirmation.

## Coach outcomes

| Outcome | Response surface | Contract |
| --- | --- | --- |
| Risk spending | `warnings`, `report.abnormalSpending`, `unusualSpendingInsights` | Flag unusually large, fast-growing, duplicate-looking, or travel-out-of-range spending as review candidates, not confirmed fraud. |
| Recurring/subscription spend | `report.subscriptions`, `report.fixedExpenses`, `fixedCostInsights` | Identify likely recurring charges, subscriptions, and fixed expenses with uncertainty language. |
| Budget overrun forecast | `nextPeriodForecast`, `trendInsights`, `comparisonFocus` | Forecast likely next-period pressure from current spending pace and comparison range, not guaranteed future values. |
| Cashflow coaching | `summary`, `habitAssessment`, `recommendations`, `report.improvementActions` | Explain spending habits and next actions in plain Korean without creating, editing, deleting, categorizing, or reclassifying ledger entries. |
| Category/payment coaching | `categoryInsights`, `paymentInsights`, `report.topPaymentMethod` | Suggest review points for category/payment patterns while keeping source ledger records unchanged. |

## Expanded coach modules

The coach should move beyond a monthly summary by rendering four review cards plus one action list. These cards can be backed by deterministic ledger aggregates first, then enriched by the LLM only after the advisory and privacy contracts are satisfied.

| Module | Minimum signal | User-facing output | Safety acceptance |
| --- | --- | --- | --- |
| This month's risk spending | Current-month outliers, duplicate-looking payments, sudden category growth, travel-out-of-range spending, and unusually high single merchants. | A ranked list of review candidates with amount, category, date range, reason, confidence, and source evidence. | Use uncertainty language, never call a transaction fraud, and never edit/delete/categorize entries. |
| Subscription and recurring spend | Similar merchant/title, cadence, amount variance, payment method, and last paid date. | Likely subscriptions, fixed expenses, renewal risk, and manual review/cancel suggestions. | Mark uncertain matches as candidates and keep classification unchanged until the user confirms a separate action. |
| Budget overrun forecast | Elapsed days, current spend pace, comparison period, configured household/ledger goals, and known fixed expenses. | Category-level and total budget pressure with projected range, confidence, and suggested intervention. | Forecasts are estimates, not guarantees; overrun labels must be explainable from source aggregates. |
| Next-month cashflow forecast | Recent income, recurring expenses, subscriptions, planned travel spend, fixed expenses, and unusual current-month events. | Estimated next-month inflow/outflow pressure, upcoming cash pinch points, and preparation suggestions. | Do not present financial advice as certainty; avoid account-balance claims unless the source data supports them. |
| Coach action list | Combined risk, recurring, overrun, and cashflow signals. | Three to five prioritized actions the user can manually review, dismiss, or convert into an explicit ledger/budget task later. | Every action remains advisory until the user separately confirms a mutation in a non-AI flow. |
## Non-negotiable safety rules

| Rule | Reason |
| --- | --- |
| AI coach output is advisory-only analysis. | A model can be wrong and must not be treated as an accounting authority. |
| Any ledger data change requires a separate explicit user confirmation/save action outside the AI response. | Prevents hidden autonomous mutation from model output. |
| Coach text must not claim that entries were created, updated, deleted, saved, categorized, reclassified, or otherwise changed. | Keeps output in the advice lane and lets the unsafe-output validator fail closed. |
| Coach text must not expose API keys, provider URLs, signed URLs, public tokens, secondary PINs, raw prompts, or provider response bodies. | AI output and history can be shown to users and retained. |
| Prompt-injection-like ledger text remains untrusted data. | Transaction titles, memos, OCR text, category names, and user text can contain hostile instructions. |
| Repeated requests should reuse recent equivalent completed analysis when safe. | Avoids duplicate histories and retry confusion. |
| Forecasts and risk labels must use uncertainty language. | Reduces overconfidence for risk spending, budget pressure, subscription detection, and cashflow estimates. |

## Current implementation anchors

| Anchor | Required behavior |
| --- | --- |
| `LedgerAiAnalysisReportResponse` | Keeps `abnormalSpending`, `subscriptions`, `fixedExpenses`, `improvementActions`, and `comparisonFocus` as safe list fields. |
| `LedgerAiAnalysisService` | Merges remote/fallback report fields into highlights, warnings, recommendations, unusual spending, fixed-cost insights, next-period forecast, and habit assessment. |
| `LedgerAiRemoteResponseValidator` | Rejects secret-like output, prompt-injection echoes, empty usable output, and mutation claims before accepted analysis is stored. |
| `LedgerAiAnalysisServiceTest` | Covers provider payload boundary, duplicate suppression, safe failure history, and advice-only output contract. |
| `LedgerAiRemoteResponseValidatorTest` | Covers invalid provider output and unsafe output rejection. |
| Frontend AI advisory copy | Presents AI output as advisory analysis and states ledger changes require separate user confirmation/save. |

## Provider prompt contract

Every provider prompt/output contract should request these coach categories while preserving the safety rules:

- This month's risk spending and why it is risky.
- Likely subscriptions, recurring charges, and fixed expenses.
- Budget overrun or next-period pressure forecast based on current pace and comparison data.
- Next-month cashflow considerations, expressed as estimate/advice rather than fact.
- Concrete improvement actions that the user may review manually.
- Explicit reminder that no ledger entry has been changed and any change requires user confirmation.

## Release gate

A change to ledger AI prompts, response DTOs, validators, history storage, frontend AI rendering, classification-rule suggestions, or future auto-apply flows should include:

- Updated coach outcome mapping if any AI response field is added, removed, or renamed.
- A validator or service test proving model output cannot claim ledger entries were changed.
- A frontend copy check that advisory language remains visible near AI results.
- A privacy review proving coach output/history excludes secrets, signed URLs, raw prompts, provider responses, and secondary PINs.
- A product review if a recommendation can become a one-click ledger change; the user confirmation boundary must be explicit.

## CI contract

`scripts/verify-ledger-ai-coach-contract.ps1` checks this contract, the AI safety hardening plan, report DTO, service merge anchors, unsafe-output validator evidence, security baseline, and GitHub Actions `ledger-ai-coach-contract` release-gate job. The gate is structural and does not call an AI provider.