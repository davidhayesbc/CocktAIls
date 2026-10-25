# CocktAIls MVP specification

**Status:** Product baseline for review
**Platform:** Android first (Pixel 10 Pro and Pixel 8); architecture is shared-core-ready for a future iPhone app
**Operating model:** Offline-first; no required account, backend, or subscription

## 1. Product goal

Help a household keep track of its ingredients at multiple homes, discover drinks it can make, record how drinks turn out, and share updates between members. The core workflow must remain useful with airplane mode enabled. Internet access may improve barcode lookup, catalog acquisition, and AI assistance, but must not be required for browsing or editing the household's own data.

## 2. Users and household model

- A household is the default sharing boundary. Recipes, locations, pantry records, and tasting notes belong to the household unless a later privacy feature says otherwise.
- A person can use the app without creating a cloud account. The local installation has a stable device ID and a locally selected member profile; both IDs are generated independently of a server.
- A household can contain multiple member profiles and devices. Phase 1 transfers household data by export/import; there is no live synchronization service.
- AI provider credentials and per-device preferences are local-only. They are never part of a household export.

## 3. MVP capabilities

### 3.1 Ingredient and product library

- Create and edit a generic ingredient, such as dry gin, lime juice, or simple syrup.
- Optionally associate one or more branded products or bottles with a generic ingredient. Recipes normally refer to generic ingredients; inventory can refer to a product when the user wants that detail.
- Capture a barcode locally and allow manual correction. Barcode capture must still be useful when no lookup source is configured.
- Store a name, category, optional description, optional ABV, optional label image, and optional personal tasting notes.
- Lookup and label OCR are suggestions only: the user confirms fields before saving. External data source selection and licensing are pending review.

### 3.2 Locations and inventory

- Create multiple household locations, such as Home and Cabin.
- Offer opt-in automatic active-location selection while the app is in use. Use device location only on-device to match a device-local, user-configured area to a household location; do not continuously track in the background.
- Allow a persistent manual location override and a clear way to return to automatic selection. If permission is denied, no area matches, or multiple areas are ambiguous, keep the current/last selected location and ask rather than guessing.
- Keep coordinates and location-match radii device-local and out of household exports. Household location names and pantry records remain shareable.
- Track a generic ingredient or product at a location using approximate levels: **Full, Mostly Full, Half, Low, Out**.
- No inventory record means **not tracked/unknown**; **Out** means tracked and depleted. These states must not be conflated.
- Allow optional quantity and unit for users who want more precision, without requiring it for normal use.
- Show when and by whom an inventory estimate was last updated.

### 3.3 Recipe library and recipe editor

- Ship a small offline core catalog, separately versioned from app code and household records. Every core record carries source/provenance and license metadata. Expand the catalog only through reviewed sources or original, properly licensed contributions.
- Keep source catalog entries distinct from household-authored/imported recipes. Users can favorite and annotate a catalog recipe; editing it creates a household-owned variant linked to the source variant rather than changing the bundled source record.
- Support importing CocktAIls household bundles and user-provided recipe CSV/JSON through a staging/preview flow with explicit field mapping and provenance. Source-specific adapters can be added when their rights and formats are reviewed; do not imply that every website/API can be copied or redistributed.
- Group recipes under a canonical cocktail identity in browse/search results. Preserve genuinely different formulations as variants within that group; equivalent copies from different sources should add provenance/source references to one variant rather than create repeated listings.
- During import, suggest likely family/variant matches using normalized names, aliases, ingredients, and ratios. Exact, user-confirmed matches can be linked; uncertain matches require review. Never auto-merge solely because titles match, and make identity/linking decisions reversible.
- Manual recipes work regardless of data source availability.
- Create, edit, duplicate, archive, and delete a recipe. Deletion is represented by a tombstone for future sharing.
- A recipe contains a title, instructions, yield/servings, ordered ingredient lines, optional garnish/preparation notes, tags, provenance, and optional image.
- Ingredient lines support amount, unit, preparation, and optionality. Support common volume units (ml and US fl oz), count, dash, barspoon, and “to taste”; retain original display values to avoid lossy conversions.
- Scale servings while preserving the source recipe and showing scaled amounts.
- Distinguish imported, household-authored, and AI-generated recipes. Keep source and attribution/license metadata where applicable.
- Mark favorites and show a small “made recently” list.

### 3.4 Search and discovery

- Filter recipes for a selected location using its inventory.
- Provide exact-match results plus “missing one” and “missing two” suggestions. Clearly separate available, optional, and missing ingredients.
- Allow text search and basic filters such as spirit/base ingredient, style, and favorites.
- Show one result card per cocktail family, with the number of available variants and an indication of which variants can be made at the selected location. Let the user open the family to compare variants and sources.
- Substitution suggestions are advisory and explicit; do not silently treat a different ingredient as equivalent.
- Include an **At the shop** mode that explicitly selects the household location being stocked. Shop mode uses that location's pantry and pauses automatic location switching; the store itself is never treated as a household location.
- Show ingredients marked Low/Out (and optionally Half as a watch list) and rank candidate spirits by the number of additional distinct cocktail families they would make fully makeable at the selected location. Simulate adding the candidate as available; count a family once even if it has several matching variants, and count it only when at least one variant has no other missing required ingredients. Explain the calculation and show example drinks. This calculation is local and works offline.

### 3.5 Tasting notes and reviews

- Record a tasting note as a separate, append-only entry linked to a recipe and member profile.
- Include optional rating, date made, location, free-text notes, and optional photo.
- Keep multiple household members’ reviews; editing one person’s note must not overwrite another person’s note.

### 3.6 Import/export and conflict handling

- Export a versioned household bundle containing referenced shared records and optional photos. Clearly show bundle size and what is included.
- Import begins with a preview of additions, unchanged records, updates, conflicts, tombstones, and unsupported schema versions.
- Merge matching records by stable ID. Never overwrite a divergent edit silently.
- For concurrent edits, offer local version, incoming version, or field-level merge when safe. Save a merge as a new revision with both parents.
- Keep an explicit backup/restore path. An older bundle cannot silently roll back newer records.
- Exclude credentials, provider keys, access tokens, and other device-only settings.

### 3.7 Cocktail assistant

- The core app works without a model. The assistant is optional and accessed through a provider interface.
- A provider can be an on-device model or a user-configured cloud model provider. Initial cloud candidate: Ollama Cloud, subject to API/auth verification. OpenAI API may be added if the user supplies separate API access; a Codex subscription is not an app API credential.
- Tell users which provider will receive a request and whether it is local or cloud. Cloud requests require explicit opt-in and must disclose likely data sent.
- Send only the selected location’s relevant ingredient list and user-entered constraints by default. Never send the household database, tasting notes, or credentials unless the user explicitly chooses to include relevant content.
- Ask the model for structured recipe suggestions, validate ingredient references and measurements, and show the result as an unsaved AI draft. The user reviews and saves it.
- Clearly mark generated suggestions as generated; do not present them as sourced classics.
- Model files are optional downloads with displayed size and a removable local installation. No model is bundled in the app binary.

## 4. Non-functional requirements

- Recipe browsing, editing, pantry updates, search, notes, and file import/export work offline.
- Local data is stored in an on-device database. Use platform-provided at-rest protection; document that this is not a substitute for device lock/encryption.
- Network credentials are held in Android Keystore-backed storage where supported and are never logged, exported, or synced.
- The UI is polished, legible, touch-friendly, and responsive on Pixel 10 Pro and Pixel 8 screen sizes. Support dark mode and dynamic type/font scaling.
- Minimize background work, battery use, and data transfer. No analytics or account requirement in the initial release.
- Keep imported data attributable and respect its license. No dataset is bundled until a source and its redistribution conditions are reviewed.

## 5. Out of scope for initial release

- Hosted accounts, real-time cloud sync, public/social profiles, and cross-household sharing.
- Shopping lists, retailer integrations, payments, subscriptions, and paid developer-operated infrastructure.
- Automatic pantry recognition or unreviewed label/barcode enrichment.
- Nutrition or medical claims, exact pour measurement, inventory valuation, and alcohol-consumption recommendations.
- Shipping an iOS client in the initial release; keep core logic and interchange formats portable so a native iPhone client can follow.

## 6. MVP acceptance criteria

1. With networking disabled, a user can create two locations, add ingredients, enter a recipe, update stock levels, find recipes available at a selected location, favorite one, scale it, and save a tasting note.
2. A second device can import an export and see the household data with relationships and photos intact when photos were selected.
3. If two devices independently edit the same recipe, importing one bundle reports a conflict and preserves both edits until a user resolves it.
4. Separate tasting notes from both members survive export/import without conflict or loss.
5. An older bundle does not silently remove newer records or undo later edits.
6. A barcode can be captured and manually associated with a product while offline; lookup failure never blocks saving.
7. The app is usable with no AI provider configured. When configured, provider and data-sharing status are visible before a cloud request, and generated output is validated and saved only by user action.
8. No API credential appears in an export, log, crash report, or shared database record.
9. A user can import a supported recipe CSV/JSON file, review field mapping and source/license details, preview changes, and cancel without changing the database.
10. Importing an equivalent recipe from a second source does not create a duplicate listing; it adds source provenance to the matched variant. A different formulation of the same cocktail is grouped as another variant, and an uncertain match is held for review.
11. If location access is enabled, the app selects a matching home while in use, clearly shows the active home, and respects a manual override. Denying permission leaves manual selection fully usable; ambiguous or unmatched positions do not silently switch homes.
12. At the shop, the user can select a target home, see its low/out items and candidate spirits ranked by distinct cocktail families that become fully makeable, and continue using the view offline.
13. Household exports contain named locations and inventory, but never device-local coordinates, location radii, or location-service history.

## 7. Data source and distribution policy

- Candidate core recipe seed: the Wikipedia CSV subset of `rasmusab/iba-cocktails`, reported as CC BY-SA 3.0. Before inclusion, audit each record's source page and attribution, include required notices, identify modifications, and keep the ShareAlike dataset distinct from app code. Do not use the repository's separately scraped `iba-web/` content without independent clearance.
- Wikibooks Bartending is a possible later CC BY-SA 4.0 source, subject to the same record-level attribution, modification, and license checks.
- Do not bulk-import Open Drinks or Bar Assistant solely because their repositories declare MIT; sampled records may include uncleared third-party prose or images.
- Exclude NC-only sources from the default distributable catalog. Public availability and attribution do not by themselves grant redistribution rights.
- Keep images out of the initial core unless each image has separately verified rights and attribution.
- Recipe source records, household copies, product datasets, images, and app code are separate licensing units. Preserve source/license/version/retrieval date and modification metadata for imported content.
- Barcode scans are stored as strings (preserving leading zeros); validate and normalize UPC/EAN forms without discarding the scanned original. Manual entry always works offline.
- Do not bundle Open Food Facts data. Its ODbL/Database Contents License obligations and the size/coverage of a useful spirits subset remain relevant; a filtered extract is not automatically outside database obligations. The narrowly scoped, user-triggered single-product v3.6 lookup is documented in [ADR-0011](../architecture/ADR-0011-optional-open-food-facts-lookup.md) and does not export provider cache data.
- USDA FoodData Central Branded (CC0) and Wikidata (CC0) are candidates for product facts/identities, subject to useful coverage tests. Do not claim sensory tasting notes from chemical data.
- No verified comprehensive open bottle-level tasting-note corpus is available from this research. Use household-authored notes and, later, opt-in community profiles with explicit contribution terms. Do not scrape manufacturer prose without permission.

## 8. Open questions

- The bounded eight-record text-only starter pack is included under CC BY-SA 3.0; see `docs/planning/CATALOG_DATA_REVIEW.md`. No additional catalog sources are cleared.
- Product/barcode source: measure target-market coverage and size; decide whether a separate Open Food Facts-derived pack is worthwhile after ODbL review.
- How should future catalog updates beyond the bundled starter pack be delivered without a developer-operated backend: app releases, user-imported data packs, or an optional hosted static file?
- Should users be able to import arbitrary JSON/CSV only, or should the first release include one source-specific adapter after the selected source is cleared?
- How should exports handle imported content with license terms that require attribution, ShareAlike, or restrict redistribution? At minimum, show included source/license details and let the user exclude a source pack.
- Android barcode/OCR implementation and offline availability: verify during technical discovery.
- Local model/runtime support and feasible model sizes on both target Pixels: prototype and measure before committing to a model.
- Ollama Cloud API endpoint, authentication method, usage limits, and terms: verify with the account/API flow before implementation.
- Decide whether images are embedded in bundles or packaged as referenced files based on first real-world export size.
