# Phase 1 plan: Offline household cocktail library

**Goal:** deliver the complete offline data and discovery loop, with safe file-based sharing between two Android devices. This phase establishes the sync-ready data model; it does not implement a hosted synchronization service.

## Phase 1 scope

Included:

- Android app shell with polished responsive Compose UI, dark theme, scalable text, and accessible touch targets.
- Kotlin Multiplatform shared domain/data core and shared SQLite persistence with household, member/device profiles, locations, generic ingredients, optional products, canonical cocktail families, recipe variants and source records, recipe ingredient lines, tasting notes, favorites/recent activity, record revisions, and tombstones.
- Native Android UI in Jetpack Compose. Keep the future iOS app in a separate SwiftUI target that consumes the same shared core.
- UUIDv7 IDs generated offline; immutable revision ancestry and explicit conflict representation.
- Manual ingredient and recipe CRUD, including instructions, provenance fields, amount/unit, preparation, optional ingredients, and servings.
- Location-specific approximate stock levels (`Full`, `Mostly Full`, `Half`, `Low`, `Out`) plus optional numeric quantity; missing record is unknown.
- Opt-in foreground location selection using device-local home areas, a persistent manual override, and explicit fallback when permission or a confident match is unavailable.
- Recipe search for a selected location: exact matches, missing one/two ingredients, text search, favorite filter, and explicit substitution hints where curated.
- Offline At the shop view for a selected destination home, including low/out restocks and spirits ranked by additional distinct recipe families unlocked.
- Family-based listings that group equivalent source records and distinct recipe variants, with safe import match suggestions and user review for uncertain identity links.
- Recipe scaling, unit display preference, favorites, and recently made list.
- Separate tasting notes/reviews for each member.
- Barcode capture with manual fallback. External barcode lookups and label OCR await source/runtime review; neither may block saving.
- Small, separately versioned and attributed core recipe pack; candidate seed is the audited Wikipedia CSV subset of `rasmusab/iba-cocktails`. Source entries are read-only; household edits create linked variants.
- Staged, provenance-aware import pipeline. Support CocktAIls bundles and user-provided recipe CSV/JSON with explicit field mapping; add source-specific adapters only after rights and formats are reviewed.
- Versioned bundle export/import with optional photos, validation, preview, idempotent merge, conflict resolution, and safe backup/restore.
- No login, no paid backend, no analytics, and no required network service.

Not in Phase 1:

- Bundling unreviewed third-party records, images, or product data. Dot's research is an input, not a completed record-level rights audit.
- Real-time sync, accounts, public sharing, or a server.
- Shipping a specific LLM/runtime or making AI a prerequisite for cocktail discovery. Keep the provider boundary in the architecture; implement AI after its device and API feasibility review.
- Retail integrations, social features, nutrition claims, automatic inventory detection, or an iOS release in Phase 1.

## Work sequence

### 0. Repository and product readiness

- Confirm package/application ID, minimum Android version, target SDK, distribution method, visual direction, and whether photos are needed in the first import format.
- Audit source-page attribution and modifications for each selected row in the candidate 89-record Wikipedia subset; exclude rows that cannot be attributed adequately. Document source URL/revision, license/version, attribution, redistribution, and update process. Do not use the separately scraped `iba-web/` data without independent clearance.
- Decide the initial core pack license for original contributions and define contributor terms. Keep per-record provenance where source licenses differ.
- Defer Open Food Facts bulk data until spirits coverage, pack size, and ODbL obligations are evaluated. Keep barcode capture useful with manual entry meanwhile.
- Verify barcode scanning and optional OCR offline behavior; verify Ollama Cloud auth/API terms separately if Phase 2 AI is approved.
- Create a Gradle repository with an Android app target and a Kotlin Multiplatform `shared/core` target; configure Android and future iOS framework source sets without introducing an online account/backend dependency.
- Add a SwiftUI app shell directory for the future client, but keep iOS UI implementation and release out of Phase 1. Record that full iOS framework linking, signing, and device tests require a macOS/Xcode runner.

**Exit:** supported devices/toolchain and approved data-pack policy are documented; a minimal app builds; the initial core dataset has an attribution/license manifest.

### 1. Data foundation and manual entry

- Implement common domain types and SQLDelight schema/migrations for household, local profiles/devices, locations, ingredients/products, cocktail families, recipe variants/source records, recipe ingredient lines, notes, inventory, revisions, and tombstones.
- Preserve source-specific recipe IDs and provenance; never make a provider's recipe ID the app's canonical identity.
- Add repository/use-case layer so Android UI, future SwiftUI, and interchange logic do not depend on SQLDelight row IDs or platform UI types.
- Implement quick ingredient and recipe entry, edit, duplicate, archive/delete, and location/inventory editing.
- Add an opt-in location-selection adapter: ask only when enabled, match foreground coordinates to device-local home areas, expose manual override, and keep coordinates out of household exports.
- Add member profile selection; do not require sign-in.

**Exit:** Android app and shared code build; common domain tests and Android database migrations pass; the iOS framework export is checked on macOS/Xcode before iOS implementation begins. Core records work offline on Android.

### 2. Recipe use loop

- Implement recipe detail and polished browse/search screens.
- List one card per cocktail family, show variant count and location availability, and let users compare variants and source attributions.
- Add selected-location availability matching, exact and missing-one/two filters, optional ingredient handling, and explicit substitutions.
- Add At the shop analysis in shared code: count families that become fully makeable once per candidate spirit, show example variants, and keep Low/Out restock items separate from unlocks.
- Add scaling/unit preference, favorites, and recent drinks.
- Add append-only tasting notes, ratings, author, date, and optional location.

**Exit:** acceptance criteria 1, 11, and 12 in the MVP spec pass on Pixel 10 Pro and Pixel 8 or representative emulators, including airplane mode and permission-denied/ambiguous-location paths.

### 3. Versioned exchange and conflict review

- Define the bundle JSON schema and asset packaging/checksum rules independently from SQLDelight migrations. Preserve source records, household copies, and data-pack provenance as distinct concepts.
- Add a generic CSV/JSON recipe importer that stages records, asks the user to map fields and supply/confirm provenance and license, validates ingredient references, and previews changes before commit. Keep source-specific adapters separate.
- Make export preview show included source/license notices and allow data packs to be excluded; do not silently drop attribution or redistribute a source pack without preserving its terms.
- Export household records and revisions; verify credentials/device-only settings are excluded.
- Import into a staging area, validate structure/relationships/limits, and present a preview before an atomic commit.
- Suggest likely family/variant matches using normalized names/aliases and ingredient/ratio similarity. Require review for uncertain links; allow a confirmed source record to be unlinked or re-associated later.
- Implement idempotent duplicate import, parent-lineage fast-forward, concurrent revision conflict review, field selection/merge, two-parent merge revisions, and tombstone behavior.
- Add backup/restore guardrails and explicit outcome summaries.

**Exit:** two-device scenarios in MVP acceptance criteria 2–5 pass, including divergent recipe edits and concurrent tasting-note additions; import criteria 9–10 pass, including repeatable source mapping, no duplicate listings for matched recipes, and distinct formulations grouped as variants.

### 4. Barcode and visual refinement

- Add camera permission explanation and barcode scan flow. Save the scanned value locally before attempting lookup.
- Integrate an approved lookup source only after coverage and rights review; show source and confidence and require user confirmation.
- Add optional label photo. OCR is a later opt-in enhancement if its offline footprint and accuracy are acceptable.
- Refine typography, color, empty states, loading, errors, dark mode, font scaling, and accessibility.

**Exit:** barcode acceptance criterion 6 passes offline; all source-derived fields are attributable.

### 5. MVP hardening and release candidate

- Test import/export corruption, unsupported versions, large assets, repeated imports, interrupted writes, tombstones, and device clock skew.
- Profile search and database access with a representative household dataset.
- Verify privacy: no unexpected network calls, secrets absent from logs/exports, and optional network features clearly disclosed.
- Test the complete offline workflow and restore from backup on both target Pixel generations.

**Exit:** all MVP acceptance criteria pass; known limitations and data-source attribution are included in the app and repository docs.

## Initial domain model sketch

All shared records use stable UUIDv7 IDs. Editable records have immutable revisions and one or more parent revision IDs. Exact SQL and Kotlin type choices are implementation details, but the following relationships are required:

- `Household` contains `MemberProfile` and `Location`.
- `Ingredient` is generic; optional `Product` records represent brands/barcodes and can map to a generic ingredient.
- `InventoryItem` links a location to an ingredient or product and stores approximate level plus optional quantity/unit.
- `Recipe` has ordered `RecipeIngredient` lines linked to generic ingredients, and provenance/source/license metadata.
- `TastingNote` is an append-only member-authored record linked to a recipe and optionally a location.
- Mutable records have `RecordRevision` history with author/device, timestamp, parent IDs, and payload/schema version. Deletions are tombstone revisions.
- Images/assets use stable IDs and checksums; provider credentials and device preferences are outside this shared graph.

## Validation approach

- Unit tests: recipe availability, missing count, scaling/rounding, unit conversion, optional lines, revision ancestry, conflict detection, deterministic merge validation, and tombstone merge.
- Database tests: SQLDelight relations, transactions, migrations from every supported version, and interrupted import rollback.
- Interchange tests: golden bundles, malformed/oversized archives, duplicate imports, unknown versions, asset checksums, and secret exclusion.
- UI/device tests: quick add, pantry update, offline search, tasting note, barcode manual fallback, conflict review, backup restore, automatic/manual location choice, and At the shop target-home selection.
- Manual device checks: camera and foreground-location permissions, location override and ambiguity, dark mode, large font, offline operation, shop unlock counts, no location leakage in exports, performance, battery/thermal behavior, and local model experiments only in a later AI milestone.

## Risks and mitigations

- **Catalog licensing/coverage:** Dot's research identifies candidates but does not clear every row. Keep manual recipes useful; bundle only audited records with complete attribution and license metadata.
- **Dataset license coupling:** keep separately licensed catalog packs separate from code and household records; retain per-record provenance and publish required notices/modified records.
- **Product database obligations and size:** defer local Open Food Facts packs until ODbL review and target-market coverage/size measurements.
- **Merge complexity:** begin with immutable revisions and conflict-preserving import; test parent graphs before polishing merge UI.
- **False recipe matches:** use similarity only to suggest links; preserve source records and require confirmation before joining families/variants.
- **Data entry friction:** optimize quick-add flows and allow generic ingredients before detailed products.
- **Barcode lookup gaps:** store raw barcode locally and always allow manual correction.
- **Photo bundle size:** make photos optional and show size before export/import.
- **Pixel model/runtime uncertainty:** keep AI optional and evaluate separately on both phones; do not let it block Phase 1.
- **Clock skew:** use ancestry and explicit conflict resolution, never timestamps as a conflict winner.

## Immediate next steps

1. Complete the record-level attribution/license audit for candidate recipe sources and decide which, if any, source to use.
2. Confirm package ID, minimum Android version/distribution plan, and visual style.
3. Scaffold the Android project and establish CI/build verification.
4. Implement the domain model and offline manual-entry slice before catalog import or AI integration.
