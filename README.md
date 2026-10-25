# CocktAIls

An offline-first cocktail library and household bar companion for Android. The app helps people track bottles at multiple locations, find and make drinks, record what they thought, and share their data without requiring a hosted service.

## Product documents

- [MVP specification](docs/product/MVP_SPEC.md)
- [Phase 1 plan](docs/planning/PHASE_1_PLAN.md)
- [Architecture decision records](docs/architecture/)
- [UI design direction and sketches](docs/design/)

## Repository layout

- `apps/androidApp/`: first-release native Android app (Compose).
- `apps/iosApp/`: future native iPhone app (SwiftUI); requires macOS/Xcode to build and sign.
- `shared/core/`: Kotlin Multiplatform domain, persistence, matching, and import/export behavior.
- `catalogs/core/`: separately versioned, provenance-aware offline recipe data pack.
- `docs/`: product, architecture, and delivery decisions.

The first release targets Android. Shared Kotlin Multiplatform domain/data logic and a portable file format are intended to support a future native iPhone app. It is designed around local storage and versioned file exchange. It should ship with a small, separately versioned core recipe catalog and support source-aware imports. Dot's research identifies candidate recipe sources, but record-level attribution and redistribution review is still required before bundling a source dataset. Product database selection is also pending. Network services and language models remain optional capabilities layered on later.

## Current state

This repository contains product and architecture planning only. Phase 1 implementation has not started. The CocktAIls GitHub remote currently has no commits or advertised branches.
