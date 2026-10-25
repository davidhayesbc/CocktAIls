# ADR-0008: Kotlin Multiplatform shared core with native app shells

- **Status:** Accepted direction
- **Date:** 2026-10-25

## Context

Android is the first release target, but an iPhone version should be possible without reimplementing recipe identity, search, inventory matching, conflict resolution, and interchange behavior. The app should feel at home on each platform and use platform APIs for cameras, secure storage, files, and optional model runtimes.

## Decision

- Use Kotlin Multiplatform (KMP) for shared domain and data logic.
- Use native presentation shells: Kotlin/Jetpack Compose in `apps/androidApp` and Swift/SwiftUI in a future `apps/iosApp`.
- Use SQLDelight for the shared SQLite schema, queries, and migrations. Keep SQLDelight row IDs internal; domain and exported IDs remain UUIDv7.
- Begin with one `shared/core` module, organized by domain/application/data/interchange packages. Split Gradle modules only when independent build or dependency boundaries provide real value.
- Keep family/variant identity, recipe availability/search, scaling, import parsing/validation, revision ancestry, and merge-conflict detection in common Kotlin code with common tests.
- Keep camera/barcode capture, file pickers/share sheets, platform permissions, secure credential storage, and platform-specific local inference behind interfaces implemented in each app shell.
- Define provider-neutral assistant contracts in shared code only when the assistant implementation begins; provider credentials remain platform-local.
- Keep the core catalog as an independent versioned data pack in `catalogs/core`, with its own source/license manifest. Put platform-specific fonts, icons, and UI resources within each app.
- Keep the household bundle format independent of KMP, Kotlin serialization choices, and the SQLite schema so future non-Kotlin clients can read it.

## Initial repository layout

```text
apps/
  androidApp/       Android application, Compose UI, Android adapters
  iosApp/           Future iOS application, SwiftUI UI, Apple adapters
shared/
  core/             KMP domain, use cases, SQLDelight, import/export, common tests
catalogs/
  core/             Versioned recipe pack, manifest, attribution/license notices
docs/
  architecture/     ADRs
  planning/         Delivery plans
  product/          Product specifications
```

## Consequences

- Recipe matching, import behavior, and conflict rules have one implementation across platforms.
- The Android app can remain native in look and feel; the future iOS app can use SwiftUI and Apple interaction patterns.
- KMP adds Gradle/Native framework build complexity and requires careful Kotlin-to-Swift API design.
- A macOS machine with Xcode is required to build, sign, and test the complete iOS application. Linux cloud tasks can work on shared code and Android but cannot replace those Apple toolchain checks.
- SQLDelight is selected over Room-only persistence to share schema and migrations. This decision should be validated with a small Android/iOS persistence spike before significant app code is written.
- Barcode scanning, secure keys, file exchange UI, and local LLM runtime remain platform-specific and need per-platform validation.
