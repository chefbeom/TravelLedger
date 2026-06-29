# PWA and Mobile Capture Baseline

This slice makes the frontend more usable as an installable mobile workspace without changing server-side upload trust boundaries.

## Installed app shell

- `frontend/index.html` declares the manifest, theme color, Apple mobile app metadata, and a readable `TravelLedger` page title.
- `frontend/public/manifest.webmanifest` defines standalone display, icon metadata, shortcuts, and mobile-oriented categories.
- `frontend/src/registerServiceWorker.js` registers `/sw.js` only in production builds.
- `frontend/public/sw.js` caches only the app shell and static same-origin GET assets. It deliberately skips `/api/*` and non-GET requests so private ledger, drive, travel, OCR, and AI responses are not stored by the service worker.
- `frontend/public/offline.html` provides a safe navigation fallback when the app shell is installed but the network is unavailable.

## Mobile camera upload hints

The following image upload inputs now include `capture="environment"` so supported mobile browsers can open the rear camera directly:

- Calendar/ledger image capture flows in `CalendarWorkspace.vue`.
- Family album media uploads in `FamilyAlbumWorkspace.vue`.
- Travel memory photo uploads in `TravelMemoryPanel.vue`.
- CalenDrive profile image upload in `CalenDriveProfileModal.vue`.

These are client-side hints only. The backend MIME, extension, size, OCR, thumbnail, and privacy checks remain the authority.

## Remaining backlog

| Area | Next step |
| --- | --- |
| Offline temporary upload queue | Store selected upload intents in IndexedDB with explicit user retry, size limits, and encryption/privacy review. |
| Install prompt UX | Add a small non-blocking install prompt that respects browser support and user dismissal. |
| Mobile capture choice | Add separate `Take photo` and `Choose from library` buttons where `capture` is too aggressive for album workflows. |
| E2E coverage | Add mobile viewport smoke tests for install shell, OCR capture, travel photo upload, and family album upload. |