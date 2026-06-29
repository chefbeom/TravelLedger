# Security Baseline Checklist

Updated: 2026-06-30

This checklist maps the current TravelLedger security surface to a practical baseline inspired by OWASP ASVS 5.0.0. It is not a certification document. It is the working checklist for implementation, tests, and release review.

## Scope

| Area | Current code anchors | Primary risk |
| --- | --- | --- |
| Authentication/session | `SecurityConfig`, `AuthController`, `AppUserDetailsService`, `LoginAttemptService` | account takeover, weak session lifecycle, CSRF bypass |
| Remember-me | `PersistentTokenBasedRememberMeServices`, `persistent_logins` | long-lived token theft, weak rotation/cleanup |
| CSRF | `CookieCsrfTokenRepository`, frontend `api.js` | unsafe state-changing requests |
| Admin APIs | `AdminController`, `AdminAccessController`, `DriveAdminController` | privilege escalation, destructive backup/restore actions |
| Shared links | `DriveDownloadLinkService`, `TravelPublicMediaTokenService`, public media endpoints | unauthorized file/media access |
| File upload | drive, travel, family album, support inquiry, OCR upload flows | malware upload, content-type spoofing, storage abuse |
| Presigned URL | `DriveStorageService`, `TravelMediaStorageService`, MinIO config | object overwrite, excessive URL lifetime, public endpoint misuse |
| OCR/AI API keys | `LedgerOcrRemoteClient`, `LedgerAiN8nClient`, `LedgerAiLmStudioClient` | key leakage, SSRF-like backend calls, sensitive data exposure |
| Observability/audit | admin login audit, backup services, Actuator/Prometheus | missing forensic trail, undetected failure |

## Baseline Rules

| ID | Requirement | Current evidence | Required action | Verification |
| --- | --- | --- | --- | --- |
| AUTH-01 | All non-public API routes require authentication. | `SecurityConfig` permits a small explicit allowlist and authenticates all other requests. | Keep the allowlist explicit. Every new public route must be reviewed here. | Add/update controller tests for unauthenticated access returning `401` except approved public routes. |
| AUTH-02 | Login, logout, registration, profile credential changes must use CSRF protection for unsafe methods. | CSRF is enabled globally, frontend sends `X-XSRF-TOKEN`. | Ensure every POST/PUT/PATCH/DELETE path used by frontend obtains CSRF first. | MockMvc tests for missing CSRF on auth/profile/admin mutation endpoints. |
| AUTH-03 | Remember-me tokens must be persistent, random, scoped, and revocable. | Persistent token repository exists; cookie name is `CALEN_REMEMBER_ME`; `LedgerEntryUserScopeIntegrationTest` covers non-opt-in no-restore, restore without session, logout old-cookie rejection, and auto-login token rotation; `ProfileCredentialIntegrationTest` covers password and secondary PIN changes clearing the remember-me cookie and rejecting the old token; `AdminDashboardIntegrationTest` covers admin user deactivation rejecting the target user old remember-me cookie; `ProfileCredentialIntegrationTest.rememberMeCookieUsesExpectedSecurityAttributes` covers remember-me cookie name, `HttpOnly`, path, max-age, and local secure-cookie default; `docs/remember_me_security_review.md` records the remaining SameSite/deployment-policy follow-up. | Set `APP_SECURITY_REMEMBER_ME_SECURE_COOKIE=true` in HTTPS production and document SameSite/header policy. | Integration tests: login without remember does not restore, logout removes token/cookie, old token cannot re-authenticate, token rotates after auto-login, and profile credential changes reject the old remember-me cookie, and admin deactivation rejects the target user old remember-me cookie, and cookie attributes are asserted. |
| AUTH-04 | Repeated login failures must be throttled and audited. | `LoginAttemptService` and login audit code exist. | Document thresholds and lockout semantics. Ensure admin reset exists and is audited. | Tests for failed login threshold, blocked IP state, admin unblock path. |
| AUTH-05 | Passwords and secondary PINs must be hashed using an adaptive password hashing function. | `BCryptPasswordEncoder` is configured. | Keep raw credentials out of logs and DTO responses. | Unit tests or JSON assertions that profile/admin responses never expose password/PIN hashes. |
| ACCESS-01 | Admin APIs require admin role, recent secondary verification, and CSRF on unsafe methods. | `AdminController` and `DriveAdminController` call `adminPageAccessService.requireVerified`; admin services still enforce admin role. | Keep every new admin-like route behind admin role, recent secondary verification, and CSRF for mutation methods. | `AdminDashboardIntegrationTest` covers `/api/admin` dashboard/data-management/user-active denial, admin access verification auth/role/CSRF checks, and missing-CSRF rejection for backup, downloadable backup, restore, blocked-IP clear, and user-active mutation routes; `DriveAdminSecurityIntegrationTest` covers `/api/administrator` denial before verification and PATCH CSRF enforcement. |
| ACCESS-02 | Object access must be owner-scoped by authenticated user ID. | Services usually accept `currentUser.userId()`. | Audit drive, travel, family, ledger repository queries for owner/user predicates. | Repository/service tests for cross-user object access denial. |
| ACCESS-03 | Shared resources must enforce explicit share grant or valid token. | Drive shared routes, travel public media token service, and drive public-link access logs exist. | Keep expiry, revocation, owner-only log viewing, and token-fingerprint-only storage for public links. | Tests for expired/revoked/invalid/limit-reached token, invalid-item/trashed source object, invalid travel media/token pairs, token-fingerprint-only log storage, presigned download URL success logging without loading file bytes, owner-scoped access log viewing including non-owner denial, and bounded status-specific access-log writes. |
| FILE-01 | Upload size limits must be defined per feature. | Spring multipart max is 1GB; OCR has `LEDGER_OCR_MAX_FILE_SIZE`; support attachments are limited to 5MB; Drive presigned upload initialization rejects known extension/content-type mismatches before storage access; travel storage validates several flows. | Define remaining per-feature limits for drive, travel media, family album, OCR. | Tests for too-large file rejection per upload endpoint. |
| FILE-02 | Upload content type must be validated server-side and not trusted from filename only. | Travel/family media and support attachments validate MIME/extension alignment; Drive upload initialization validates known extension/content-type alignment; support attachments and OCR also check image signatures; OCR checks image-like upload path before remote calls. | Centralize allowed MIME/extension rules per feature. Reject ambiguous files. | Tests for empty uploads, spoofed extension/content type mismatch, fake image signatures, and no remote call before validation succeeds. |
| FILE-03 | Uploaded image processing must fail closed. | Thumbnail and EXIF services catch some failures. | Ensure malformed image cannot create partially trusted records or leak stack traces. | Tests with malformed image bytes for travel/family/profile/OCR upload. |
| PRESIGN-01 | Presigned upload URLs must be short-lived and object-key scoped. | MinIO expiry configurable; travel object key validation rejects wrong owner/record prefixes and unsafe path segments. | Review all generated object keys for owner/record scoping and overwrite prevention. | Tests that user A cannot complete user B's presigned object key. |
| AI-01 | AI/OCR calls must be backend-only and disabled by default unless explicitly configured. | OCR and AI config flags exist; browser calls backend. | Keep provider URLs out of frontend. Keep `.env.example` placeholders only. | Config tests for disabled state and status response; status JSON must not include provider URLs. |
| AI-02 | AI/OCR API keys must never be logged or returned to frontend. | Properties store API keys; status response returns configured flags, not keys. | Review exception messages and logs for key inclusion. | Tests or grep gate for `apiKey`, API-key header names, provider URLs, and high-risk committed secret patterns. |
| AUDIT-01 | High-risk admin actions must be audited. | Login audit exists and records `ADMIN_ACTION` detail for backup, restore, user activation, blocked-IP clear actions, and Drive admin storage/user mutations; `docs/admin_audit_log_contract.md` lists the required action codes and safe-detail policy. | Keep the same audit pattern for future destructive operations and update the contract for every new admin-like mutation. | `LoginAuditLogServiceTest`, `DriveAdminSecurityIntegrationTest`, and `scripts/verify-admin-audit-contract.ps1` assert audit event creation, safe detail values, and CI coverage. |
| OBS-01 | Security-relevant failures should emit metrics/alerts. | Actuator/Prometheus exposed; Prometheus rules cover backend availability, 5xx/latency, AI/OCR, external workflows, backup, Redis, MinIO, DB pool, public-link abuse, JVM heap, and host disk. | Keep alert labels bounded and add rules when new security-relevant failure modes are introduced. | `scripts/verify-prometheus-alerts.ps1` plus Prometheus scrape or unit tests for new meter registration. |

## Public Route Allowlist Review

Current explicit public routes from `SecurityConfig`:

| Route | Reason | Follow-up |
| --- | --- | --- |
| `/api/auth/csrf` | CSRF bootstrap | Keep public. No sensitive data except CSRF metadata. |
| `/api/auth/login` | login | Rate-limit and audit failures. |
| `/api/auth/me` | identity probe | Confirm unauthenticated response is minimal. |
| `/api/auth/logout` | logout | Confirm CSRF behavior and remember-me cookie/token cleanup. |
| `/api/invites/*`, `/api/invites/accept` | invitation flow | Token must stay hashed at rest and expire. |
| `/api/file/public-download/*` | public drive download token | Access attempts are logged by status without storing raw tokens. Keep expiry/revoke/access log tests for direct file responses and presigned download URL responses. |
| `/actuator/health`, `/actuator/prometheus` | operations | Prometheus should be network-restricted in production. |
| `/error` | framework error endpoint | Ensure no stack trace leak in production. |

## Immediate Test Backlog

| Priority | Test | Target |
| --- | --- | --- |
| P0 | unauthenticated, non-admin, unverified-admin, and missing-CSRF requests cannot call admin dashboard/data-management/backup/restore/blocked-IP/user mutation routes | `AdminController`, `AdminDashboardIntegrationTest` |
| P0 | admin access verification itself requires admin role and CSRF before establishing a verified admin session | `AdminAccessController`, `AdminDashboardIntegrationTest` |
| P0 | keep `/api/administrator/**` covered for unauthenticated, non-admin, unverified-admin denial, and CSRF rejection on mutation routes | `DriveAdminController`, `DriveAdminSecurityIntegrationTest` |
| P0 | missing CSRF rejects profile verification/password/PIN/admin mutation requests | `SecurityConfig`, `ProfileCredentialIntegrationTest`, `AdminDashboardIntegrationTest` |
| P0 | public download token fails when token is blank/invalid, item is non-file/trashed/missing storage path, or link is revoked/expired/over limit, and every success/failure path writes owner-scoped bounded access-log status without raw token storage | `DriveDownloadLinkServiceTest`, `DriveDownloadLinkAccessLogServiceTest` |
| P0 | travel public media token accepts only the issued media/token pair and rejects null, blank, tampered, wrong-media, or wrong-secret tokens | `TravelPublicMediaTokenServiceTest` |
| P1 | OCR rejects empty, too-large, MIME/extension mismatch, and fake image-signature uploads before remote OCR calls; invalid upload failures emit bounded `invalid_file` metrics without user notifications | `LedgerOcrService`, `LedgerOcrServiceTest` |
| P1 | Drive upload initialization rejects known extension/content-type mismatches before MinIO presigned URL generation while allowing generic octet-stream fallbacks | `DriveStorageService`, `DriveStorageServiceTest` |
| P1 | AI status never exposes API keys, API-key headers, or provider URLs | `LedgerAiAnalysisServiceTest.statusDoesNotExposeProviderUrlsOrApiKeys`, `LedgerAiAnalysisStatusResponse` |
| P1 | presigned upload completion rejects object key outside expected owner/record scope before MinIO stat | `TravelMediaStorageServiceTest`, `TravelMediaStorageService` |
| P1 | admin backup/restore/user activation/blocked-IP and drive-admin mutation actions produce safe `ADMIN_ACTION` audit events and stay listed in the admin audit contract | `AdminController`, `DriveAdminController`, `LoginAuditLogServiceTest`, `DriveAdminSecurityIntegrationTest`, `docs/admin_audit_log_contract.md`, `scripts/verify-admin-audit-contract.ps1` |
| P1 | privacy controls delete/revoke/export only the current user data, require auth/CSRF, require secondary PIN for export, and exclude operational secrets from archives | `PrivacyControllerIntegrationTest`, `PrivacyManagementServiceTest`, `DataPortabilityExportServiceTest`, `docs/privacy_control_panel.md`, `docs/data_portability.md`, `scripts/verify-privacy-control-contract.ps1` |`r`n| P1 | remember-me logout, token rotation, profile credential-change revocation, admin-deactivation revocation, and cookie attributes stay covered | `SecurityConfig`, `AuthController`, `AdminService`, `LedgerEntryUserScopeIntegrationTest`, `ProfileCredentialIntegrationTest`, `AdminDashboardIntegrationTest` |
| P1 | large media upload and thumbnail reprocessing stay separated, bounded, owner-scoped, fail-closed, and safe for queue payloads | `TravelMediaStorageService`, `TravelThumbnailBackfillService`, `ImageThumbnailService`, `docs/media_processing_queue_contract.md`, `scripts/verify-media-processing-contract.ps1` |`n| P2 | malformed image upload cannot create trusted thumbnail/media record | travel/family/profile upload services |

## Release Gate

Before promoting a build that changes auth, admin, sharing, upload, OCR, AI, or backup behavior:

1. Run backend security-focused tests. GitHub Actions now exposes this as `backend-security-tests` and runs admin, remember-me/profile, privacy, drive sharing/public-link, travel public-media token, OCR, and ledger AI safety tests explicitly. The separate `admin-audit-contract` job verifies high-risk admin action audit coverage and safe detail policy. The `privacy-control-contract` job verifies destructive privacy controls, data export protections, and safe archive manifests. The `media-processing-contract` job verifies large media upload and thumbnail reprocessing boundaries.
2. Verify `.env.example` and `application.yml` have matching public configuration names.
3. Run `scripts/scan-secrets.ps1` and confirm no real secrets are present in committed files.
4. Run `scripts/verify-db-migrations.ps1` when schema files change.
5. Confirm public routes are still intentionally listed in this document.
6. Run `scripts/verify-prometheus-alerts.ps1` when alert rules or observability docs change.
7. Run `scripts/verify-e2e-smoke-checklist.ps1` and attach P0 browser smoke evidence for changed frontend-critical flows.
8. Confirm operational dashboards or alerts cover the changed failure mode.

## CI Security Gate

The `backend-security-tests` GitHub Actions job intentionally duplicates a focused subset of `backend-test` so security-critical regressions are visible by name in branch protection and release review. Keep this list updated whenever a new P0 security test is added for authentication, admin APIs, sharing/public tokens, upload validation, OCR, AI, or privacy controls.