# Ledger Classification Rules

Updated: 2026-06-30

This document records the first backend slice for user-defined ledger classification rules. The goal is to improve OCR and Excel import quality with explicit, explainable rules before adding AI-recommended rule approval.

## Implemented API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/ledger/classification-rules` | `GET` | List the current user's active rules, or all rules with `includeInactive=true`. |
| `/api/ledger/classification-rules` | `POST` | Create a keyword rule. |
| `/api/ledger/classification-rules/{ruleId}` | `PUT` | Replace a rule owned by the current user. |
| `/api/ledger/classification-rules/{ruleId}` | `DELETE` | Deactivate a rule instead of hard-deleting it. |
| `/api/ledger/classification-rules/preview` | `POST` | Preview the first matching rule for a title/memo/entryType input. |

## Matching Rule

| Step | Behavior |
| --- | --- |
| Text normalization | Title and memo are trimmed, lowercased, and repeated whitespace is collapsed. |
| Entry type filter | Rule entry type must match the preview entry type when both are present. |
| Keyword match | First active rule whose normalized keyword is contained in title+memo wins. |
| Priority | Lower priority number wins, then lower rule ID. |

## Safety Rules

| Rule | Reason |
| --- | --- |
| Rules are scoped to `@AuthenticationPrincipal`. | Users cannot create or preview another user's rules. |
| Category group/detail/payment method IDs are owner-validated. | Prevents cross-user classification references. |
| Category detail must belong to the selected group. | Prevents inconsistent import suggestions. |
| Delete deactivates only. | Keeps audit/debug context for surprising import behavior. |
| Preview does not mutate ledger data. | User confirmation is still required before applying suggestions. |

## Next Slices

| Slice | Notes |
| --- | --- |
| Apply rules in Excel/OCR preview | Show matched rule and suggested category/payment before save. |
| AI-recommended rule approval | Let AI suggest new keyword rules, but require user approval. |
| Rule conflict detection | Warn when a new keyword overlaps with a higher-priority rule. |
| Usage statistics | Track how often a rule matched and was accepted/rejected. |

## Test Evidence

| Evidence | Coverage |
| --- | --- |
| `LedgerClassificationRuleServiceTest.previewReturnsFirstActiveOwnerRuleInPriorityOrder` | Verifies preview reads the current user's active rules in priority order and returns the first matching keyword. |
| `LedgerClassificationRuleServiceTest.previewDoesNotMatchDifferentEntryTypeRule` | Verifies an income rule does not classify an expense preview even when the keyword text matches. |
| `LedgerClassificationRuleServiceTest.createRuleRejectsCategoryDetailFromDifferentGroup` | Verifies a rule cannot bind a category detail that belongs to a different category group. |
## Test Backlog

- User A cannot create/update/deactivate/preview User B's category or payment IDs.
- Keep preview priority-order and active-only repository coverage current as matching modes expand.
- Inactive rules do not match preview.
- Keep detail/category consistency coverage current as rule creation gains conflict detection.
- Preview does not create or update ledger entries.