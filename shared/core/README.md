# Shared core

Planned Kotlin Multiplatform module shared by Android and iOS. Keep UI and platform APIs out of common code.

Initial package areas:

- `domain`: households, ingredients/products, cocktail families/variants, inventory, revisions, source provenance
- `application`: use cases and repository interfaces
- `data`: SQLDelight schema, queries, migrations, repository implementations
- `matching`: availability search, scaling, family/variant match suggestions
- `interchange`: versioned bundle and CSV/JSON import/export validation

Put shared behavior tests in `commonTest`; add platform integration tests in each app. Keep the exported household bundle format platform- and database-independent.
