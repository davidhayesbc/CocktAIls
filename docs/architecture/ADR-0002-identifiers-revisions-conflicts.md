# ADR-0002: Stable IDs and explicit concurrent-edit resolution

- **Status:** Accepted baseline
- **Date:** 2026-10-25

## Context

Two phones must create and edit shared records offline, with no central ID allocator. Import/export should be a stepping stone to eventual synchronization. Silent last-write-wins would discard useful recipe edits, and device clocks cannot be trusted for ordering.

## Decision

- Assign each persistent domain record a UUIDv7 ID on the creating device. IDs are globally generated without a server. Treat the UUID timestamp as a sorting hint only, never as proof that one edit is newer.
- Store UUIDs canonically in the interchange format; SQLDelight/SQLite may use an efficient representation internally as long as conversion is stable.
- For mutable shared records, store immutable revisions. A revision contains its own UUIDv7 `revision_id`, stable `record_id`, one or more `parent_revision_ids`, schema version, author/member ID, device ID, and UTC creation time.
- A normal edit has the prior revision as its sole parent. If imported and local revisions have the same ancestor and neither descends from the other, preserve both as concurrent heads and create a conflict.
- Resolution by choosing a version creates a new revision with both conflicting revisions as parents. Field-level merge is allowed when the record type can be merged safely; it also creates a two-parent revision.
- Never resolve conflicts by timestamp or silently overwrite. Keep unresolved heads until the user acts.
- Use tombstone revisions for deletions. Keep append-only records (such as tasting notes) as distinct stable IDs, so concurrent additions naturally coexist.
- Keep derived indexes, search documents, and current-head pointers rebuildable from domain records/revisions; they are not identity.

## Consequences

- Offline creation and later reconciliation need no server or trusted clock.
- Importing the same bundle more than once is idempotent by record/revision ID.
- Users receive explicit conflict review instead of losing edits.
- Revision history and tombstones consume more storage and need retention policy later.
- Schema migrations must preserve old revisions. The merge UI needs clear field provenance and must guard relationships (for example, recipe ingredient references).
- Inventory updates can also conflict; show competing estimates rather than using last-write-wins when their ancestry diverges.
