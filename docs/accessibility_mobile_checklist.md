# Accessibility and Mobile UX Checklist

Updated: 2026-06-29

This checklist turns the accessibility/mobile UX improvement track into reviewable release criteria. Use it for dashboard drag widgets, maps, CalenDrive, dialogs, PIN/auth screens, upload flows, OCR, and AI report screens.

## Priority Screens

| Priority | Area | Main risk | First acceptable slice |
| --- | --- | --- | --- |
| P0 | Login, PIN, session expiry | Users can be locked out or unable to recover from errors. | Keyboard-only login/PIN flow, visible focus, clear error text, touch-safe controls. |
| P0 | Admin dialogs and destructive actions | Focus loss or unclear confirmation can cause unsafe actions. | Focus trap, Escape/Cancel path, action-specific confirmation copy. |
| P1 | Dashboard drag widgets | Drag-only layout blocks keyboard and touch assistive users. | Add non-drag reorder controls and reset-layout action. |
| P1 | Drive/share/file upload | File pickers and share dialogs often miss labels and status updates. | Labeled controls, upload progress text, link expiry/revoke state announced. |
| P1 | Maps and travel media | Map-only information is hard for keyboard/screen-reader users. | Provide list/table alternative for routes, markers, and photos. |
| P2 | AI/OCR result review | Long generated output and failed OCR states can be hard to scan. | Section headings, status summary, retry state, and non-color-only confidence indicators. |

## WCAG 2.2 Checklist

| Check | Target behavior | Evidence |
| --- | --- | --- |
| Keyboard operation | Every interactive control is reachable and usable without a mouse. | Tab/Shift+Tab smoke pass for each priority screen. |
| Focus visibility | Focus indicator is clearly visible and not hidden behind sticky headers or modals. | Screenshot or manual note for default, modal, and mobile viewport states. |
| Focus order | Navigation order follows visual order and returns to the trigger after dialog close. | Manual keyboard trace. |
| Dialog behavior | Modal dialogs use one active focus region, close predictably, and preserve context. | Dialog checklist entry per modal family. |
| Drag alternatives | Drag/drop widgets also provide buttons, menus, or forms for the same operation. | Dashboard and file movement flows work without pointer drag. |
| Target size | Primary touch targets meet WCAG 2.2 minimum target size and have practical mobile spacing. | 360px-wide viewport review. |
| Accessible names | Icon-only buttons, inputs, upload controls, and map controls have meaningful labels. | DOM review or automated accessibility scan. |
| Error recovery | Auth, upload, OCR, AI, and backup errors explain what failed and what the user can do next. | Error state screenshot or component story. |
| Status updates | Upload progress, AI/OCR completion, and async failures are announced in text, not only spinners. | Screen-reader or DOM live-region smoke check. |
| Color and contrast | Text, icons, focus rings, error states, and chart labels do not rely on color alone. | Contrast check on light/dark or current theme states. |
| Reduced motion | Motion-heavy UI respects reduced-motion preferences. | Browser reduced-motion setting smoke check. |
| Mobile viewport | Core flows work at 360x640 without horizontal scrolling or clipped actions. | Mobile viewport smoke pass. |

## Manual Test Recipe

1. Open each priority screen at desktop width and at a 360px mobile viewport.
2. Complete the primary flow using only keyboard controls.
3. Repeat the same flow with the browser zoomed to 200%.
4. Trigger one validation error and one async failure state.
5. Confirm focus lands on useful content after navigation, modal close, upload completion, and failed AI/OCR actions.
6. Record gaps in the roadmap or issue tracker with the affected screen and checklist row.

## Implementation Queue

| Order | Change | Reason |
| --- | --- | --- |
| 1 | Add reusable focus-trap and return-focus handling for modal families. | Reduces repeated dialog accessibility bugs. |
| 2 | Add keyboard alternatives for dashboard drag/reorder. | Removes the largest pointer-only interaction. |
| 3 | Add route/marker list alternatives for map screens. | Makes travel information available without map gestures. |
| 4 | Add accessible upload status components for Drive/OCR/media flows. | Makes long-running operations understandable. |
| 5 | Add automated accessibility smoke checks for login, dashboard, drive share, and OCR review. | Prevents regressions after the manual baseline is fixed. |

## Release Gate

A release touching priority screens should include:

- A checklist note for keyboard, focus, touch target, and error-state behavior.
- A mobile viewport smoke pass at 360px width.
- A confirmation that destructive admin/share actions still have clear text and cancel paths.
- A ticket for any remaining P0 or P1 accessibility defect that is intentionally deferred.