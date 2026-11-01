# Offline starter recipe data

The app exposes **one Add or update starter collection action**. It installs these two attributed source datasets in one database transaction:

| Source | Stable pack ID | Recipes |
| --- | --- | --- |
| Wikipedia / IBA starter | `cocktails-wikipedia-iba-starter` | 8 |
| Uploaded Amber Book | `uploaded-amber-book` | 48 |

Together they contain 56 source formulations in 48 drink families, including seven source-designated mocktails. Overlapping names retain materially different recipes as variants. Source identity is deliberately unchanged: existing installations of either or both packs are recognized, and repeated installation adds only missing records. Household edits, source-linked copies, archived recipes, favourites and tasting notes are not overwritten. A later source failure rolls back the whole combined install.

## Canonical structured JSON

- **`iba-wikipedia-starter/recipes.json`** is the canonical, human-readable v1 data, used directly as a runtime asset. Edit this JSON for reviewed starter-source corrections; retain stable IDs, revision links and attribution.
- **`amber-book/source-records.json`** is the canonical original Amber data, preserved byte-for-byte from the reviewed upload. **`amber-book/recipes.json` is generated**, not a second manually maintained source. The explicit transformations live in `tools/catalog/build_amber_pack.py`; the full MIT notice is read from `amber-book/LICENSE`.
- **`catalog-pack.schema.json`** documents both existing runtime JSON formats. `decodeCatalogPack` enforces the runtime contract, including unique source IDs, bounds, source links, notices and raw-quantity constraints. Core tests decode the actual checked-in files and exercise both legacy install orders, combined installation and backup/restore.

Regenerate Amber runtime data with:

```sh
python3 tools/catalog/build_amber_pack.py
```

Verify deterministic generation without modifying files:

```sh
python3 tools/catalog/build_amber_pack.py --check
```

Android CI runs that check. An edit to the pinned original Amber upload requires explicit source review and an intentional checksum update; do not silently rewrite historical source records. Generated assets may repeat original fields for portable provenance, but are derived from the single canonical JSON source.

Raw amounts such as fractions, ranges, Top, Pinch and rinses remain strings, not guessed conversions. Recipe style and base spirit remain separate from bottle categories. A source mocktail label is not an alcohol/allergen guarantee.

## Notices and credits

Required notices remain distributed in these assets and source metadata. Existing per-recipe source attribution remains available. The unified install card has a compact Source notices action; an About/Credits screen is deferred. This change does not import Bar Assistant, stevana or any held datasets, images or third-party application code, and does not create a separate public repository.
