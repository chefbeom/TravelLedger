# Notification Center Backend Baseline

Updated: 2026-06-30

This document records the notification-center backend baseline and the first producer wiring. Storage, listing, unread counts, read handling, AI analysis events, OCR failure events, scheduled backup failure events, and shared-file events are now in place; budget and travel producers remain in the queue.

## Implemented API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/notifications` | `GET` | List the current user's notifications with `page`, `size`, and optional `unreadOnly=true`. |
| `/api/notifications` | `POST` | Create a notification for the current user. This remains useful for manual/internal UI testing while producers are being wired. |
| `/api/notifications/{notificationId}/read` | `PATCH` | Mark one owned notification as read. |
| `/api/notifications/read-all` | `PATCH` | Mark all unread owned notifications as read. |

## Data Model

| Field | Reason |
| --- | --- |
| `ownerId` | Keeps notifications user-scoped and avoids body-provided user targeting. |
| `type` | Stable event family such as `AI_ANALYSIS_DONE`, `BACKUP_FAILED`, or `SHARED_FILE_RECEIVED`. |
| `title` / `message` | User-facing summary text for header badge and notification list. |
| `targetUrl` | Optional deep link into the feature that produced the notification. Signed-token query values are redacted before persistence. |
| `metadataJson` | Optional structured payload for future UI rendering. Sensitive field values and signed-token query parameters are redacted before persistence. |
| `readAt` | Supports unread badge counts without deleting history. |

## Safety Rules

| Rule | Reason |
| --- | --- |
| Current user ID always comes from `@AuthenticationPrincipal`. | Prevents cross-user notification access. |
| List and read APIs are owner-scoped. | A user cannot mark another user's notification as read. |
| API responses include `unreadCount`. | Header badge can render without a second count endpoint. |
| Notification metadata must not contain API keys, signed URLs, raw prompts, or backup credentials. | Notifications are designed for UI convenience, not sensitive data storage. |
| Notification creation redacts sensitive metadata fields, bearer tokens, and signed URL query parameters before persistence. | Producer mistakes should not turn the notification table into a secret store. |
| Scheduled backup failure notifications go only to active admins and store bounded `backupType`/`status` metadata. | Operators need actionability without leaking backup paths, remote names, or credentials. |
| Event producers should write short messages and link to the source page. | Keeps the center useful without duplicating feature data. |

## Implemented Producers

| Producer | Type | Target |
| --- | --- | --- |
| Ledger AI analysis completed | `AI_ANALYSIS_DONE` | AI analysis history deep link. |
| Ledger AI analysis failed | `AI_OR_OCR_FAILED` | AI analysis history/status deep link. |
| Ledger OCR failed | `AI_OR_OCR_FAILED` | Receipt OCR retry surface. |
| Scheduled database backup failed | `BACKUP_FAILED` | Admin data-management page. |
| Scheduled MinIO backup failed | `BACKUP_FAILED` | Admin data-management page. |
| CalenDrive file shared with user | `SHARED_FILE_RECEIVED` | CalenDrive shared-files view. |

## Event Producer Queue

| Producer | Suggested type | Target |
| --- | --- | --- |
| Budget threshold exceeded | `BUDGET_WARNING` | Ledger dashboard. |
| Travel date approaching | `TRAVEL_REMINDER` | Travel plan detail page. |

## Test Backlog

- Service tests cover sensitive metadata redaction and owner-scoped single-notification read lookup.
- OCR remote/config failures create bounded `AI_OR_OCR_FAILED` notifications without masking the original OCR error.
- Scheduled backup failures create bounded `BACKUP_FAILED` notifications for active admins without leaking backup paths, remote names, or credentials.
- Unauthenticated users cannot call `/api/notifications`.
- User A cannot list or mark User B's notification as read.
- `unreadCount` decreases after read/read-all operations.
- Event producers do not include raw prompts, backup credentials, or long raw payloads in `metadataJson`.
- Pagination clamps `size` to the backend maximum.