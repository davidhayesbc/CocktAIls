# ADR-0004: Versioned household bundles for sharing

- **Status:** Accepted baseline
- **Date:** 2026-10-25

## Context

Manual transfer is acceptable for the first release, but records need a path to later synchronization. Import must be repeatable and must not erase concurrent edits.

## Decision

- Define a versioned, documented household bundle independent of the SQLDelight/SQLite schema. Include bundle format version, export timestamp for display, household ID, records, revisions, and explicit tombstones.
- Keep source provenance and license/attribution metadata with imported catalog records.
- Import by stable record/revision IDs. Identical repeats are no-ops; new revisions extend known ancestry; divergent revisions remain as conflicts.
- Preview counts and conflicts before applying. Validate IDs, relationships, size limits, supported versions, and checksums before committing the import transaction.
- Treat unknown newer schema versions as unsupported and leave the local database unchanged.
- Export photos as optional sidecar assets referenced by stable asset IDs and checksums; exclude them by default if the user chooses a compact bundle.
- Exclude device-specific preferences, model cache files, credentials, access tokens, and provider configuration from household bundles.
- Provide an explicit backup/restore operation. Restore cannot replace the current database without confirmation and a recoverable backup.

## Consequences

- The format can be reused by future sync clients and other platforms.
- Import requires validation and a preview/commit boundary, which is more work than replacing a database file but safer.
- Assets make archive creation larger; streaming and size warnings may be needed.
- Format compatibility and deterministic merge behavior need automated tests before release.
