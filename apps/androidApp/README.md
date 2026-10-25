# Android application

Native Android app using Kotlin and Jetpack Compose. The current slice supports multiple local homes, manual ingredient and recipe entry, approximate stock levels, recipe search by name/ingredient/style, family-grouped versions, ready-to-make filtering, and per-member tasting notes. At the shop separates restocks from spirit recommendations based on newly makeable recipe families. It consumes `shared/core` for SQLite and domain logic.

Automatic location selection, barcode scanning, catalog imports, household sync/import-export, member switching, and model integration are not implemented yet. Build instructions are in the root README. See [ADR-0008](../../docs/architecture/ADR-0008-kotlin-multiplatform-repository.md) and the [Phase 1 plan](../../docs/planning/PHASE_1_PLAN.md).
