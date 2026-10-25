# ADR-0011: Optional Open Food Facts product lookup

- Status: Accepted
- Date: 2026-10-25
- Supersedes the remote-lookup deferral in [ADR-0009](ADR-0009-offline-barcode-capture.md)

## Context

Barcode capture and manual product entry must remain useful offline. Users asked for optional product identification, but third-party product data has distinct provenance and licensing. The app's household records and backups must not silently absorb provider data or lose its source context.

## Decision

- Use the public Open Food Facts Product Opener v3.6 single-product read endpoint only after the user taps **Look up on Open Food Facts**. Do not issue requests when a barcode is scanned, typed, or saved.
- Request only `code`, `product_name`, `brands`, `quantity`, `product_quantity`, `product_quantity_unit`, `last_modified_t`, and `schema_version`. Do not request images, categories, nutrition, or ABV in this version.
- Send only the scanned/entered barcode in the request path plus the required application/version User-Agent. Open Food Facts receives the normal network IP address. Never send pantry, household/member, location, photo, recipe, or preference data.
- Show a matching household product locally before the online action. Check the app's local provider cache before making an HTTP request. Keep the user-entered barcode and the provider's returned canonical code as distinct values; do not pad, remove leading zeroes, or guess equivalent identifiers for the request.
- Display provider product name, brand, and quantity as a separate suggestion. Do not copy it into household fields or revisions. Ingredient name/category and optional product metadata remain user-entered; ABV is not fetched or stored. Saving the household form never counts as accepting or relicensing the source data.
- Store suggestions with source, license, request/canonical code, provider modification time, fetch time, schema version, and warning IDs in a private device-local cache. The cache is separate from the household database and household backup/export. A restore on another device may need a new lookup. No product images are requested or cached.
- Attribute the provider as Open Food Facts and distinguish the database ODbL 1.0 from the contents DbCL beside suggestions. Keep the source/license explanation in [OPEN_FOOD_FACTS.md](../licensing/OPEN_FOOD_FACTS.md). This is not blanket legal clearance for republication or bulk reuse.
- Use HTTPS, five-second connect/read timeouts, a bounded response, a local request throttle, and no automatic retries or redirects. Treat 429, missing products, wrong product types, malformed responses, and network failures as nonfatal; manual entry and saving remain available.

## Consequences

- Product lookup depends on network access only after an explicit user action; local scanning, entry, browsing, and household data remain offline-capable.
- Open Food Facts does not guarantee spirits coverage. The app does not claim that a missing result means the barcode is invalid.
- Provider details stay device-local and do not sync between household members in this release. The other phone may perform its own explicit lookup.
- Expanding fields, syncing/cache export, bulk packs, or adding images requires a separate source, mapping, privacy, and license review.

## References

- [Open Food Facts API introduction](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/)
- [Product lookup endpoint](https://openfoodfacts.github.io/documentation/docs/Product-Opener/v3/products/get-api-v3-product-code/)
- [License guidance](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/tutorials/license-be-on-the-legal-side/)
- [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
