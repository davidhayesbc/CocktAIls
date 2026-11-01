# The Amber Book — uploaded collection, version 1

Source: the user-supplied `TheAmberBook-main.zip`, `src/data/drinks.ts`, independently inspected before integration. Only its parsed recipe data and exact MIT notice are included. No upstream repository identity or named copyright holder was supplied; neither is invented here. Copyright (c) 2026. See **LICENSE** for the complete notice.

The parsed JSON was relayed as task data after the supported archive transfer failed. Its UTF-8 SHA256 is `8c0fe99512b0327924e0342bd0ba01cbf5235da23e45851dd3851b8225c6e002`, matching the inspector's file exactly. `source-records.json` preserves all 48 original objects and source IDs, including original categories, prose, ingredients, amounts, glassware, garnish and methods.

`recipes.json` is generated deterministically by `python3 tools/catalog/build_amber_pack.py`. It contains 48 recipes, including 7 source-designated mocktails. No photographs, source application code, scripts, UI or dependencies were imported.

## Adaptation

- Original measures, including fractions, ranges, counts, rinses, `Top` and `Pinch`, remain display strings. No oz/ml conversions or invented values. Automatic scaling is disabled for these catalog formulations; the database's unit serving is one source formulation, not an independently verified yield.
- Source names and IDs remain unchanged. Classic Martini and Dry Martini share a family, preserving distinct formulations. Other aliases are conservative punctuation/case matching. Overlapping starter recipes remain distinct source specifications within the same family; the existing eight starter formulations differ in quantities and/or ingredients. Household variants, notes and favourites are not replaced. Repeated installs use stable source IDs.
- French Blonde's base is Gin and its style Shaken, correcting the source's Bubbly classification because no sparkling ingredient is listed. The original classification remains in `sourceMetadata.originalRecord`. French 75 retains Gin as base and Bubbly as style. Bottle drink categories are independent.
- Mocktail is a source designation, not proof of zero alcohol or allergen absence. Strength and difficulty are editorial source labels, not measured ABV or verified ratings.
- A small explicit alias map removes `Fresh` from lemon/lime juice names, maps Bourbon to Bourbon whiskey, Bourbon or rye to Bourbon or rye whiskey, and the explicitly named triple sec to Triple sec. Egg white's explicit optional status is represented structurally. Originals remain in every source record. No other brand/style equivalences are inferred.
- Source measures and methods remain attributed under MIT. Garnish is appended as a labelled line. No recipe prose from the held datasets or third-party images is included.

## Compatibility

The optional v2 catalog format allows uploaded-data provenance without fabricated source/repository URLs and requires a full license notice and raw original source record. Existing v1 packs remain supported. Source metadata and the exact license notice use the existing recipe-source JSON field and survive household export/import without another database migration. License text is available from the collection card and each imported recipe, offline.
