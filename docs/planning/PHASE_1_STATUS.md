# Phase 1 implementation status

This is a snapshot of implementation and verification, not a release-readiness claim. The acceptance criteria refer to [MVP_SPEC.md](../product/MVP_SPEC.md#6-mvp-acceptance-criteria).

## Delivered foundation

- Android debug app and Kotlin Multiplatform shared core are in place; Android CI compiles the common metadata, runs shared-core tests and lint, builds the debug APK, and uploads it as a 30-day Actions artifact.
- SQLDelight schema v3 migrates a populated v1 pantry without changing its existing IDs or stock levels. Tests cover the v1-to-v3 migration, canonical UUIDv7 generation, family-level shop unlock counting, recipe-family explicit association, tasting-note author attribution, and revision payload/parent links.
- Household recipe variants can now be archived with an immutable deletion revision and tombstone. Archived variants stay in storage/history and disappear from browse results; repeated archive attempts do not create another deletion.
- Local household and member profiles, multiple homes, per-home approximate inventory, persisted manual home/member choices, recipe entry, grouped variants, style filters, tasting notes, and the initial At the shop spirit ranking are implemented.
- Recipe catalog remains empty by design pending record-level rights review. No credentials, model providers, or network service are required for the app's current workflows.

## Acceptance criteria

| # | State | Evidence or remaining work |
|---|---|---|
| 1 | Partial | Offline-capable local storage, homes, pantry, recipe entry, availability, and notes exist. Favorites, scaling, missing-one/two suggestions, and airplane-mode device validation remain. |
| 2 | Not implemented | Versioned household bundle export/import and optional photo transfer are pending. |
| 3 | Not implemented | Concurrent edit conflict detection, conflict review, field merge, and two-parent merge revisions are pending. |
| 4 | Partial | Member-attributed tasting notes are stored independently and tested locally; cross-device round-trip is pending. |
| 5 | Not implemented | Reimport fast-forward/idempotence and old-bundle no-rollback behavior need import tests. |
| 6 | Not implemented | Barcode capture, local barcode storage, product association, and offline manual fallback are pending. |
| 7 | Partial | The app has no AI dependency. Provider configuration, disclosure, request limits, structured validation, and save-as-draft behavior are pending. |
| 8 | Partial | Device-only preferences are in a separate local table and there are no provider credentials yet. Export privacy tests are pending with interchange. |
| 9 | Not implemented | Staged CSV/JSON mapping, preview, cancel-without-writes, validation, and atomic commit are pending. |
| 10 | Partial | Manual recipes are separate by default; users can explicitly associate a variant with a family. Source-aware duplicate detection and reversible import associations are pending. |
| 11 | Partial | Manual home override is persisted and tested. Opt-in foreground location permission, on-device home matching, ambiguity handling, and permission-path device tests are pending. |
| 12 | Partial | Offline spirit unlock ranking counts distinct newly makeable families and has unit coverage. Shop-specific fixed destination selection and full Pixel validation remain. |
| 13 | Not implemented | Household export and tests that exclude coordinates, radii, credentials, and local preferences are pending. |

## Remaining Phase 1 work

1. Complete versioned bundle and CSV/JSON interchange with staging, preview/cancel, atomic validation, idempotent imports, provenance preservation, and privacy tests.
2. Add revision graph comparison, skewed-clock concurrent edit detection, conflict review/field selection, two-parent merge revisions, and robust tombstone import/merge coverage.
3. Add missing recipe CRUD and discovery functions: edit/duplicate, favorites, recent drinks, recipe scaling and units, and missing-ingredient filters. Keep source association explicit and reversible.
4. Add location permission and foreground home selection with persisted manual override; keep the At the shop destination fixed for the shop session.
5. Add barcode scan capture with an always-available manual path. Any external lookup requires a separate coverage, privacy, and license review.
6. Finish source-by-source recipe attribution and redistribution review before adding a bundled catalog.
7. Validate migration from every supported database version, export/import failure rollback, and clock-skew/large-bundle cases.
8. Run usability checks on Pixel 10 Pro and Pixel 8 or representative emulators, including airplane mode, denied/ambiguous location, large font, dark theme, and long content. Current cloud validation is build/test/lint only; no emulator/device UI run has been completed.
9. Check shared iOS framework linking on macOS/Xcode before building the future SwiftUI client. No iOS application shell or iOS runtime validation exists yet.

AI provider integration, shipping a model, a catalog dataset, hosted sync, and an iOS release remain outside Phase 1. The assistant provider boundary remains a later optional feature; neither the Codex subscription nor an OpenAI API credential is assumed.
