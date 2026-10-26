# ADR-0007: Track physical bottles and stage intake locally

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The previous pantry uniqueness rule allowed only one stock level for an ingredient at a home. A real bar can contain several bottles of the same product, with different fullness levels and homes. Large collections also need a draft intake that can pause and survive app recreation without publishing partial entries to household sync.

## Decision

- An `inventory_item` represents one physical bottle. Its ID and revision ancestry remain stable; `product_id` links the bottle to a shared product identity when known.
- Multiple bottle rows may share a product, ingredient, or home. Recipe availability remains ingredient based: an ingredient is available when at least one bottle at the selected home is not `OUT`.
- Existing database rows migrate one-for-one. Their IDs, stock levels, timestamps, authors and revision history are preserved. Migration links a bottle to a product only when exactly one product exists for its ingredient; ambiguous links stay unset.
- Intake sessions and drafts are device-local and are not part of household bundles. Confirmation promotes all drafts to revisioned bottle records in one transaction. A confirmed-session marker makes a repeated confirmation safe. Cancel removes draft rows and their local photo bytes.
- Label photos are optional user captures. They are previewed and re-encoded as bounded JPEGs without EXIF before attachment; capture files remain in a narrow app-cache FileProvider path.
- Barcode capture and manual entry work without network or model inference. Open Food Facts and Firecrawl are independently opt-in; when enabled, a user-initiated bottle scan starts lookup automatically. Firecrawl checks at most three distinct result pages per scan (one search plus up to three page fetches), stopping at the first exact barcode match or quota/authentication/rate-limit failure. Results remain attributed and reviewable. Intake drafts and fetched page text are not included in household export.
- Each user supplies their own Firecrawl API key. Android stores it as AES-GCM ciphertext in app-private preferences with the encryption key in Android Keystore. The key is sent only to the fixed Firecrawl HTTPS API host and is not part of household records, diagnostics, or app backups. There is no shared project key or backend.
- Ollama Cloud extraction is an explicit, optional step after source-page review. It receives selected page text after removing links, URLs, and control characters; other personal details present in the page may remain. Its candidate fields are checked against exact evidence quotes and shown temporarily for human review. The app does not copy these suggestions into bottle fields.
- On-device inference is optional and requires the user to import and enable the reviewed model file. Barcode digits are removed from inference input and verified locally. No model is bundled or downloaded automatically; manual intake remains usable without it. Pixel memory, speed, and extraction quality still require physical-device validation.

## Consequences

- Household backups now carry separate inventory records for duplicate bottles. Older backups without `productId` remain valid.
- Legacy ingredient-wide stock editing updates every bottle for that ingredient; the bottle list edits one selected bottle at a time.
- Web search uses the user's own plan and may consume provider quota. Search never starts on scan, and the user's free/paid plan setting is not modified by the app.
- The local model runtime/download and public contribution remain future work. No model artifact is bundled. A device-only enrichment queue stores job metadata but not source text, search queries, generated output, or credentials; interrupted jobs pause and require a user action to retry.
