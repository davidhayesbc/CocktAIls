# BC product reference data

Contains information licensed under the Open Government Licence – British Columbia.

Source: BC Liquor Store Product Price List, July 2026, published by the Province of British Columbia / Liquor Distribution Branch.

Catalogue: https://catalogue.data.gov.bc.ca/dataset/bc-liquor-store-product-price-list-historical-prices

Licence URL supplied by the catalogue: https://www2.gov.bc.ca/gov/content?id=A519A56BC2BF44E4A008B33FCF527F61

Licence title: Open Government Licence - British Columbia. Licence ID: `2`.
The same retained catalogue metadata also reports `isopen: false`; this inconsistency is preserved in `catalogue-package-show.json` and `products.json`, not silently corrected. The explicit licence title/URL are the attribution basis. This is not a claim that the boolean was resolved by the publisher.

The files are a retained snapshot downloaded on 2026-11-14, not a fresh live retrieval. Dataset metadata was modified 2026-10-14T17:30:04.945513; the CSV resource reports last_modified 2026-10-14T17:30:04.928730. The data's named reporting period remains July 2026. The live licence page could not be fetched during implementation; the catalogue assignment and source snapshot were byte-verified.

No government endorsement is implied. Product names and classifications remain source claims; availability, prices, current product specifications, alcohol-free status, and allergen safety are not guaranteed. No retail website text, images, logos, or private household data are included.

Transformations: numeric UPC strings are preserved alongside check-digit-validated zero-padded GTIN-14 candidates. Ambiguous short codes, duplicate keys, non-beverage classifications, invalid codes and non-single-container packages are retained but excluded from automatic matching. Decimal litres are displayed as millilitres for eligible single containers. Brand is never inferred from product names. The raw CSV retains the original values, including historical prices; prices are not used by the app.
