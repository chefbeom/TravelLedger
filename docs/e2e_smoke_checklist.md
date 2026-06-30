# E2E smoke checklist

Updated: 2026-06-30

This checklist defines the minimum backend and browser-level evidence required before releases that change authentication, ledger, OCR/AI, travel, drive sharing, notifications, file uploads, or admin backup behavior. It is paired with the first Playwright smoke skeleton at `frontend/e2e/smoke.spec.js`; the checklist remains the source of truth for acceptance criteria, fixture safety, and release evidence.

## Shared setup

| Item | Requirement |
| --- | --- |
| Browser | Chromium or Chrome current stable. The Playwright skeleton runs desktop Chromium and a mobile Chromium profile. |
| Viewports | Desktop `1440x900` and mobile `390x844` for auth, ledger entry, OCR, drive share, and notification flows. |
| Accounts | One normal user, one second normal user for sharing/cross-user checks, and one admin with secondary verification configured. |
| Data reset | Use disposable test data. Do not run against production data unless the release manager explicitly approves a read-only smoke pass. |
| Evidence | Attach date, tester, commit SHA, environment URL, provider mode, pass/fail/skip result, and screenshots or short screen recordings for failed steps. |

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

## Backend high-risk test coverage

The CI `backend-security-tests` job is the fast high-risk backend subset. The full `backend-test` job still runs the complete Gradle test suite, but this subset must keep explicit coverage for the areas most likely to create user-visible data loss, authorization leakage, or unsafe AI/OCR behavior.

| Area | Required CI coverage | Test classes in `backend-security-tests` |
| --- | --- | --- |
| AI analysis and OCR safety | Prompt/output validation, provider configuration, LM Studio/n8n failures, report merge behavior, retention cleanup, OCR failure notification, and no autonomous ledger mutation. | `LedgerAiRemoteResponseValidatorTest`, `LedgerAiLmStudioClientTest`, `LedgerAiAnalysisPropertiesTest`, `LedgerAiAnalysisPayloadBuilderTest`, `LedgerAiAnalysisReportMergerTest`, `LedgerAiAnalysisHistoryRetentionServiceTest`, `LedgerAiAnalysisServiceTest`, `LedgerOcrServiceTest` |
| Authorization and account boundaries | Admin-only surfaces, secondary credential flows, privacy/account scoping, and data-management access denial. | `AdminDashboardIntegrationTest`, `AdminDataManagementServiceTest`, `ProfileCredentialIntegrationTest`, `PrivacyControllerIntegrationTest`, `DriveAdminSecurityIntegrationTest` |
| Sharing and public links | Drive owner scope, direct share grants, download link status/logging, travel public media token behavior, and cross-user visibility. | `DriveServiceTest`, `DriveShareServiceTest`, `DriveDownloadLinkServiceTest`, `DriveDownloadLinkAccessLogServiceTest`, `TravelServiceShareVisibilityTest`, `TravelPublicMediaTokenServiceTest` |
| Backup, restore, and data portability | Backup scheduler behavior, admin data-management service guardrails, and export contract safety. | `DataOpsBackupSchedulerTest`, `AdminDataManagementServiceTest`, `DataPortabilityExportServiceTest` |
| File upload and media limits | Drive storage validation, travel media storage validation, travel controller upload paths, and family album upload controller behavior. | `DriveStorageServiceTest`, `TravelMediaStorageServiceTest`, `TravelControllerTest`, `FamilyAlbumControllerTest` |

Adding a new AI provider, upload surface, share mode, backup target, or admin data operation should add or extend a backend test in this matrix before the release gate is considered complete.
## Playwright smoke skeleton

| Item | Current contract |
| --- | --- |
| Config | `frontend/playwright.config.js` defines desktop `1440x900` and mobile `390x844` Chromium projects. |
| Spec | `frontend/e2e/smoke.spec.js` keeps the P0 flow inventory in code and verifies public shell rendering, login/session behavior, authenticated workspace routing, fixture gates, and a notification-center API/UI smoke path. |
| Command | From `frontend`, run `npm run test:e2e:install` once for Chromium, then run `npm run test:e2e:smoke`. Use `npm run test:e2e:smoke:headed` for local debugging. |
| Base URL | `E2E_BASE_URL` or `PLAYWRIGHT_BASE_URL`; default is `http://127.0.0.1:5173`. Set `E2E_START_LOCAL_SERVER=0` when targeting an already running deployed environment. |
| User credentials | `E2E_USER_LOGIN_ID`, `E2E_USER_PASSWORD`, optional `E2E_USER_SECONDARY_PIN`. |
| Second-user credentials | `E2E_SECOND_USER_LOGIN_ID`, `E2E_SECOND_USER_PASSWORD`, optional `E2E_SECOND_USER_SECONDARY_PIN` for CalenDrive sharing checks. |
| Admin credentials | `E2E_ADMIN_LOGIN_ID`, `E2E_ADMIN_PASSWORD`, optional `E2E_ADMIN_SECONDARY_PIN` for admin route and backup guardrail checks. |
| Mutation safety | Mutating/upload/share/admin paths require `E2E_ALLOW_MUTATING_SMOKE=1` plus the flow-specific fixture readiness flag before they run. |
| Provider mode | OCR and AI paths require `E2E_PROVIDER_MODE=stubbed` with deterministic fixture flags such as `E2E_OCR_STUB_READY=1` and `E2E_AI_STUB_READY=1`. |
| Current scope | Phase 1 automation is a smoke skeleton, not full release proof for every flow. A release can count it as evidence only when affected P0 flows pass with feature-specific assertions or have approved skips. |

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
| P0 | Login and session | `frontend/e2e/smoke.spec.js` covers CSRF bootstrap, API login, refresh persistence, `/api/auth/me`, and logout. Add UI form assertions once login selectors are stabilized. |
| P0 | Ledger entry create/edit/delete | Current Playwright checkpoint requires `E2E_LEDGER_SMOKE_READY=1` and `E2E_ALLOW_MUTATING_SMOKE=1`; next step is adding stable selectors for entry form fields and calendar/statistics result rows. |
| P0 | OCR confirm-save | Current Playwright checkpoint requires `E2E_PROVIDER_MODE=stubbed`, `E2E_OCR_STUB_READY=1`, and `E2E_ALLOW_MUTATING_SMOKE=1`; next step is deterministic receipt fixture upload and explicit confirm-save assertions. |
| P0 | CalenDrive share | Current Playwright checkpoint requires two users plus `E2E_DRIVE_SHARE_SMOKE_READY=1`; next step is two browser contexts for User A and User B with UI status checks for share/revoke/expired states. |
| P1 | Excel import | Current Playwright checkpoint requires `E2E_EXCEL_IMPORT_SMOKE_READY=1`; store a tiny fixture spreadsheet in the E2E fixture folder before adding upload/confirm assertions. |
| P1 | Admin backup action | Current Playwright checkpoint requires admin credentials, `E2E_ADMIN_BACKUP_SMOKE_READY=1`, and `E2E_ALLOW_MUTATING_SMOKE=1`; prefer mocked backup endpoint in automated UI tests and run real backup rehearsal through the backend runbook gate. |
| P1 | Travel photo upload | Current Playwright checkpoint requires `E2E_TRAVEL_MEDIA_SMOKE_READY=1`; use a tiny valid JPEG and a malformed-image fixture, then verify no broken media record remains visible after invalid upload. |
| P1 | AI analysis advisory | Current Playwright checkpoint requires `E2E_PROVIDER_MODE=stubbed` and `E2E_AI_STUB_READY=1`; next step is asserting advisory copy, provider failure UI, and absence of direct ledger mutation. |
| P1 | Notification center | `frontend/e2e/smoke.spec.js` now requires `E2E_NOTIFICATION_SMOKE_READY=1`, verifies `/api/notifications` response shape and secret-free payload text, and checks the notification center heading, filters, read-all affordance, and unread badge. Next step is seeding cross-user notifications and asserting owner isolation plus read/read-all state transitions with disposable fixtures. |

## Gate policy

- Any release touching a P0 flow must attach the corresponding smoke evidence or a linked automated run.
- Skips require an owner, reason, follow-up issue, and explicit release approver.
- The Playwright smoke skeleton can be used as release evidence only for flows whose feature-specific assertions are implemented and passing; fixture-gated workspace checkpoints alone are not enough for high-risk changes.
- Live AI/OCR provider checks are not required in CI, but stubbed success, timeout, and failure paths must be covered when E2E automation is introduced.
- Automated runs must use disposable fixtures and must not log API keys, public-link tokens, presigned URLs, raw OCR images, raw AI prompts, or secondary PIN values.
- The checklist must stay in sync with `scripts/verify-e2e-smoke-checklist.ps1`, `frontend/playwright.config.js`, `frontend/e2e/smoke.spec.js`, and the CI `frontend-e2e-smoke-checklist` job.
