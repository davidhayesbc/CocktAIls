# ADR-0006: Separate catalog packs and provenance-aware imports

- **Status:** Accepted direction; source records pending audit
- **Date:** 2026-10-25

## Context

The app needs a useful offline recipe catalog, while users also need to add their own recipes and import content from other sources. Third-party datasets may have different licenses, attribution requirements, update terms, and image rights. Merging these records into one undifferentiated collection would make provenance, editing, and redistribution hard to manage.

## Decision

- Ship a small core recipe catalog as a separately versioned data pack, independent of application code and household records.
- Retain source ID, source URL/revision, author/contributor attribution, license/version, retrieval date, and modifications at record or pack granularity as appropriate. Keep required license/attribution notices with the pack and in the app's attribution view.
- Treat catalog source records as immutable/read-only. A household member may favorite or annotate a source record; editing creates a household-owned copy linked to the source record and preserving its original provenance.
- Provide a staged import pipeline: decode source format, validate, normalize, capture provenance/license metadata, preview additions/duplicates/updates/conflicts, then commit transactionally.
- Support the app's own versioned bundle first. Add source-specific import adapters only after rights and format are reviewed. Generic CSV/JSON import can be supported where mapping is explicit; a public API is not automatically permission to redistribute its results.
- The Wikipedia CSV subset of `rasmusab/iba-cocktails` is the leading core seed candidate because Dot's research reports an explicit CC BY-SA 3.0 grant for that subset. Audit source-page attribution and modifications for each selected record before bundling. Do not infer rights for the separately scraped `iba-web/` directory.
- Keep product/barcode datasets separate from recipes and code. Defer a bundled Open Food Facts-derived dataset until coverage, size, and ODbL obligations are reviewed. Manual barcode entry remains available.
- Exclude NC-only datasets and uncleared images/prose from the default distributable packs. Do not treat a repository's code license as clearance for all record contents.

## Consequences

- A modest audited catalog can provide a useful first-run experience while manual and imported household content remains independent.
- Catalog updates can be delivered through app releases or user-imported packs without a required backend.
- Attribution and license display are product requirements, not a later documentation fix.
- A copied recipe does not mutate a bundled dataset record, and user reviews stay attached to the source recipe or chosen copy explicitly.
- Multiple licenses increase packaging and review work. Per-pack and per-record metadata must survive export/import.
- Open Food Facts may still be valuable, but its database license and useful offline extract need separate analysis before distribution.
