# CocktAIls

An offline-first cocktail library and household bar companion for Android. The app helps people track bottles at multiple locations, find and make drinks, record what they thought, and share their data without requiring a hosted service.

## Product documents

- [MVP specification](docs/product/MVP_SPEC.md)
- [Phase 1 plan](docs/planning/PHASE_1_PLAN.md)
- [Phase 1 implementation status](docs/planning/PHASE_1_STATUS.md)
- [Architecture decision records](docs/architecture/)
- [UI design direction and sketches](docs/design/)

## Repository layout

- `apps/androidApp/`: first-release native Android app (Compose).
- `apps/iosApp/`: future native iPhone app (SwiftUI); requires macOS/Xcode to build and sign.
- `shared/core/`: Kotlin Multiplatform domain, persistence, matching, and import/export behavior.
- `catalog-packs/iba-wikipedia-starter/`: separately licensed, attributed offline starter recipe pack.
- `catalogs/core/`: catalog pack policy and directory notes.
- `docs/`: product, architecture, and delivery decisions.

The first release targets Android. Shared Kotlin Multiplatform domain/data logic and a portable file format are intended to support a future native iPhone app. Household records stay on-device and can be shared by versioned file exchange. The Recipes screen offers an optional local install of eight source-attributed classics; see the pack notice and [bounded source review](docs/planning/CATALOG_DATA_REVIEW.md). Other cocktail or product datasets require separate source and license review. Network services and language models remain optional capabilities layered on later.

## Build the Android app

Requirements: JDK 17 or newer, Android SDK Platform 36 and Build Tools 36.0.0, and network access to Maven Central and Google's Maven repository for first-time dependency resolution.

```bash
./gradlew :apps:androidApp:assembleDebug
```

The debug APK is created at `apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk`. The Android app supports local ingredient and recipe entry, multiple homes, approximate stock levels, the offline starter recipes, recipe search/availability filters, tasting notes, shop suggestions, barcode capture/manual entry, photos, and manual backup/restore. See the Phase 1 status for current checks and remaining physical-device validation.

## Current state

The product and architecture foundation is in place, and Android development has started. The current build includes multiple local homes, ingredient and recipe entry, approximate inventory tracking, recipe search/style filters, tasting notes, and shop insights. See the [Phase 1 plan](docs/planning/PHASE_1_PLAN.md) for the remaining milestones and the app READMEs for platform status.
