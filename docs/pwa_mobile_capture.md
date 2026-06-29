# PWA and Mobile Capture Baseline

Updated: 2026-06-29

This document records the first PWA/mobile capture slice for TravelLedger. The goal is to make the app installable and safer to open on mobile before adding richer offline drafts and camera-first upload screens.

## Implemented Baseline

| Area | Current behavior |
| --- | --- |
| Web app manifest | `frontend/public/manifest.webmanifest` declares standalone display, theme color, app name, icon, start URL, and shortcuts. |
| Service worker | `frontend/public/sw.js` caches only app-shell assets and same-origin static GET assets. |
| API privacy | `/api/*` requests are intentionally excluded from service-worker caching. |
| Registration | `frontend/src/registerServiceWorker.js` registers the service worker only in production builds. |
| Install metadata | `frontend/index.html` includes manifest, theme color, and Apple mobile web app metadata. |

## Mobile Capture Rules

| Rule | Reason |
| --- | --- |
| Use camera-friendly file inputs for receipt, travel photo, and family album upload flows. | Mobile users should be able to capture directly instead of browsing the file system. |
| Keep captured files in browser memory or IndexedDB drafts only after explicit user action. | Receipts and family photos are sensitive personal data. |
| Do not cache API responses, signed URLs, AI analysis payloads, or file blobs in the service worker. | Prevents stale private data from being exposed through browser cache. |
| Show offline state before allowing upload submission. | Uploads, OCR, AI, and presigned completion require network connectivity. |
| Keep offline drafts user-clearable. | Users need a privacy escape hatch for captured but unsent files. |

## Next Implementation Queue

| Order | Slice | Acceptance evidence |
| --- | --- | --- |
| 1 | Add a shared mobile capture component for OCR receipts, travel media, and family album uploads. | Inputs expose camera capture affordance and clear file type labels on mobile. |
| 2 | Add an offline network banner and disable upload submit while offline. | Offline state is visible without relying on browser errors. |
| 3 | Add encrypted or clearable IndexedDB drafts for forms without binary blobs first. | User can recover and clear text metadata drafts. |
| 4 | Add binary draft support only after retention and privacy controls are defined. | Drafted files can be listed, retried, and deleted by the user. |
| 5 | Add mobile PWA smoke checks to CI or release checklist. | Installability and service-worker registration are checked on production build. |

## Manual Smoke Checklist

1. Build and serve the production frontend.
2. Confirm browser install prompt or installability metadata is available.
3. Confirm `sw.js` is registered in production only.
4. Open the app once, then reload while offline and confirm the shell loads.
5. Confirm authenticated API calls are not served from service-worker cache.
6. Confirm upload flows show clear file type labels before adding camera capture support.

## Known Limits

- This slice does not yet add offline form drafts.
- This slice does not yet alter OCR/travel/family upload components.
- The current `Ledger.png` asset is reused as the PWA icon; a dedicated maskable icon set should replace it later.