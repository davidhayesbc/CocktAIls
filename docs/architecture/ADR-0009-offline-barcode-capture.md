# ADR-0009: Offline barcode capture

- Status: Accepted for Phase 1 Android
- Date: 2026-10-25

## Decision

Use CameraX for the Android camera preview and ZXing Core for UPC-A, EAN-8, and EAN-13 decoding. ZXing runs against camera luminance frames on the device. Ask for camera permission only after the user taps **Scan barcode**; manual entry is always available and remains usable after permission denial or camera failure.

Validate GTIN lengths and check digits before saving. Normalize UPC-A to its equivalent zero-prefixed GTIN-13 form only for comparison, while retaining the exact scanned or entered string in the product record. Confirm the generic ingredient and optional brand before saving. The product-to-ingredient relationship and inventory addition commit together locally and travel through the versioned household backup.

Do not bundle a product dataset. The later approved optional Open Food Facts lookup is defined in [ADR-0011](ADR-0011-optional-open-food-facts-lookup.md); it never makes barcode entry depend on connectivity. The shared core owns GTIN validation; a future iOS shell can use the same rules with an iOS camera adapter.

## Consequences

- UPC/EAN capture works offline and does not upload or retain camera frames.
- A barcode identifies a user-confirmed local product record; it does not claim to identify an ingredient from a remote catalog.
- Camera hardware is optional at install time. Devices without camera access can enter and save codes manually.
- CameraX and ZXing Core are Apache-2.0 libraries; source/license notices remain with their upstream distributions.
- Camera preview/focus behavior still needs physical-device validation on the Pixel 10 Pro and Pixel 8.
