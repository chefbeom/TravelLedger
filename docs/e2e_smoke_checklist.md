# E2E smoke checklist

Updated: 2026-06-30

This checklist defines the minimum browser-level evidence required before releases that change authentication, ledger, OCR/AI, travel, drive sharing, notifications, or admin backup behavior. It is intentionally tool-neutral so the same scenarios can be run manually now and converted to Playwright/Cypress later.

## Shared setup

| Item | Requirement |
| --- | --- |
| Browser | Chromium or Chrome current stable. |
| Viewports | Desktop `1440x900` and mobile `390x844` for auth, ledger entry, OCR, drive share, and notification flows. |
| Accounts | One normal user, one second normal user for sharing/cross-user checks, and one admin with secondary verification configured. |
| Data reset | Use disposable test data. Do not run against production data unless the release manager explicitly approves a read-only smoke pass. |
| Evidence | Attach date, tester, commit SHA, environment URL, pass/fail result, and screenshots or short screen recordings for failed steps. |

## P0 smoke flows

| Flow | Acceptance criteria | Risk covered |
| --- | --- | --- |
| Login and session | User can load CSRF, log in, refresh the page, see their own dashboard, and log out. Missing or stale session returns to login without exposing another user's data. | Authentication, CSRF bootstrap, session isolation. |
| Ledger entry create/edit/delete | User can create an income or expense, see it on the calendar/statistics surface, edit it, move it to trash/delete path as designed, and never sees another user's entry. | Core ledger integrity and owner scoping. |
| Excel import preview and confirm | User can upload a valid spreadsheet, review parsed rows, confirm import, and see imported entries. Invalid file feedback is visible and does not create entries. | Bulk import correctness and file handling. |
| OCR confirm-save | User can upload a receipt image, receive suggestions, review/edit each suggested entry, explicitly save, and confirm no ledger entry is created before user approval. | OCR safety, user-confirmed mutations, mobile upload UX. |
| Travel photo upload | User can create/select a travel plan, upload a photo, see it in the travel media/photo surface, and recover gracefully from an invalid image. | Travel media upload, image processing failure isolation. |
| CalenDrive share | User A can upload/share a file with User B, User B can see the shared item, public/revoked/expired link behavior is visible, and User C cannot access the item. | Drive ownership, share grants, public link safety. |
| Admin backup action | Admin must complete secondary verification before backup/restore/data-management actions. Non-admin and unverified admin users are denied. | Admin authorization, destructive-operation guardrails. |
| AI analysis advisory | User can run or load AI analysis, sees advisory wording, and any suggested ledger change still requires a separate explicit user action. Provider failure shows bounded error UI. | AI safety, failure handling, no autonomous mutations. |
| Notification center | User sees AI/share/backup/OCR notifications when produced, unread count changes after read/read-all, and another user's notifications are not visible. | Cross-feature awareness and owner-scoped notification access. |

## Automation readiness contract

| Contract | Required practice | Why it matters |
| --- | --- | --- |
| Stable selectors | Prefer role/name assertions; add stable `data-testid` only where dynamic tables, uploads, calendars, maps, or generated AI/OCR content make role/name selectors ambiguous. | Prevents fragile CSS-selector tests while keeping UI behavior user-oriented. |
| Disposable fixtures | Seed disposable users, ledger rows, spreadsheet fixture, receipt image, travel photo, drive file, notification rows, and admin backup state through setup APIs or fixtures; clean them after the run. | Keeps smoke runs repeatable and safe outside production. |
| Provider stubbing | Required CI paths use deterministic OCR/AI success, timeout, and failure fixtures; live provider checks are optional and must not expose API keys in logs or screenshots. | Makes OCR/AI smoke tests reliable while still allowing manual provider confidence checks. |
| Cross-user contexts | Sharing and ownership checks use at least two authenticated browser contexts plus one unauthorized or third-user context. | Catches data isolation failures that a single-user happy path misses. |
| Mutation safety | OCR and AI scenarios assert no ledger entry, backup restore, file revoke, or destructive admin action happens before explicit user confirmation. | Preserves the project rule that AI/OCR output is advice or draft data until approved. |
| Admin guardrail | Admin backup/data-management scenarios cover non-admin denied, admin without secondary verification denied, verified admin allowed, and cancel path safe. | Keeps destructive operations behind both role and secondary-verification gates. |
| Mobile/accessibility pass | Auth, PIN, upload, share, OCR/AI review, and notification flows run at a mobile viewport and include keyboard/focus notes for blocking defects. | Connects the E2E baseline to the accessibility/mobile risk register. |
| Artifact hygiene | Store commit SHA, environment URL, fixture set, browser contexts, provider mode, screenshots/videos for failures, and skip owner/issue for any skipped P0 flow. | Makes release evidence auditable instead of anecdotal. |

## Release evidence template

```text
Commit SHA:
Environment URL:
Backend profile/config summary:
Fixture set:
Provider mode: stubbed | live-readonly | mixed
Browser contexts used:
Automation run URL or artifact path:
Tester:
Date/time:
Desktop browser/version:
Mobile browser/device or emulator:

Flow results:
- Login and session: pass | fail | skipped, reason
- Ledger entry create/edit/delete: pass | fail | skipped, reason
- Excel import preview and confirm: pass | fail | skipped, reason
- OCR confirm-save: pass | fail | skipped, reason
- Travel photo upload: pass | fail | skipped, reason
- CalenDrive share: pass | fail | skipped, reason
- Admin backup action: pass | fail | skipped, reason
- AI analysis advisory: pass | fail | skipped, reason
- Notification center: pass | fail | skipped, reason

Defects found:
Release decision:
```

## Automation conversion notes

| Priority | Scenario | Automation notes |
| --- | --- | --- |
| P0 | Login and session | Seed disposable users through backend fixtures or API setup. Assert URL, user display name, logout, and unauthenticated redirect. |
| P0 | Ledger entry create/edit/delete | Use stable selectors for entry form fields and calendar/statistics result rows. Assert owner-specific API responses through UI only where possible. |
| P0 | OCR confirm-save | Stub OCR backend response for deterministic browser automation, then keep one optional live-provider smoke case outside required CI. |
| P0 | CalenDrive share | Use two browser contexts for User A and User B; keep public-link token assertions in backend tests and UI status checks in E2E. |
| P1 | Excel import | Store a tiny fixture spreadsheet in the E2E fixture folder once a browser runner is introduced. |
| P1 | Admin backup action | Prefer mocked backup endpoint in automated UI tests; run real backup rehearsal through the backend runbook gate. |
| P1 | Travel photo upload | Use a tiny valid JPEG and a malformed-image fixture. Verify no broken media record remains visible after invalid upload. |
| P1 | AI analysis advisory | Stub provider result/failure responses. Assert advisory copy and absence of direct ledger mutation. |
| P1 | Notification center | Seed notifications through API setup, then verify unread/read-all UI and owner isolation. |

## Gate policy

- Any release touching a P0 flow must attach the corresponding smoke evidence or a linked automated run.
- Skips require an owner, reason, follow-up issue, and explicit release approver.
- Live AI/OCR provider checks are not required in CI, but stubbed success, timeout, and failure paths must be covered when E2E automation is introduced.
- Automated runs must use disposable fixtures and must not log API keys, public-link tokens, presigned URLs, raw OCR images, raw AI prompts, or secondary PIN values.
- The checklist must stay in sync with `scripts/verify-e2e-smoke-checklist.ps1` and the CI `frontend-e2e-smoke-checklist` job.