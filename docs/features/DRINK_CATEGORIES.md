# Bottle drink categories

Each physical bottle has an optional, manually chosen drink category. Intake/review and My Bar provide a searchable, typeable suggestion field. Suggestions cover spirit styles, liqueurs, aperitifs, vermouths, wines, bitters, syrups and mixers. They are practical starting points, not an official or exhaustive taxonomy. Search ignores case and accents and treats whisky/whiskey alike; custom values keep their spelling. Clear the field to leave it unset.

The existing ingredient `category` is a broad recipe-matching classification (Spirit, Mixer, etc.). It remains independent and unchanged. Two bottles sharing an ingredient can have different drink categories. Manual creation still offers the existing ingredient group selection.

Database version 10 adds `drink_category TEXT NOT NULL DEFAULT ''` to inventory items and intake drafts. Migration 9 changes no existing fields or ingredient identities. Old bottles and pending drafts start unset; no classification is guessed from brands or old broad groups. Only the dedicated category-edit operation can write a draft's category. General draft updates, OCR, lookup retries and stock changes preserve it, including an explicit clear.

Confirmed bottles carry `drinkCategory` in bar exports and household inventory records. The household bundle format remains v2 with an optional additive string field: older bundles load with an unset category for new records and do not overwrite an existing category when the key is absent. An explicit empty string clears it. Existing conflict/revision handling applies to inventory edits. Older applications cannot display this new field and may omit it when re-exporting; use this version or newer for category-preserving round trips. Intake drafts remain local, as before.

Validation covers v9-to-v10 migration with an existing bottle and unfinished draft, per-bottle isolation, custom/cleared values, stale draft updates, stock changes, export/import, suggestions and Compose input/selection. Device CI exercises the actual review screen receiving delayed suggestions after manual edits and clears.
