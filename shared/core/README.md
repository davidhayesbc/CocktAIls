# Shared core

Kotlin Multiplatform module shared by Android and iOS. The current implementation includes UUIDv7 IDs, base household/location/ingredient/inventory models, the SQLDelight schema, a local repository, and spirit unlock ranking. Keep UI and platform APIs out of common code.

Initial package areas:

- `domain`: households, ingredients/products, cocktail families/variants, inventory, revisions, source provenance
- `application`: use cases and repository interfaces
- `data`: SQLDelight schema, queries, migrations, repository implementations
- `matching`: availability search, scaling, family/variant match suggestions
- `interchange`: versioned bundle and CSV/JSON import/export validation

Shared behavior belongs in `commonMain`, with common tests in `commonTest`; add platform integration code only where required. Keep the exported household bundle format platform- and database-independent.
