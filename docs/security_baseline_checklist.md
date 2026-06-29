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
| AUTH-03 | Remember-me tokens must be persistent, random, scoped, and revocable. | Persistent token repository exists; cookie name is `CALEN_REMEMBER_ME`. | Add explicit cookie security review: `Secure`, `SameSite`, domain/path, expiration, logout deletion. | Integration test: login with remember, logout removes token/cookie, old token cannot re-authenticate. |
| AUTH-04 | Repeated login failures must be throttled and audited. | `LoginAttemptService` and login audit code exist. | Document thresholds and lockout semantics. Ensure admin reset exists and is audited. | Tests for failed login threshold, blocked IP state, admin unblock path. |
| AUTH-05 | Passwords and secondary PINs must be hashed using an adaptive password hashing function. | `BCryptPasswordEncoder` is configured. | Keep raw credentials out of logs and DTO responses. | Unit tests or JSON assertions that profile/admin responses never expose password/PIN hashes. |
| ACCESS-01 | Admin APIs require admin role, recent secondary verification, and CSRF on unsafe methods. | `AdminController` and `DriveAdminController` call `adminPageAccessService.requireVerified`; admin services still enforce admin role. | Keep every new admin-like route behind admin role, recent secondary verification, and CSRF for mutation methods. | `AdminDashboardIntegrationTest` covers `/api/admin` dashboard/data-management/user-active denial and PATCH CSRF enforcement; `DriveAdminSecurityIntegrationTest` covers `/api/administrator` denial before verification and PATCH CSRF enforcement. |
| ACCESS-02 | Object access must be owner-scoped by authenticated user ID. | Services usually accept `currentUser.userId()`. | Audit drive, travel, family, ledger repository queries for owner/user predicates. | Repository/service tests for cross-user object access denial. |
| ACCESS-03 | Shared resources must enforce explicit share grant or valid token. | Drive shared routes, travel public media token service, and drive public-link access logs exist. | Keep expiry, revocation, owner-only log viewing, and token-fingerprint-only storage for public links. | Tests for expired/revoked/invalid token, invalid travel media/token pairs, deleted/trashed source object, and owner-scoped access log viewing. |
| FILE-01 | Upload size limits must be defined per feature. | Spring multipart max is 1GB; OCR has `LEDGER_OCR_MAX_FILE_SIZE`; support attachments are limited to 5MB; travel storage validates several flows. | Define remaining per-feature limits for drive, travel media, family album, OCR. | Tests for too-large file rejection per upload endpoint. |
| FILE-02 | Upload content type must be validated server-side and not trusted from filename only. | Travel/family media and support attachments validate MIME/extension alignment; support attachments and OCR also check image signatures; OCR checks image-like upload path before remote calls. | Centralize allowed MIME/extension rules per feature. Reject ambiguous files. | Tests for spoofed extension/content type mismatch. |
| FILE-03 | Uploaded image processing must fail closed. | Thumbnail and EXIF services catch some failures. | Ensure malformed image cannot create partially trusted records or leak stack traces. | Tests with malformed image bytes for travel/family/profile/OCR upload. |
| PRESIGN-01 | Presigned upload URLs must be short-lived and object-key scoped. | MinIO expiry configurable; travel object key validation rejects wrong owner/record prefixes and unsafe path segments. | Review all generated object keys for owner/record scoping and overwrite prevention. | Tests that user A cannot complete user B's presigned object key. |
| AI-01 | AI/OCR calls must be backend-only and disabled by default unless explicitly configured. | OCR and AI config flags exist; browser calls backend. | Keep provider URLs out of frontend. Keep `.env.example` placeholders only. | Config tests for disabled state and status response; status JSON must not include provider URLs. |
| AI-02 | AI/OCR API keys must never be logged or returned to frontend. | Properties store API keys; status response returns configured flags, not keys. | Review exception messages and logs for key inclusion. | Tests or grep gate for `apiKey`, API-key header names, provider URLs, and high-risk committed secret patterns. |
| AUDIT-01 | High-risk admin actions must be audited. | Login audit exists and records `ADMIN_ACTION` detail for backup, restore, user activation, and blocked-IP clear actions. | Extend the same pattern to drive admin changes and future destructive operations. | Unit/integration tests asserting audit event creation and safe detail values. |
| OBS-01 | Security-relevant failures should emit metrics/alerts. | Actuator/Prometheus exposed. | Add counters for login block, CSRF failure, AI/OCR failure, backup failure, public link invalid access. | Prometheus scrape or unit tests for meter registration. |

## Public Route Allowlist Review

Current explicit public routes from `SecurityConfig`:

| Route | Reason | Follow-up |
| --- | --- | --- |
| `/api/auth/csrf` | CSRF bootstrap | Keep public. No sensitive data except CSRF metadata. |
| `/api/auth/login` | login | Rate-limit and audit failures. |
| `/api/auth/me` | identity probe | Confirm unauthenticated response is minimal. |
| `/api/auth/logout` | logout | Confirm CSRF behavior and cookie cleanup. |
| `/api/invites/*`, `/api/invites/accept` | invitation flow | Token must stay hashed at rest and expire. |
| `/api/file/public-download/*` | public drive download token | Access attempts are logged by status without storing raw tokens. Keep expiry/revoke/access log tests. |
| `/actuator/health`, `/actuator/prometheus` | operations | Prometheus should be network-restricted in production. |
| `/error` | framework error endpoint | Ensure no stack trace leak in production. |

## Immediate Test Backlog

| Priority | Test | Target |
| --- | --- | --- |
| P0 | unauthenticated, non-admin, unverified-admin, and missing-CSRF requests cannot call admin dashboard/data-management/user mutation routes | `AdminController`, `AdminDashboardIntegrationTest` |
| P0 | keep `/api/administrator/**` covered for unauthenticated, non-admin, unverified-admin denial, and CSRF rejection on mutation routes | `DriveAdminController`, `DriveAdminSecurityIntegrationTest` |
| P0 | missing CSRF rejects profile verification/password/PIN/admin mutation requests | `SecurityConfig`, `ProfileCredentialIntegrationTest`, `AdminDashboardIntegrationTest` |
| P0 | public download token fails when token is blank/invalid, item is non-file/trashed/missing storage path, or link is revoked/expired/over limit, and each attempt writes an owner-scoped access log without raw token storage | `DriveDownloadLinkServiceTest`, `DriveDownloadLinkAccessLogServiceTest` |
| P0 | travel public media token accepts only the issued media/token pair and rejects null, blank, tampered, wrong-media, or wrong-secret tokens | `TravelPublicMediaTokenServiceTest` |
| P1 | OCR rejects too-large files, MIME/extension mismatches, and fake image signatures before remote OCR calls | `LedgerOcrService`, `LedgerOcrServiceTest` |
| P1 | AI status never exposes API keys, API-key headers, or provider URLs | `LedgerAiAnalysisServiceTest.statusDoesNotExposeProviderUrlsOrApiKeys`, `LedgerAiAnalysisStatusResponse` |
| P1 | presigned upload completion rejects object key outside expected owner/record scope before MinIO stat | `TravelMediaStorageServiceTest`, `TravelMediaStorageService` |
| P1 | admin backup/restore/user activation/blocked-IP actions produce audit events | `AdminController`, `LoginAuditLogServiceTest` |
| P2 | malformed image upload cannot create trusted thumbnail/media record | travel/family/profile upload services |

## Release Gate

Before promoting a build that changes auth, admin, sharing, upload, OCR, AI, or backup behavior:

1. Run backend security-focused tests.
2. Verify `.env.example` and `application.yml` have matching public configuration names.
3. Run `scripts/scan-secrets.ps1` and confirm no real secrets are present in committed files.
4. Confirm public routes are still intentionally listed in this document.
5. Confirm operational dashboards or alerts cover the changed failure mode.
