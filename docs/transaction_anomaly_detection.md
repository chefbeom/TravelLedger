# Transaction Anomaly Detection

Updated: 2026-06-30

This document records the deterministic transaction anomaly detection baseline. The backend API is read-only and does not use AI, so it can flag duplicate and unusually large spending candidates without mutating ledger data.

## Implemented API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/entries/anomalies` | `GET` | Lists duplicate and unusually large expense candidates for the authenticated user. |

Optional query parameters:

| Parameter | Description |
| --- | --- |
| `from` | Start date, `yyyy-MM-dd`. |
| `to` | End date, `yyyy-MM-dd`. |
| `limit` | Maximum anomaly groups to return. Defaults to 50 and is capped at 200. |

## Current Detectors

| Type | Rule | Severity |
| --- | --- | --- |
| `DUPLICATE_SAME_DAY_AMOUNT_TITLE` | Expense entries on the same date with the same amount and normalized title. | `medium` for 2 entries, `high` for 3 or more. |
| `UNUSUALLY_LARGE_EXPENSE` | Expense is at least 3x the selected-range median expense and at least KRW 50,000. Detector requires at least 5 expense entries in scope. | `high` when at least 5x median and KRW 50,000, otherwise `medium`. |

Normalization and thresholds:

- Title is trimmed, lowercased, and repeated whitespace is collapsed for duplicate grouping.
- Amount uses a trailing-zero-insensitive decimal representation for anomaly keys.
- Large-spend detection ignores income entries and uses absolute expense amounts.
- Deleted entries and non-expense entries are ignored.
- User scope comes only from `@AuthenticationPrincipal`.

## Safety Rules

| Rule | Reason |
| --- | --- |
| Detector is read-only. | It must not mutate or delete ledger data automatically. |
| Results are candidates, not facts. | User confirmation is required before editing or dismissing entries. |
| Date range is capped at 366 days. | Prevents expensive full-history scans from accidental wide queries. |
| Large-spend detector requires a small baseline. | Avoids flagging the first few expenses before there is enough local context. |
| API returns entry summaries, not hidden deleted records. | Keeps output aligned with normal ledger visibility. |

## Next Detectors

| Candidate | Notes |
| --- | --- |
| Repeated subscription-like payments | Same merchant/title and similar amount across months. |
| Category/payment historical baseline | Use longer history by category or payment method instead of only the selected-range median. |
| Travel out-of-context spend | Expense linked to travel category outside active trip date range. |
| Imported duplicate candidates | Compare newly imported Excel/OCR rows against existing entries before save. |

## Test Evidence

| Evidence | Coverage |
| --- | --- |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesGroupsSameDaySameAmountNormalizedTitleExpensesOnly` | Verifies same-day same-amount normalized-title expense duplicates are grouped, while income entries and different dates do not join the group. |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesFlagsUnusuallyLargeExpenseAgainstMedianExpense` | Verifies a large single expense is flagged against the selected-range median without including income entries. |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesCapsReturnedGroupsWithoutChangingTotalGroups` | Verifies `limit` is capped at 200 and does not change the full `totalGroups` count. |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesRejectsRangeLongerThan366DaysBeforeReadingEntries` | Verifies wide date ranges fail before reading ledger entries. |

## Test Backlog

- Keep same-day same-amount normalized-title duplicate grouping coverage current as detectors expand.
- Different users never see each other's anomaly candidates.
- Keep repository owner/deleted scope and expense-only grouping coverage current.
- Keep range validation coverage current before adding broader historical detectors.
- Keep limit cap coverage current so UI pagination cannot hide total candidate count.
- Add frontend panel and dismiss workflow coverage once the anomaly candidates are shown in the UI.