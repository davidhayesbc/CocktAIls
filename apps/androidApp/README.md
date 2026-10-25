# Android application

Native Android app using Kotlin and Jetpack Compose. The current slice supports multiple local homes, manual ingredient and recipe entry, approximate stock levels, recipe search by name/ingredient/style, family-grouped versions, ready-to-make and missing-one/two-required-ingredient filters, and per-member tasting notes. At the shop separates restocks from spirit recommendations based on newly makeable recipe families. It consumes `shared/core` for SQLite and domain logic.

Recipe variants can be archived with a deletion revision and tombstone while preserving their history. Household JSON backups are available from More: export to a file, select a backup to preview, resolve any conflicts, then apply the import. Canceling the preview leaves local data unchanged. Automatic location selection, barcode scanning, catalog imports, generic recipe CSV/JSON field mapping, edit/duplicate, favorites/scaling, and model integration are not implemented yet. Build instructions are in the root README. See [ADR-0008](../../docs/architecture/ADR-0008-kotlin-multiplatform-repository.md), the [Phase 1 plan](../../docs/planning/PHASE_1_PLAN.md), and the [implementation status](../../docs/planning/PHASE_1_STATUS.md).

## Sideload preview builds

Pushes to `main` and manually dispatched workflow runs upload a debug-signed APK as a GitHub Actions artifact for 30 days. GitHub downloads that artifact as a ZIP archive: extract it first, then open `androidApp-debug.apk` from Files to start Android's package installer. Pull requests run verification and builds but do not publish an artifact. The APK is a development preview, not a production release. It uses package `com.davidhayesbc.cocktails`, minimum Android API 26 (Android 8.0), and a CI version name containing the run and commit identity.

Each hosted runner creates its own Android debug signing key. APKs from different runs therefore cannot update each other in place. To install a different run, uninstall the previous app first; uninstalling removes its local database. No stable signing key or production signing secret is configured.
