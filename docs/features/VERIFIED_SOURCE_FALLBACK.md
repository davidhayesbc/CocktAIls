# Bounded verified-source fallback

The first exact-barcode page can be incomplete or a bot challenge. Challenge pages are unusable evidence: the app skips them, does not send them to the model, and never solves or bypasses their checks.

Within the existing maximum of three automatic page fetches, an incomplete verified page can be replaced by a richer independently verified page. After finding a verified page, additional requests are limited to exact-barcode search hits advertising explicit quantity or ABV information. Search text only suggests where to fetch; it never supplies accepted facts. Existing local parsers validate facts from the fetched pages to compare coverage. Replacement requires strictly greater supported core-field coverage and no contradictory known product identity, size or ABV. Explicit source conflicts stop replacement and remain field-rejection metadata through review; disputed fields are removed from the extraction and cannot be silently filled by a later label. Facts from different pages are not combined.

If later pages are unavailable, mismatched or no richer, the original verified page remains selected. Quota/authentication/rate limits retain their existing stop behavior. The model runs only once against the final selected page. Diagnostics record selection and challenge reasons without exposing private label text.

Regression fixtures represent the reported patterns, not copies of the user's missing raw report: an incomplete exact-code page followed by a richer Russo-style specification, and an ambiguous result followed by a challenge and an independently verified Aperol-style specification. They also cover misleading snippets, mismatch, unavailable fallback and conflicting sizes.
