# Core E2E Smoke Checklist

Updated: 2026-06-30

This checklist defines the first end-to-end coverage target for TravelLedger. It is intentionally tool-neutral so it can be used manually now and converted to Playwright/Cypress later without changing the acceptance criteria.

## Scope

| Priority | Flow | Why it matters | Evidence to capture |
| --- | --- | --- | --- |
| P0 | Login, secondary PIN, logout | Users cannot use any private feature without this path. | Screenshot or test trace showing successful login, `/api/auth/me`, and logout state. |
| P0 | Ledger entry create/edit/delete | Core household ledger workflow. | Entry appears in list/statistics, edit persists, delete removes it. |
| P0 | Excel import preview/save | Bulk data import can corrupt ledger data if mappings drift. | Preview rows, category/payment mapping, saved entries count. |
| P0 | OCR receipt analysis and confirm-save | AI/OCR must suggest entries without silently mutating the ledger. | OCR result shown as review step, user confirms before entry is created. |
| P0 | CalenDrive upload/share/public link | File sharing is security-sensitive. | Owner uploads file, recipient can access shared file, revoked/expired public link fails. |
| P0 | Admin backup action | Admin-only destructive/operational path. | Non-admin denied, verified admin can trigger backup, audit/event evidence visible. |
| P1 | Travel photo upload and map view | Travel media ties upload, storage, thumbnails, and map UI. | Photo upload completes, thumbnail renders, map/photo cluster shows owner-scoped media. |
| P1 | AI ledger analysis | Provider integration must remain advisory and safe. | Analysis result renders as advice; no ledger entry is created without user confirmation. |
| P1 | Notification center | Async completion/failure events should be visible. | AI/share event appears, unread count updates, read action persists. |

## Shared Test Data

| Data | Purpose |
| --- | --- |
| Regular user `hana` or seeded equivalent | Main owner-scoped flow. |
| Second regular user `minsu` or seeded equivalent | Cross-user/share recipient checks. |
| Admin user `admin` or seeded equivalent | Admin-only and recent secondary verification checks. |
| Small valid image receipt | OCR upload and review flow. |
| Small valid photo | Travel/drive media upload. |
| Small `.xlsx` import file | Ledger import preview/save. |
| Small PDF or text file | Drive upload/share smoke path. |

Use disposable test data. Do not run E2E smoke checks against production accounts or real financial receipts.

## P0 Flow Details

### 1. Login, secondary PIN, logout

1. Open the app unauthenticated.
2. Log in with a seeded regular user and valid secondary PIN.
3. Confirm the authenticated workspace loads and `/api/auth/me` returns the current user.
4. Log out.
5. Confirm private navigation returns to unauthenticated state.

Acceptance criteria:

- Login succeeds only with correct password and secondary PIN.
- Logout clears the authenticated UI state.
- No API key, provider URL, secondary PIN, or remember-me token appears in visible UI text.

### 2. Ledger entry create/edit/delete

1. Create an expense entry with category, payment method, amount, title, date, and memo.
2. Confirm the entry appears in the ledger list and monthly statistics.
3. Edit amount/title/category.
4. Delete the entry.

Acceptance criteria:

- Create/edit/delete operations require authenticated user state.
- Statistics update after create/edit/delete.
- The entry is never visible to another regular user.

### 3. Excel import preview/save

1. Upload a small `.xlsx` file with two or more rows.
2. Review preview rows and mapping suggestions.
3. Save selected rows.
4. Confirm saved entries appear in the ledger.

Acceptance criteria:

- Preview does not mutate ledger data before save.
- Invalid rows are visible and explain why they cannot be saved.
- Saved entries remain owner-scoped.

### 4. OCR receipt analysis and confirm-save

1. Upload a receipt image.
2. Wait for OCR/AI analysis result.
3. Review extracted text, suggested amount/category/payment method, and warnings.
4. Save only after explicit user confirmation.

Acceptance criteria:

- OCR analysis result is a review step, not an automatic ledger mutation.
- Oversized or spoofed files are rejected before remote OCR work.
- Failure state explains retry or configuration next steps.

### 5. CalenDrive upload/share/public link

1. Upload a small file as the owner.
2. Share the file with a second user.
3. Confirm recipient can access only the shared file.
4. Create a public download link and use it once.
5. Revoke or expire the public link and confirm it fails.

Acceptance criteria:

- Owner/recipient boundaries are respected.
- Public-link failures are controlled and logged without raw token exposure.
- Shared file metadata does not expose storage credentials or signed upload URLs.

### 6. Admin backup action

1. Attempt backup-related admin action as unauthenticated user.
2. Attempt as regular user.
3. Attempt as admin without recent secondary verification.
4. Complete recent admin verification and trigger the backup action.

Acceptance criteria:

- Unauthenticated receives `401`; regular/unverified admin receives `403`.
- Verified admin mutation requires CSRF.
- Audit/log evidence identifies the admin action without secrets.

## P1 Flow Details

| Flow | First assertion set |
| --- | --- |
| Travel photo upload and map view | Upload photo, thumbnail renders, owner sees photo cluster, other user cannot access owner media without share/public access. |
| AI ledger analysis | Result is labeled as AI analysis/advice, failed provider request stores failure history, retry does not create duplicate completed history within suppression window. |
| Notification center | AI/share event appears, unread count changes, read action persists across refresh. |

## Automation Conversion Notes

When converting this checklist to automated E2E tests:

1. Keep P0 flows independent and reset data between runs.
2. Prefer API-assisted setup for users/categories/payment methods, then browser assertions for user-visible flows.
3. Capture traces/screenshots on failure.
4. Keep secrets and provider URLs out of trace attachments.
5. Stub OCR/AI providers for deterministic CI; keep one separate manual/live-provider smoke path for staging.
6. Run mobile viewport smoke for login, OCR review, drive share, and travel photo upload.

## Release Gate

Before a release that changes auth, ledger, import, OCR, drive sharing, travel media, admin backup, AI analysis, or notification behavior:

1. Run the matching P0 smoke flow manually or via automated E2E.
2. Attach evidence to the release note or PR.
3. If skipped, record why and what compensating backend/unit tests cover the changed behavior.