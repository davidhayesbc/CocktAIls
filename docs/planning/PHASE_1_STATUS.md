# Phase 1 implementation status

This is a snapshot of implementation and verification, not a release-readiness claim. The acceptance criteria refer to [MVP_SPEC.md](../product/MVP_SPEC.md#6-mvp-acceptance-criteria).

## Delivered foundation

- Android debug app and Kotlin Multiplatform shared core are in place; Android CI runs build/unit/lint/APK verification independently from the headless emulator smoke job. Exact-SHA Actions run [40161092337](https://github.com/davidhayesbc/CocktAIls/actions/runs/40161092337) passed both jobs on `359322082480c74728d01db4e7f4e9e2f577013b`: the APK was uploaded as a 30-day artifact, and the Android API 35 emulator passed offline recipe creation plus activity-recreation persistence. The emulator uses software acceleration and takes several minutes to boot; physical Pixel checks remain separate.
- A manual, trusted-main-only preview release workflow is prepared. It requires `COCKTAILS_PREVIEW_KEYSTORE_BASE64` (PKCS#12) and `COCKTAILS_PREVIEW_KEYSTORE_PASSWORD`, signs with alias `cocktails-preview`, verifies the APK certificate against the keystore, then publishes a public GitHub prerelease using job-scoped `contents: write`. It has not run because the approved signing secrets are not yet configured; no key has been generated or release published.
- SQLDelight schema v4 migrates a populated v3 pantry without changing its IDs or stock levels. Tests also cover v1-to-v3 migration, canonical UUIDv7 generation, family-level shop unlock counting, recipe-family explicit association, tasting-note author attribution, and revision payload/parent links.
- Household recipe variants can now be archived with an immutable deletion revision and tombstone. Archived variants stay in storage/history and disappear from browse results; repeated archive attempts do not create another deletion.
- Versioned household JSON backups can be written/read with Android's document picker. Import is size/schema/reference checked, staged for preview, cancelable without writes, and committed in one database transaction. Device-local preferences and provider/coordinate settings are excluded.
- Import conflict review compares revision ancestry, never relies on device timestamps, supports keep-local/use-backup/field merge, and writes a merge revision with both branch heads as parents. Reimporting an older branch after merge does not roll back the selected result.
- Apply rechecks the preview fingerprint inside the database transaction, preserves the exact revision/time for an existing tombstone on stale live-backup reimport, and rejects a combined household state that would exceed the 10 MiB backup limit.
- Local household and member profiles, multiple homes, per-home approximate inventory, persisted manual home/member choices, recipe entry, grouped variants, style filters, tasting notes, and the initial At the shop spirit ranking are implemented.
- Recipe discovery now filters by what is on hand, exactly one missing required ingredient, or up to two missing required ingredients. Optional lines do not block availability and repeated use of one ingredient counts once.
- Recipe entry now accepts measured ingredient lines and an original yield; the detail view scales known quantities for a selected serving count. Quantity/yield persistence is covered by a two-database bundle round-trip test.
- Recipe variants can be edited without replacing immutable revision history or stable ingredient-line IDs, duplicated within their existing family, and merged after divergent two-device edits. Duplicate variants retain source attribution and measured yields.
- Family favorites are member-attributed household records. Removal writes a tombstone, divergent changes enter conflict review, and stale backups cannot restore a removed favorite. Recently made events are persisted, attributed to member/location, and transferred in household bundles.
- Optional foreground location selection uses one approximate coarse fix while the app is open. Users save a private radius for each home on this phone; overlapping/no-match areas never guess, and a manual home change pauses automatic switching until resumed. The selected home and override survive process recreation. At the shop snapshots its destination and pantry for the whole session.
- The eight-record Wikipedia/IBA candidate has a bounded source review in [CATALOG_DATA_REVIEW.md](CATALOG_DATA_REVIEW.md). No recipe data is bundled until the remaining source-page history check is complete.
- No credentials, model providers, or network service are required for the app's current workflows.

## Acceptance criteria

| # | State | Evidence or remaining work |
|---|---|---|
| 1 | Partial | Offline-capable storage, manual recipe entry/edit/duplicate, favorites, recent-made history, scaling, availability filters, notes, and sync are implemented. Hosted Android API 35 emulator passed offline recipe creation and activity-recreation persistence on run 40161092337; physical Pixel usability checks remain. |
| 2 | Partial | Versioned household JSON export/import includes recipes, sources, notes, favorites, and recent-made events with preview, cancel, size/reference validation, and atomic apply. Photo transfer and generic recipe CSV/JSON mapping remain. |
| 3 | Partial | Revision-ancestry conflicts, explicit resolution, field merge, two-parent recipe-edit revisions, favorite-removal tombstones, stale-import tombstone preservation, and edit-at-commit race are tested. Concurrent archive-vs-edit UX remains. |
| 4 | Partial | Member-attributed tasting notes round-trip across two independent databases in the sync regression test. Device-level multi-user usability remains unverified. |
| 5 | Partial | Duplicate reimport, stale branch no-rollback, favorite removal, and recent-made event exchange are tested. Broader fast-forward/tombstone/import-history fixtures remain. |
| 6 | Not implemented | Barcode capture, local barcode storage, product association, and offline manual fallback are pending. |
| 7 | Partial | The app has no AI dependency. Provider configuration, disclosure, request limits, structured validation, and save-as-draft behavior are pending. |
| 8 | Partial | Device-only preferences stay in a separate table; export tests verify API-key/coordinate/radius canaries are omitted. |
| 9 | Partial | Household bundle staging, preview, cancel-without-writes, validation, and atomic commit are implemented. Generic recipe CSV/JSON field mapping remains. |
| 10 | Partial | Manual recipes are separate by default; users can explicitly associate a variant with a family. Source-aware duplicate detection and reversible import associations are pending. |
| 11 | Partial | Opt-in coarse foreground permission, on-device per-home radius matching, ambiguity/no-match handling, persisted manual override, privacy exclusions, and a denied-permission manual-selector instrumentation test are implemented. That new emulator test is pending exact-SHA CI; physical Pixel permission/recreation checks remain. |
| 12 | Partial | Offline spirit unlock ranking counts distinct newly makeable families and has unit coverage. At the shop snapshots the selected destination and pantry for the session. Full Pixel validation remains. |
| 13 | Partial | Household export excludes local preferences; regression coverage checks credential, coordinate, and radius canaries. |

## Remaining Phase 1 work

1. Complete generic recipe CSV/JSON interchange with staging, field mapping, provenance review, and idempotent commits; household JSON exchange is implemented.
2. Add robust tombstone-vs-edit conflict tests, validate import behavior for all record types and malformed/large bundles, and retain the tested skewed-clock two-device conflict path.
3. Add remaining discovery/history polish: recent-drink date display and favorite a specific variant if needed. Family favorites, recent-made events, edit/duplicate, measured entry, serving scaling, and missing-ingredient filters are implemented. Keep source association explicit and reversible.
4. Add barcode scan capture with an always-available manual path. Any external lookup requires a separate coverage, privacy, and license review.
5. Finish the candidate page-revision history review in [CATALOG_DATA_REVIEW.md](CATALOG_DATA_REVIEW.md) before adding its separately licensed pack. Do not bundle other sources under that review.
6. Validate migrations from every supported database version, export/import failure rollback, and clock-skew/large-bundle cases. Populated v1-to-v3 and v3-to-v4 migrations are directly tested; the SQLDelight migration verifier did not finish in its bounded run because its object-diff worker remained in recursive comparison.
7. Run usability checks on Pixel 10 Pro and Pixel 8, including denied/ambiguous location, large font, dark theme, and long content. The hosted headless emulator test covers offline recipe entry and activity recreation; it does not replace physical-device checks.
8. Check shared iOS framework linking on macOS/Xcode before building the future SwiftUI client. No iOS application shell or iOS runtime validation exists yet.

AI provider integration, shipping a model, a catalog dataset, hosted sync, and an iOS release remain outside Phase 1. The assistant provider boundary remains a later optional feature; neither the Codex subscription nor an OpenAI API credential is assumed.
