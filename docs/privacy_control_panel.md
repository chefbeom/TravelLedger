# Privacy Control Panel Backend Slice

Updated: 2026-06-29

This document records the first backend slice for user-facing privacy controls. The UI can call these endpoints after showing an explicit confirmation dialog.

## Implemented Controls

| Control | Endpoint | Behavior |
| --- | --- | --- |
| Delete my AI analysis history | `DELETE /api/privacy/ai-analysis-history` | Permanently deletes ledger AI analysis history rows owned by the current user. |
| Revoke my public drive links | `DELETE /api/privacy/public-download-links` | Sets `revokedAt` on all active public download links owned by the current user. |
| Cleanup sensitive derived data | `POST /api/privacy/cleanup` | Runs both AI-history deletion and public-link revocation in one authenticated request. |

## Safety Rules

| Rule | Reason |
| --- | --- |
| Current user ID comes only from `@AuthenticationPrincipal`. | Prevents users from targeting another account by request body or query parameter. |
| Public links are revoked, not deleted. | Keeps owner-visible metadata and access-log evidence while stopping future token use. |
| AI history deletion is hard-delete. | AI history stores derived prompts/results and is user-controlled sensitive data. |
| Endpoints are not public allowlist routes. | CSRF and authenticated-session protections apply like other state-changing APIs. |
| UI must show counts returned by the backend. | Users need confirmation of how many derived records were affected. |

## Response Contract

```json
{
  "aiAnalysisHistoriesDeleted": 3,
  "publicDownloadLinksRevoked": 2,
  "processedAt": "2026-06-29T12:34:56"
}
```

## Next Controls

| Candidate | Notes |
| --- | --- |
| Strip GPS metadata from travel/family photos | Needs object rewrite path and thumbnail invalidation. |
| Download my data archive | Should include ledger CSV, metadata JSON, and owned file manifest. |
| Revoke shared travel/public media tokens | Mirror the drive-link revocation pattern for travel media. |
| Delete OCR upload artifacts | Needs artifact inventory and retention policy first. |

## Test Backlog

- Current user can delete only their own AI history.
- Current user can revoke only their own public drive links.
- Revoked links keep access logs and no longer resolve.
- `/api/privacy/*` rejects unauthenticated requests and unsafe requests without CSRF.