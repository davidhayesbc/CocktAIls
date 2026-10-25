# Android application

Native Android app using Kotlin and Jetpack Compose. The first slice persists a default household and location, supports manual ingredient entry and stock-level updates, and provides Make, My Bar, Recipes, More, and At the shop screens. It consumes `shared/core` for SQLite and domain logic.

Barcode scanning, location services, catalog imports, and model integration are not implemented yet. Build instructions are in the root README. See [ADR-0008](../../docs/architecture/ADR-0008-kotlin-multiplatform-repository.md) and the [Phase 1 plan](../../docs/planning/PHASE_1_PLAN.md).
