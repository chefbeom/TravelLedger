# Transaction Anomaly Detection

Updated: 2026-06-30

This document records the first deterministic transaction anomaly detection slice. The initial backend API is read-only and does not use AI, so it can flag obvious duplicate candidates without mutating ledger data.

## Implemented API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/entries/anomalies` | `GET` | Lists duplicate expense candidates for the authenticated user. |

Optional query parameters:

| Parameter | Description |
| --- | --- |
| `from` | Start date, `yyyy-MM-dd`. |
| `to` | End date, `yyyy-MM-dd`. |
| `limit` | Maximum anomaly groups to return. Defaults to 50 and is capped at 200. |

## Current Detector

| Type | Rule | Severity |
| --- | --- | --- |
| `DUPLICATE_SAME_DAY_AMOUNT_TITLE` | Expense entries on the same date with the same amount and normalized title. | `medium` for 2 entries, `high` for 3 or more. |

Normalization:

- Title is trimmed, lowercased, and repeated whitespace is collapsed.
- Amount uses a trailing-zero-insensitive decimal representation.
- Deleted entries and non-expense entries are ignored.
- User scope comes only from `@AuthenticationPrincipal`.

## Safety Rules

| Rule | Reason |
| --- | --- |
| Detector is read-only. | It must not mutate or delete ledger data automatically. |
| Results are candidates, not facts. | User confirmation is required before editing or dismissing entries. |
| Date range is capped at 366 days. | Prevents expensive full-history scans from accidental wide queries. |
| API returns entry summaries, not hidden deleted records. | Keeps output aligned with normal ledger visibility. |

## Next Detectors

| Candidate | Notes |
| --- | --- |
| Unusually large spending | Compare against category/payment historical baseline. |
| Repeated subscription-like payments | Same merchant/title and similar amount across months. |
| Travel out-of-context spend | Expense linked to travel category outside active trip date range. |
| Imported duplicate candidates | Compare newly imported Excel/OCR rows against existing entries before save. |

## Test Evidence

| Evidence | Coverage |
| --- | --- |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesGroupsSameDaySameAmountNormalizedTitleExpensesOnly` | Verifies same-day same-amount normalized-title expense duplicates are grouped, while income entries and different dates do not join the group. |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesCapsReturnedGroupsWithoutChangingTotalGroups` | Verifies `limit` is capped at 200 and does not change the full `totalGroups` count. |
| `LedgerTransactionAnomalyServiceTest.findAnomaliesRejectsRangeLongerThan366DaysBeforeReadingEntries` | Verifies wide date ranges fail before reading ledger entries. |
## Test Backlog

- Keep same-day same-amount normalized-title duplicate grouping coverage current as detectors expand.
- Different users never see each other's anomaly candidates.
- Keep repository owner/deleted scope and expense-only grouping coverage current.
- Keep range validation coverage current before adding broader historical detectors.
- Keep limit cap coverage current so UI pagination cannot hide total candidate count.