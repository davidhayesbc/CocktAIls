# ADR-0003: Household scope and approximate inventory

- **Status:** Accepted baseline
- **Date:** 2026-10-25

## Context

The same household shares recipes and reviews, but ingredient availability differs by house. Users want useful quantity estimates without the burden of exact measurement.

## Decision

- Model a household as the shared data scope and a location as a named place within that scope.
- Household location records contain shareable names and inventory relationships. Physical coordinates, match radii, active automatic/manual mode, and last selection are device-local routing preferences excluded from household exports.
- Give generic ingredients stable IDs independent of branded products. Recipe ingredient lines reference generic ingredients; inventory can reference a product or generic ingredient.
- Model inventory as a location-specific record, not a property on an ingredient.
- Use the discrete levels `FULL`, `MOSTLY_FULL`, `HALF`, `LOW`, `OUT`; allow an optional numeric quantity/unit for users who want precision.
- Absence of an inventory record means `UNKNOWN`/not tracked. `OUT` means the user tracks it and has none. Do not encode unknown as out.
- Record the last observation time, author, and device for user context. Concurrent inventory changes follow ADR-0002 and are reviewable conflicts; timestamps alone do not select a winner.
- Tasting notes/reviews are separate records with author, date, optional rating, recipe link, and optional location link.

## Consequences

- “What can I make at Home?” can be computed without affecting the Cabin pantry.
- Generic recipe matching works when the household owns different brands.
- The user can update stock quickly while optional precision remains available.
- Ingredient/product matching and substitutions need explicit rules; the app must not assume every product is interchangeable.
