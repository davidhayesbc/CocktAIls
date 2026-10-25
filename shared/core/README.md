# Shared core

Kotlin Multiplatform module shared by Android and iOS. The current implementation includes canonical UUIDv7 generation, household/location/ingredient/inventory and recipe models, the SQLDelight schema and v1-to-v3 migrations, a local repository, append-only JSON revision payloads with local parent links, and spirit unlock ranking. Keep UI and platform APIs out of common code.

The first debug build used a UUIDv7 formatter that emitted malformed variant groups. The migrations deliberately preserve existing text IDs and all foreign-key relationships as opaque identifiers; they do not rewrite them. New IDs use canonical UUIDv7 formatting. Import and sync code must accept opaque legacy IDs.

Local edits now write revision payloads and link each revision to its immediate local parent. Archiving a recipe variant writes a deletion revision and tombstone, retaining the underlying record and hiding it from active recipe queries. The device ID and selected home/member are stored in `local_preference`, which is device-local state and must be excluded from household exports. Conflict detection, multi-parent merge revisions, tombstone import/merge, and bundle import/export are still pending.

Initial package areas:

- `domain`: households, ingredients/products, cocktail families/variants, inventory, revisions, source provenance
- `application`: use cases and repository interfaces
- `data`: SQLDelight schema, queries, migrations, repository implementations
- `matching`: availability search, scaling, family/variant match suggestions
- `interchange`: versioned bundle and CSV/JSON import/export validation

Shared behavior belongs in `commonMain`, with common tests in `commonTest`; add platform integration code only where required. Keep the exported household bundle format platform- and database-independent.
