# Privacy Control Panel Backend Slice

Updated: 2026-06-30

This document records the backend and UI contract for user-facing privacy controls. The privacy management screen should let a signed-in user download their data, revoke shared links, delete AI analysis history, remove photo location metadata, and run a combined sensitive-data cleanup after explicit confirmation.

## Implemented Controls

| Control | Endpoint | Behavior |
| --- | --- | --- |
| Delete my AI analysis history | `DELETE /api/privacy/ai-analysis-history` | Permanently deletes ledger AI analysis history rows owned by the current user. |
| Delete one AI analysis history item | `DELETE /api/statistics/ai-analysis/history/{historyId}` | Permanently deletes one AI analysis history row only when it belongs to the current user. |
| Delete all AI analysis history from AI screen | `DELETE /api/statistics/ai-analysis/history` | Permanently deletes all ledger AI analysis history rows owned by the current user from the AI history UI context. |
| Revoke my public drive links | `DELETE /api/privacy/public-download-links` | Sets `revokedAt` on all active public download links owned by the current user. |
| Revoke my travel public media shares | `DELETE /api/privacy/travel-public-media-shares` | Disables public sharing on the current user's travel plans and community-shared travel memory records so existing stateless media tokens no longer pass visibility checks. |
| Remove my photo location metadata | `DELETE /api/privacy/photo-location-metadata` | Clears derived travel photo GPS latitude, longitude, and extraction timestamp fields owned by the current user. |
| Cleanup sensitive derived data | `POST /api/privacy/cleanup` | Runs AI-history deletion, public drive-link revocation, travel public-media share revocation, and photo location metadata removal in one authenticated request. |
| Download my ledger data archive | `POST /api/privacy/data-export` | Downloads a secondary-PIN-protected zip containing ledger CSV and export metadata. |

## Privacy management screen contract

| Screen card | Primary action | Confirmation requirement | Success evidence |
| --- | --- | --- | --- |
| My data download | Request `POST /api/privacy/data-export` after secondary PIN verification. | Explain that the current archive contains ledger CSV and safe manifests; binary photos/files require the async archive job from the data portability contract. | Download starts and the user sees the protected archive scope/date range. |
| Revoke all shared links | Call `DELETE /api/privacy/public-download-links` and `DELETE /api/privacy/travel-public-media-shares`, or include both through `POST /api/privacy/cleanup`. | Explain that Drive public links are revoked while owner audit metadata remains, and Travel public media visibility is disabled so stateless URLs stop working. | Show counts for `publicDownloadLinksRevoked` and `travelPublicMediaSharesRevoked`. |
| Delete AI analysis history | Call `DELETE /api/privacy/ai-analysis-history` or AI-screen bulk deletion. | Explain the action is permanent and does not delete the source ledger entries. | Show `aiAnalysisHistoriesDeleted` count and refresh AI history state. |
| Remove photo location metadata | Call `DELETE /api/privacy/photo-location-metadata` or include it through combined cleanup. | Explain that derived GPS latitude/longitude and extraction timestamp are cleared while the media item remains. | Show `photoLocationMetadataRemoved` count and keep photo/media records accessible. |
| Sensitive cleanup | Call `POST /api/privacy/cleanup`. | Present a single review screen listing AI history deletion, Drive link revocation, Travel media-share revocation, and photo GPS cleanup before submit. | Show all returned counts, `processedAt`, and a bounded `PRIVACY_ACTION_DONE` notification. |

Screen requirements:

- Place privacy controls in the profile/privacy area, not in scattered feature screens only.
- Use explicit destructive confirmation copy for delete, revoke, and cleanup actions.
- Require secondary PIN only for archive download today; do not reuse or persist the secondary PIN value in UI state after export starts.
- Use live status regions for success/failure counts so mobile and screen-reader users can verify what changed.
- Keep action buttons disabled while a request is in flight to avoid duplicate destructive submissions.
- Never show public tokens, presigned URLs, object keys, storage paths, raw GPS coordinates, OCR text, AI prompts, provider responses, or archive contents in the privacy screen.
## Safety Rules
| Rule | Reason |
| --- | --- |
| Every endpoint is under `/api/privacy` and requires authentication. | Prevents unauthenticated destructive privacy operations. |
| Unsafe methods require CSRF. | Keeps browser-originated destructive operations protected. |
| Public drive links are revoked, not deleted. | Keeps owner-visible metadata and access-log evidence while stopping future token use. |
| Travel media tokens are stateless, so revocation disables the public share surfaces they depend on. | Existing token strings cannot be deleted from storage because they are derived from `mediaId + secret`; visibility checks must fail instead. |
| Data export requires a verified secondary PIN in the current session. | Export can contain private ledger data and should require stronger intent. |
| Export archives are encrypted with the secondary PIN. | Protects downloaded archives at rest after leaving the server. |
| Export metadata excludes operational secrets. | Keeps API keys, tokens, workflow URLs, and passwords out of user data archives. |
| Privacy screen must not become a secret viewer. | The UI shows counts, statuses, and safe explanations only, never raw tokens, storage paths, prompts, provider responses, or raw GPS coordinates. |
| Destructive actions must return visible counts. | Users need clear evidence of what was deleted, revoked, or cleaned up. |

## Response Shape

```json
{
  "aiAnalysisHistoriesDeleted": 3,
  "publicDownloadLinksRevoked": 2,
  "travelPublicMediaSharesRevoked": 4,
  "processedAt": "2026-06-30T12:00:00"
}
```

`travelPublicMediaSharesRevoked` counts disabled public travel plans plus community-shared travel memory records. It is not a count of token strings because travel public media tokens are stateless. `photoLocationMetadataRemoved` counts travel media rows whose derived GPS latitude, longitude, or extraction timestamp was cleared.

## Test Evidence

| Evidence | Coverage |
| --- | --- |
| `PrivacyManagementServiceTest.revokePublicDownloadLinksScopesUpdateToCurrentOwner` | Verifies public drive link revocation calls the owner-scoped repository method with only the authenticated user ID and returns the affected count. |
| PrivacyManagementServiceTest.revokeTravelPublicMediaSharesScopesPlanAndCommunityRecordUpdatesToCurrentOwner | Verifies travel public media share revocation disables owner-scoped public plans and community-shared records only. |
| PrivacyManagementServiceTest.removePhotoLocationMetadataScopesGpsCleanupToCurrentOwner | Verifies photo location metadata cleanup only calls the owner-scoped travel media GPS metadata update. |
| `PrivacyManagementServiceTest.cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerShares` | Verifies combined cleanup deletes only the authenticated user AI history and revokes only that user's public drive/travel share surfaces. |
| `PrivacyControllerIntegrationTest.aiAnalysisHistoryDeletionRequiresAuthenticationAndCsrf` | Verifies AI analysis history deletion rejects unauthenticated requests and authenticated unsafe requests without CSRF. |
| `LedgerAiAnalysisServiceTest.deleteHistoryDeletesOnlyCurrentOwnerHistory` | Verifies single AI history deletion uses the authenticated owner ID in the delete query. |
| `LedgerAiAnalysisServiceTest.deleteHistoryReturnsNotFoundWhenHistoryIsNotOwnedByCurrentUser` | Verifies cross-owner or missing AI history rows are reported as not found. |
| `LedgerAiAnalysisServiceTest.deleteHistoriesDeletesOnlyCurrentOwnerRows` | Verifies bulk AI history deletion from the AI screen stays owner-scoped. |
| `PrivacyControllerIntegrationTest.publicDownloadLinkRevocationRequiresAuthenticationAndCsrf` | Verifies public-link revocation rejects unauthenticated requests and authenticated unsafe requests without CSRF. |
| PrivacyControllerIntegrationTest.travelPublicMediaShareRevocationRequiresAuthenticationAndCsrf | Verifies travel public media share revocation rejects unauthenticated requests and authenticated unsafe requests without CSRF. |
| PrivacyControllerIntegrationTest.photoLocationMetadataRemovalRequiresAuthenticationAndCsrf | Verifies photo location metadata removal rejects unauthenticated requests and authenticated unsafe requests without CSRF. |
| `PrivacyControllerIntegrationTest.sensitiveCleanupRequiresAuthenticationAndCsrf` | Verifies combined sensitive cleanup rejects unauthenticated requests and authenticated unsafe requests without CSRF. |
| `PrivacyControllerIntegrationTest.dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin` | Verifies data export rejects unauthenticated requests, missing-CSRF requests, and authenticated sessions without a verified secondary PIN before returning a zip attachment. |
| `DataPortabilityExportServiceTest.exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets` | Verifies the exported archive is encrypted, contains ledger CSV plus metadata, and excludes secret-like values. |
| `DataPortabilityExportServiceTest.exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData` | Verifies secondary PIN validation happens before ledger data is exported. |

## Remaining Privacy Backlog

| Item | Notes |
| --- | --- |
| Extend photo GPS metadata cleanup to family media | Travel media derived GPS metadata cleanup is implemented; family media GPS/EXIF cleanup remains a follow-up if family uploads start storing location metadata. |
| Include binary photos/files in export | Needs size limits, async job progress, archive encryption, and retry/resume handling. |
| Extend frontend privacy panel with async large-archive export status | Current profile UI covers destructive confirmations, returned counts, and PIN-protected ledger archive download; large photo/file archive export still needs background job progress. |
| Add audit events for privacy destructive actions | Store safe event names and counts without raw file names, tokens, or export contents. |


## Contract and CI Gate

`scripts/verify-privacy-control-contract.ps1` checks that the privacy panel documentation, data portability documentation, controller endpoints, service owner-scoping snippets, export archive safety snippets, test evidence, security baseline, and GitHub Actions `privacy-control-contract` job stay aligned.

The gate treats these controls as the minimum privacy contract:

- AI analysis history deletion is authenticated, CSRF-protected, and owner-scoped.
- Public drive link revocation is authenticated, CSRF-protected, owner-scoped, and revokes instead of deleting audit evidence.
- Travel public media share revocation disables owner-scoped public surfaces that stateless media tokens depend on.
- Photo location metadata cleanup clears derived GPS values through an owner-scoped repository method.
- Combined cleanup runs AI-history deletion, drive link revocation, travel share revocation, and photo location cleanup in one authenticated request.
- Data export requires a verified secondary PIN, creates a password-protected archive, includes ledger CSV plus safe manifests, and excludes operational secrets, object paths, signed URLs, prompts, provider responses, and raw coordinates.
## UI Contract Notes

- Show one combined privacy dashboard for data download, shared-link revocation, AI history deletion, photo location metadata removal, and sensitive cleanup.
- Show destructive confirmation copy before delete/revoke/cleanup actions.
- Explain that public drive links are revoked but logs remain available to the owner.
- Explain that travel public media share revocation disables public trip/community visibility and invalidates existing stateless media URLs through visibility checks.
- Explain that AI analysis history deletion cannot be undone.
- For export, require the user to verify their secondary PIN first, then call `POST /api/privacy/data-export`.
- Keep `/api/privacy/*` authentication, CSRF, and secondary-PIN integration tests current for each new unsafe/export endpoint.
- Keep frontend status messages count-based and safe: `aiAnalysisHistoriesDeleted`, `publicDownloadLinksRevoked`, `travelPublicMediaSharesRevoked`, `photoLocationMetadataRemoved`, and `processedAt`.

