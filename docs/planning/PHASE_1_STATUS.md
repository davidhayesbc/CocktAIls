# Phase 1 implementation status

This is a snapshot of implementation and verification, not a release-readiness claim. The acceptance criteria refer to [MVP_SPEC.md](../product/MVP_SPEC.md#6-mvp-acceptance-criteria).

## Delivered foundation

- Android debug app and Kotlin Multiplatform shared core are in place; Android CI compiles the common metadata, runs shared-core tests and lint, builds the debug APK, and uploads it as a 30-day Actions artifact.
- SQLDelight schema v3 migrates a populated v1 pantry without changing its existing IDs or stock levels. Tests cover the v1-to-v3 migration, canonical UUIDv7 generation, family-level shop unlock counting, recipe-family explicit association, tasting-note author attribution, and revision payload/parent links.
- Household recipe variants can now be archived with an immutable deletion revision and tombstone. Archived variants stay in storage/history and disappear from browse results; repeated archive attempts do not create another deletion.
- Versioned household JSON backups can be written/read with Android's document picker. Import is size/schema/reference checked, staged for preview, cancelable without writes, and committed in one database transaction. Device-local preferences and provider/coordinate settings are excluded.
- Import conflict review compares revision ancestry, never relies on device timestamps, supports keep-local/use-backup/field merge, and writes a merge revision with both branch heads as parents. Reimporting an older branch after merge does not roll back the selected result.
- Apply rechecks the preview fingerprint inside the database transaction, preserves the exact revision/time for an existing tombstone on stale live-backup reimport, and rejects a combined household state that would exceed the 10 MiB backup limit.
- Local household and member profiles, multiple homes, per-home approximate inventory, persisted manual home/member choices, recipe entry, grouped variants, style filters, tasting notes, and the initial At the shop spirit ranking are implemented.
- Recipe discovery now filters by what is on hand, exactly one missing required ingredient, or up to two missing required ingredients. Optional lines do not block availability and repeated use of one ingredient counts once.
- Recipe entry now accepts measured ingredient lines and an original yield; the detail view scales known quantities for a selected serving count. Quantity/yield persistence is covered by a two-database bundle round-trip test.
- Recipe catalog remains empty by design pending record-level rights review. No credentials, model providers, or network service are required for the app's current workflows.

## Acceptance criteria

| # | State | Evidence or remaining work |
|---|---|---|
| 1 | Partial | Offline-capable local storage, homes, pantry, measured recipe entry and scaling, availability, missing-one/two filters, and notes exist. Favorites and airplane-mode device validation remain. |
| 2 | Partial | Versioned household JSON export/import is implemented with preview, cancel, size/reference validation, transactional stale-preview protection, combined-size validation, and an Android file-picker flow. Photo transfer and generic recipe CSV/JSON mapping remain. |
| 3 | Partial | Revision-ancestry conflicts, explicit resolution, field merge, two-parent revisions, stale-import tombstone preservation, and an edit racing the commit boundary are tested. Tombstone-vs-concurrent-edit resolution remains. |
| 4 | Partial | Member-attributed tasting notes round-trip across two independent databases in the sync regression test. Device-level multi-user usability remains unverified. |
| 5 | Partial | Duplicate reimport and stale branch no-rollback behavior are tested. Broader fast-forward/tombstone/import-history fixtures remain. |
| 6 | Not implemented | Barcode capture, local barcode storage, product association, and offline manual fallback are pending. |
| 7 | Partial | The app has no AI dependency. Provider configuration, disclosure, request limits, structured validation, and save-as-draft behavior are pending. |
| 8 | Partial | Device-only preferences stay in a separate table; export tests verify API-key/coordinate/radius canaries are omitted. |
| 9 | Partial | Household bundle staging, preview, cancel-without-writes, validation, and atomic commit are implemented. Generic recipe CSV/JSON field mapping remains. |
| 10 | Partial | Manual recipes are separate by default; users can explicitly associate a variant with a family. Source-aware duplicate detection and reversible import associations are pending. |
| 11 | Partial | Manual home override is persisted and tested. Opt-in foreground location permission, on-device home matching, ambiguity handling, and permission-path device tests are pending. |
| 12 | Partial | Offline spirit unlock ranking counts distinct newly makeable families and has unit coverage. Shop-specific fixed destination selection and full Pixel validation remain. |
| 13 | Partial | Household export excludes local preferences; regression coverage checks credential, coordinate, and radius canaries. |

## Remaining Phase 1 work

1. Complete generic recipe CSV/JSON interchange with staging, field mapping, provenance review, and idempotent commits; household JSON exchange is implemented.
2. Add robust tombstone-vs-edit conflict tests, validate import behavior for all record types and malformed/large bundles, and retain the tested skewed-clock two-device conflict path.
3. Add missing recipe CRUD and discovery functions: edit/duplicate, favorites, and recent drinks. Measured entry, serving scaling, and missing-ingredient filters are implemented. Keep source association explicit and reversible.
4. Add location permission and foreground home selection with persisted manual override; keep the At the shop destination fixed for the shop session.
5. Add barcode scan capture with an always-available manual path. Any external lookup requires a separate coverage, privacy, and license review.
6. Finish source-by-source recipe attribution and redistribution review before adding a bundled catalog.
7. Validate migration from every supported database version, export/import failure rollback, and clock-skew/large-bundle cases.
8. Run usability checks on Pixel 10 Pro and Pixel 8 or representative emulators, including airplane mode, denied/ambiguous location, large font, dark theme, and long content. Current cloud validation is build/test/lint only; no emulator/device UI run has been completed.
9. Check shared iOS framework linking on macOS/Xcode before building the future SwiftUI client. No iOS application shell or iOS runtime validation exists yet.

AI provider integration, shipping a model, a catalog dataset, hosted sync, and an iOS release remain outside Phase 1. The assistant provider boundary remains a later optional feature; neither the Codex subscription nor an OpenAI API credential is assumed.
