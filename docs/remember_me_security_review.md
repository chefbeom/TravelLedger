# Remember-me Security Review

Updated: 2026-06-30

This review captures the current remember-me implementation and the remaining hardening work. Remember-me is useful for user convenience, but it is a long-lived bearer credential and should be treated like a revocable session token.

## Current Implementation

| Area | Current state |
| --- | --- |
| Service | `PersistentTokenBasedRememberMeServices` configured in `SecurityConfig`. |
| Token repository | `JdbcTokenRepositoryImpl` backed by `persistent_logins`. |
| Table shape | `username`, `series` primary key, `token`, `last_used`. |
| Cookie name | `CALEN_REMEMBER_ME`. |
| Validity | `app.security.remember-me-token-validity-seconds`, default `2592000` seconds. |
| Login behavior | `AuthController.signIn` calls `rememberMeServices.loginSuccess` only when `rememberDevice=true`; otherwise it calls `rememberMeServices.logout`. |
| Logout behavior | `AuthController.clearAuthentication` calls `rememberMeServices.logout`, clears the security context, and invalidates the HTTP session. |
| Existing test evidence | `LedgerEntryUserScopeIntegrationTest.rememberMeRestoresUserWithoutSession` proves a remember-me cookie can restore `/api/auth/me` without a session; `logoutRevokesRememberMeCookie` proves logout clears the cookie and old cookie reuse is unauthorized; `rememberMeAutoLoginRotatesTokenAndRejectsPreviousCookie` proves remember-me auto-login rotates the token and rejects the previous cookie; `loginWithoutRememberDeviceDoesNotIssueReusableRememberMeCookie` proves non-opt-in login does not create a reusable remember-me cookie. |

## Security Invariants

| ID | Invariant | Why it matters | Evidence or gap |
| --- | --- | --- | --- |
| RM-01 | Remember-me cookies are issued only after explicit user opt-in. | Avoids silently creating long-lived browser credentials. | `LedgerEntryUserScopeIntegrationTest.loginWithoutRememberDeviceDoesNotIssueReusableRememberMeCookie` covers `rememberDevice=false` not restoring authentication. |
| RM-02 | Logout revokes the persistent token and clears the browser cookie. | A stolen or old remember-me cookie should not keep working after logout. | `LedgerEntryUserScopeIntegrationTest.logoutRevokesRememberMeCookie` covers cookie clearing and old-cookie rejection after logout. |
| RM-03 | Cookie attributes are production-safe. | Long-lived cookies need `HttpOnly`, `Secure`, path/domain, expiry, and SameSite review. | Cookie name/validity are explicit; add environment review for `Secure` and SameSite behavior behind HTTPS/proxy. |
| RM-04 | Persistent tokens are random and rotated by Spring Security. | Reduces replay window and token prediction risk. | `LedgerEntryUserScopeIntegrationTest.rememberMeAutoLoginRotatesTokenAndRejectsPreviousCookie` covers token value rotation and previous-cookie rejection after auto-login. |
| RM-05 | Token storage does not expose plaintext credentials. | DB compromise should not reveal passwords or secondary PINs. | `persistent_logins` stores series/token only; no password/PIN fields. |
| RM-06 | Account deactivation or password/PIN reset should revoke old remember-me sessions. | Credential/account changes should cut off old devices. | Gap: define revocation policy and tests for password/PIN change and admin user deactivation. |

## Cookie Attribute Review

| Attribute | Desired production posture | Current evidence | Follow-up |
| --- | --- | --- | --- |
| Name | Stable, non-generic name. | `CALEN_REMEMBER_ME`. | Keep stable for tests and logout deletion. |
| HttpOnly | Enabled. | Spring Security remember-me cookies are intended for HTTP-only session use; confirm with MockMvc/browser smoke. | Add assertion on issued cookie. |
| Secure | Enabled when served over HTTPS. | Not explicitly configured in `SecurityConfig`. | Decide whether to force secure cookie in production or rely on request security/proxy headers. |
| SameSite | `Lax` or stricter unless cross-site embedding is required. | Not explicitly configured for remember-me cookie. | Add deployment-level cookie policy or custom response handling if Spring Security default is insufficient. |
| Path | App-wide path only. | Spring Security default path behavior. | Add cookie assertion in auth integration test. |
| Max age | Matches token validity and product expectation. | Default validity is 30 days. | Confirm product requirement; consider shorter duration for shared devices. |

## Immediate Test Backlog

| Priority | Test | Expected result |
| --- | --- | --- |
| P0 | Keep login with `rememberDevice=false` non-restore covered. | `loginWithoutRememberDeviceDoesNotIssueReusableRememberMeCookie` expects new unauthenticated requests to remain `401` and any clearing cookie to be unusable. |
| P0 | Keep login with `rememberDevice=true`, logout with CSRF, then reuse old remember-me cookie covered. | `logoutRevokesRememberMeCookie` expects logout cookie clearing and old-cookie `401`. |
| P0 | Keep remember-me auto-login token rotation covered. | `rememberMeAutoLoginRotatesTokenAndRejectsPreviousCookie` expects a new cookie value and old-cookie `401`. |
| P1 | Password change revokes remember-me tokens for the account. | Old remember-me cookie fails after password change. |
| P1 | Secondary PIN change revokes remember-me tokens for the account. | Old remember-me cookie fails after PIN change. |
| P1 | Admin deactivates user. | Old remember-me cookie fails for inactive account. |
| P1 | Cookie attribute assertions. | Cookie is HttpOnly, production-secure, scoped, expires as expected, and SameSite policy is documented. |

## Release Gate

Before changing authentication, logout, profile credential mutation, admin user activation, or remember-me configuration:

1. Confirm remember-me restore still works only for valid persistent tokens.
2. Confirm logout and credential/account changes revoke or invalidate old remember-me sessions.
3. Confirm cookie attributes are appropriate for the deployment environment.
4. Confirm no remember-me token, series, password, secondary PIN, or API key appears in logs or response bodies.