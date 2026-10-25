# ADR-0001: Android-first, offline-first application

- **Status:** Superseded by ADR-0008
- **Date:** 2026-10-25

## Context

The app must run on Android, be useful without a network connection, and avoid a developer-operated service and ongoing operating cost. Household data should remain portable and shared initially through files.

## Decision

- The original decision was to build Android with Kotlin/Jetpack Compose and Room/SQLite. ADR-0008 supersedes the Android-only persistence choice with a Kotlin Multiplatform shared core and multiplatform SQLite layer.
- The following constraints remain active: keep domain logic and export/import formats independent of UI and database row IDs; treat the device database as authoritative for local use; require no login, backend, or network for core workflows; and use explicit schema migrations.
- Treat the device database as authoritative for local use; network services are optional integrations.
- Do not require login, a backend, or connectivity for core workflows.
- Keep domain identifiers portable and use explicit schema migrations.

## Consequences

- Core browsing and edits work offline and app hosting has no required backend cost.
- Android-native APIs fit barcode scanning, local storage, secure credential storage, and possible on-device model runtimes.
- iOS is not an initial target; the versioned interchange format and domain model should avoid Android-only serialization.
- Database migration tests and backup/import compatibility remain important.
- This decision does not select a specific barcode, OCR, or LLM runtime; those need device-level validation.
