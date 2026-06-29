# Privacy Control Panel Backend Slice

Updated: 2026-06-30

This document records the backend slice for user-facing privacy controls. The UI can call these endpoints after showing an explicit confirmation dialog.

## Implemented Controls

| Control | Endpoint | Behavior |
| --- | --- | --- |
| Delete my AI analysis history | `DELETE /api/privacy/ai-analysis-history` | Permanently deletes ledger AI analysis history rows owned by the current user. |
| Delete one AI analysis history item | `DELETE /api/statistics/ai-analysis/history/{historyId}` | Permanently deletes one AI analysis history row only when it belongs to the current user. |
| Delete all AI analysis history from AI screen | `DELETE /api/statistics/ai-analysis/history` | Permanently deletes all ledger AI analysis history rows owned by the current user from the AI history UI context. |
| Revoke my public drive links | `DELETE /api/privacy/public-download-links` | Sets `revokedAt` on all active public download links owned by the current user. |
| Revoke my travel public media shares | `DELETE /api/privacy/travel-public-media-shares` | Disables public sharing on the current user's travel plans and community-shared travel memory records so existing stateless media tokens no longer pass visibility checks. |
| Cleanup sensitive derived data | `POST /api/privacy/cleanup` | Runs AI-history deletion, public drive-link revocation, travel public-media share revocation, and photo location metadata removal in one authenticated request. |
| Download my ledger data archive | `POST /api/privacy/data-export` | Downloads a secondary-PIN-protected zip containing ledger CSV and export metadata. |

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
| Remove photo GPS metadata | Add owner-scoped EXIF/location stripping for travel/family media. |
| Include binary photos/files in export | Needs size limits, async job progress, archive encryption, and retry/resume handling. |
| Extend frontend privacy panel with async large-archive export status | Current profile UI covers destructive confirmations, returned counts, and PIN-protected ledger archive download; large photo/file archive export still needs background job progress. |
| Add audit events for privacy destructive actions | Store safe event names and counts without raw file names, tokens, or export contents. |

## UI Contract Notes

- Show destructive confirmation copy before delete/revoke/cleanup actions.
- Explain that public drive links are revoked but logs remain available to the owner.
- Explain that travel public media share revocation disables public trip/community visibility and invalidates existing stateless media URLs through visibility checks.
- Explain that AI analysis history deletion cannot be undone.
- For export, require the user to verify their secondary PIN first, then call `POST /api/privacy/data-export`.
- Keep `/api/privacy/*` authentication, CSRF, and secondary-PIN integration tests current for each new unsafe/export endpoint.