# ADR-0010: Staged generic recipe file import

- **Status:** Accepted for Phase 1
- **Date:** 2026-10-25

## Context

Household bundles preserve CocktAIls revisions and sync metadata, but they are not a suitable interchange format for recipe files from books, websites, spreadsheets, or another recipe app. Generic imports need to remain offline, preserve source attribution, and avoid silently merging unrelated versions of a drink.

## Decision

- Support user-selected CSV and JSON files up to 10 MiB. CSV uses a header row; JSON accepts an array of recipe objects or an object containing a `recipes` array.
- Require explicit title and ingredient field mappings. Instructions, yield/servings, style, family, and stable source-record ID mappings are optional.
- Present a validated preview before any write. Invalid rows, unsupported styles, invalid amounts/yields, malformed structure, duplicate source IDs, or unresolved fields block the whole import. Cancel has no storage side effects.
- Require a source name, license/terms description, and attribution. Store this provenance with every imported recipe source record and retain it in household bundle exports.
- Commit the complete batch in one database transaction. Reimporting an identical source record is a no-op; reusing a source name/record ID with changed content is an error, and no other rows in that batch are written.
- When a stable source-record ID is absent, derive a deterministic content identity within the named source. This gives repeated imports of unchanged content idempotent behavior without claiming that title is a stable identifier.
- Rows are grouped only when the user maps a family field. Linking an imported group to an existing cocktail family requires a separate explicit selection. Title similarity alone never associates recipes.
- Attaching an imported source to a compatible existing recipe variant requires explicit selection and an exact measured-formulation match. Imported source associations can later be unlinked or moved to another compatible variant; the source record and its provenance remain intact. Each association change is recorded as a revision so household import can detect divergent choices.
- Treat imported recipes as household-owned records with recorded source attribution; do not elevate a user-provided file into a bundled catalog or make a license-clearance claim on the user's behalf.

## Consequences

- Users can import their own recipe data without a server, source-specific network connector, or device credential.
- Mapping and provenance entry add steps, but make preview behavior and record origin explicit.
- The household backup format remains the mechanism for transferring imported records, revisions, and provenance between devices.
- Source-specific adapters, web fetching, automatic duplicate-family matching, and catalog packaging require separate review.
