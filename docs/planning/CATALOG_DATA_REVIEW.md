# Starter catalog source review

## Decision

The bounded eight-record text-only starter pack is included in `catalog-packs/iba-wikipedia-starter/`. It contains only the factual ingredient/amount fields listed below, concise methods newly authored by CocktAIls, and required per-record provenance. This is a practical, scoped review, not a blanket legal-clearance claim. The pack is separately licensed CC BY-SA 3.0; app code remains under its own license.

The review supports factual ingredient lists and measures with newly authored concise methods. The included pack avoids images, copied explanatory prose, and verbatim methods whose authorship/redistribution scope is uncertain.

## Evidence already reviewed

- Extracted source: [`iba-cocktails-wiki.csv` at commit `9148d3302f582b06695684f3bb446631ab99d160`](https://github.com/rasmusab/iba-cocktails/blob/9148d3302f582b06695684f3bb446631ab99d160/wikipedia/iba-cocktails-wiki.csv).
- The same pinned repository's `LICENSE` explicitly applies CC BY-SA 3.0 to the CSV and archived Wikipedia HTML; its code is separately MIT licensed.
- The research matched each selected CSV row to its archived Wikipedia infobox. Measures were retained as supplied; no image or `iba-web/` material is used. Concise methods authored by CocktAIls would be marked as modifications.
- Required pack attribution, if later included: Wikipedia article contributors and Rasmus Bååth for extraction; CC BY-SA 3.0 notice; source and history links; pinned repository commit; a record of normalization and other changes. The data pack must remain separate from app code and household records, and exports must carry its notices.
- The reviewed archived Wikimedia terms at [oldid 98524](https://foundation.wikimedia.org/w/index.php?oldid=98524&title=Policy%3ATerms_of_Use) describe contributor attribution by article link. This is provenance evidence, not a substitute for reviewing whether source text can be redistributed.

## Eight candidate records

| CSV label | Wikipedia page revision | Archived page |
|---|---|---|
| Daiquiri | [Daiquiri, oldid 1137204541](https://en.wikipedia.org/w/index.php?title=Daiquiri&oldid=1137204541) | `Daiquiri.html` |
| Manhattan | [Manhattan (cocktail), oldid 1138697614](https://en.wikipedia.org/w/index.php?title=Manhattan_(cocktail)&oldid=1138697614) | `Manhattan_cocktail.html` |
| Margarita | [Margarita, oldid 1141361671](https://en.wikipedia.org/w/index.php?title=Margarita&oldid=1141361671) | `Margarita.html` |
| Dry martini | [Martini (cocktail), oldid 1143040738](https://en.wikipedia.org/w/index.php?title=Martini_(cocktail)&oldid=1143040738) | `Martini_cocktail.html` |
| Negroni | [Negroni, oldid 1140297831](https://en.wikipedia.org/w/index.php?title=Negroni&oldid=1140297831) | `Negroni.html` |
| Old fashioned | [Old fashioned (cocktail), oldid 1137422023](https://en.wikipedia.org/w/index.php?title=Old_fashioned_(cocktail)&oldid=1137422023) | `Old_fashioned_cocktail.html` |
| Sidecar | [Sidecar (cocktail), oldid 1138247115](https://en.wikipedia.org/w/index.php?title=Sidecar_(cocktail)&oldid=1138247115) | `Sidecar_cocktail.html` |
| Whiskey sour | [Whiskey sour, oldid 1135216366](https://en.wikipedia.org/w/index.php?title=Whiskey_sour&oldid=1135216366) | `Whiskey_sour.html` |

The candidate is only these eight CSV rows. Do not add other IBA records, images, or scraped site material under this review.

## Revision-history findings

The public histories for all eight pinned revisions were reviewed through their oldest visible entries (20 history pages total), along with current talk pages and the Martini archive. The review found no additional restrictive notice affecting the factual ingredient and amount fields. Specific context:

- Old fashioned revision 108862767 (2007) says the ingredients/directions in the infobox came from the IBA; this does not identify a separate IBA license for that text.
- Negroni revision 108835279 removes material about the drink's origin; the reviewed change concerns biography/history prose, not the selected infobox fields.
- Martini retains its infobox after the public post-removal revision 920158546. The earlier suppressed diff was not retrieved.
- Whiskey sour revision 286304679 records a split pointing to the Wikipedia “Sour (cocktail)” ancestry.
- The archived 2023 page footer states CC BY-SA 3.0. The current footer's later 4.0 notice does not replace the archived notice for these pinned revisions.

The distributed pack retains only the eight factual ingredient/amount rows; methods are newly written; article revision links, history links, contributor/extractor attribution, pinned repository commit, CC BY-SA 3.0 notice, and normalization/modification notes are included. No images or verbatim explanatory/history text are bundled. The residual Martini suppressed-diff uncertainty and the general limits of a bounded public-source review mean this is not a guarantee about every jurisdiction or rights claim. Do not broaden the reviewed subset under this result; use a source with clearer record-level redistribution evidence for additions.
