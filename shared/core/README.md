# Shared core

Kotlin Multiplatform module shared by Android and iOS. The current implementation includes UUIDv7 IDs, household/location/ingredient/inventory and recipe models, the SQLDelight schema and v1-to-v2 migration, a local repository, and spirit unlock ranking. Keep UI and platform APIs out of common code.

The first debug build used a UUIDv7 formatter that emitted malformed variant groups. The v1-to-v2 migration deliberately preserves existing text IDs and all foreign-key relationships as opaque identifiers; it does not rewrite them. New IDs use canonical UUIDv7 formatting. Import and sync code must accept opaque legacy IDs.

Initial package areas:

- `domain`: households, ingredients/products, cocktail families/variants, inventory, revisions, source provenance
- `application`: use cases and repository interfaces
- `data`: SQLDelight schema, queries, migrations, repository implementations
- `matching`: availability search, scaling, family/variant match suggestions
- `interchange`: versioned bundle and CSV/JSON import/export validation

Shared behavior belongs in `commonMain`, with common tests in `commonTest`; add platform integration code only where required. Keep the exported household bundle format platform- and database-independent.
