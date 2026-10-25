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
- Barcode capture and manual entry work without network or model inference. Open Food Facts lookup and Firecrawl search are independently opt-in and user initiated. Firecrawl results are source attributed, shown for review, and never copied into bottle fields automatically. Intake drafts and Firecrawl responses are not included in household export.
- Each user supplies their own Firecrawl API key. Android stores it as AES-GCM ciphertext in app-private preferences with the encryption key in Android Keystore. The key is sent only to the fixed Firecrawl HTTPS API host and is not part of household records, diagnostics, or app backups. There is no shared project key or backend.
- Local model inference is not implemented in this release. The app must not expose an enabled-looking model control until a runtime and tested model are available. Any later local model remains optional and cannot block manual intake.

## Consequences

- Household backups now carry separate inventory records for duplicate bottles. Older backups without `productId` remain valid.
- Legacy ingredient-wide stock editing updates every bottle for that ingredient; the bottle list edits one selected bottle at a time.
- Web search uses the user's own plan and may consume provider quota. Search never starts on scan, and the user's free/paid plan setting is not modified by the app.
- On-device AI extraction, structured source evidence, persistent enrichment queues, and public contribution remain future work. No model artifact is bundled by this decision.
