# Starter catalog source review

## Decision

The eight-record text-only IBA candidate is suitable for a separately licensed **review pack** once the listed source-history check is complete. It is **not included in the app or repository yet**. The research verifies the extractor's pinned license and each row against archived source-page content, but does not finish the page-history audit needed by the project inclusion policy.

This is a bounded hold on one candidate pack, not a reason to block manual recipes, household imports, editing, or recipe discovery.

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

## Remaining inclusion check

Review the revision history for each linked Wikipedia page and confirm the selected recipe text was contributed under terms compatible with the proposed CC BY-SA 3.0 pack. The supplied audit notes that live history/API requests were cache-missed and this review is incomplete. Until then, do not create or bundle the data pack. If that check cannot be completed, use a source with clearer record-level redistribution evidence instead.
