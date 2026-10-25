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
- Keep optional user product photos as bounded JPEG assets on the product record. Include them in household JSON only when the user exports a household bundle; validate the JPEG structure, checksum, and size before applying. The Android photo picker re-encodes pixels locally to remove EXIF/XMP metadata. Current limits are 1 MiB per image and 6 MiB total photos per bundle.
- Exclude device-specific preferences, model cache files, credentials, access tokens, and provider configuration from household bundles.
- Provide an explicit backup/restore operation. Restore cannot replace the current database without confirmation and a recoverable backup.

## Consequences

- The format can be reused by future sync clients and other platforms.
- Import requires validation and a preview/commit boundary, which is more work than replacing a database file but safer.
- Inline base64 assets make household JSON larger; the current bundle and photo size limits bound memory and storage use.
- Format compatibility and deterministic merge behavior need automated tests before release.
