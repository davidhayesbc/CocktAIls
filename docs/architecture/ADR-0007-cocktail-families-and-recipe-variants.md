# ADR-0007: Group equivalent recipes under cocktail families

- **Status:** Accepted direction
- **Date:** 2026-10-25

## Context

Multiple recipe sources commonly publish the same drink under slightly different names, quantities, or instructions. Showing every imported record in one flat list creates duplicates. Collapsing all similarly named records into one recipe would hide real historical, regional, or personal variations and could discard source attribution.

## Decision

- Model a **CocktailFamily** as the canonical identity used for browse/search grouping. It has a stable app-generated UUIDv7 ID, canonical display name, aliases, and family-level metadata/tags.
- Model each distinct formulation as a **RecipeVariant** linked to a family. A variant has its own stable ID, ordered ingredient lines, measurements, instructions, yield, and optional style/region/era labels.
- A **variant** means a distinct formulation; a **revision** means an edit history step for the same record. Editing a household variant creates a new revision, while changing the formula by forking a catalog entry creates a new household variant linked to the original.
- Model imported source occurrences as separate **RecipeSourceRecord** entries linked to a family and, when known, a variant. Keep the source's own ID, title, URL, revision, attribution, license, and raw/source-specific metadata. Equivalent source occurrences can point to the same variant without losing provenance.
- Namespace external IDs by source/dataset. Persist each user-confirmed external source-record-to-family/variant link so re-imports remain idempotent and do not repeat identity decisions.
- Group household-authored variations under a family when the user confirms the relationship. A household variation can also link to its parent variant while retaining an independent editable record.
- Browse/search shows one card per family. Family detail shows variants, attribution, availability for the selected location, and user notes/ratings at the appropriate level. Users may favorite a family or a specific variant.
- Import matching is a candidate-generation aid, not an identity oracle. Normalize case, punctuation, whitespace, and known aliases; compare ingredient identities, optionality, amounts/ratios, and instructions where appropriate. Matching titles alone is insufficient.
- Exact same namespaced source record IDs are idempotent. For cross-source matches, show candidates and a confidence/reason summary. User confirmation links source records to an existing family/variant; uncertain records remain separate until reviewed.
- Do not silently combine conflicting text, ingredient amounts, license terms, or provenance. When two source records are equivalent, preserve each attribution and source payload while selecting a display variant. When formulations differ materially, retain separate variants in the same family.
- Make family and variant associations reversible. An incorrect grouping can be split without deleting source records or household revisions.

## Consequences

- Users see a concise catalog while retaining real recipe variation and source history.
- Search availability can report whether any variant is makeable and identify the specific matching variants.
- Import requires duplicate review and family/variant UI in addition to field mapping.
- Canonical naming and aliases become maintained data. Automated similarity can produce false positives/negatives, so it must not perform irreversible merges.
- Revisions and tasting notes must link to stable variant IDs; family-level reviews should be explicitly distinguished if supported later.
- Data packs need stable family/variant/source IDs across releases so favorites, notes, and imported updates remain attached.
