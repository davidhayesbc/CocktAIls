# Bottle lookup and extraction evaluation

This is an offline replay harness around the app's production `ScannedBottleEnrichmentCoordinator`
and `LocalBottleDetailExtractor`. It separates search/source selection, facts stated by the supplied
source, and confirmed inventory values. It does not call Firecrawl, Ollama, or another network
service. The checked-in response variants are synthetic fixtures; they test orchestration, evidence
validation, abstention, malformed output, and report generation, not model quality.

## Run

From the repository root:

```sh
./gradlew :apps:androidApp:testDebugUnitTest --tests '*BottleLookupEvaluationHarnessTest'
```

The test writes `apps/androidApp/build/reports/bottle-evaluation/report.json`. To use alternate
versioned case and replay manifests without editing the test resources:

```sh
./gradlew :apps:androidApp:testDebugUnitTest \
  --tests '*BottleLookupEvaluationHarnessTest' \
  -PbottleEvalCases=/absolute/path/cases.json \
  -PbottleEvalRuns=/absolute/path/replay-runs.json \
  -PbottleEvalReport=/absolute/path/report.json
```

The report contains aggregate counts, stage outcomes, field names, and source IDs only. It does not
copy model prompts, model responses, page text, fact values, credentials, photos, or household data.
Keep detailed capture files private; only add excerpts to the repository when the source rights and
scope permit it.

## Case and run manifests

The versioned examples are in `apps/androidApp/src/test/resources/bottle-evaluation/v1/`.
`cases.json` stores a case's family group and split, barcode, search hits, supplied page text and
structured identity, source-supported field labels, and separately labeled inventory truth.
`replay-runs.json` identifies the model/runtime/quantization/prompt/decoding settings and maps case
IDs to response replays. `outputSource` must identify synthetic fixtures or a sanitized captured
response replay. Original-run timing is reported only when the manifest says `device_observed` and
contains an observed generation duration; fixture timings remain `unavailable`. Replaying an output
does not execute inference or measure generation latency. The report says this explicitly.

Prompt variants are passed through the same production extractor with a bounded configuration. The
production default remains prompt version `bottle-facts-v1` with 160 output tokens. Evaluation
variants may use a different prompt or a bounded output limit up to 512, but changing that limit is
a different latency configuration and must be recorded as such. A replayed output is not a new
model run. The current harness does not load a GGUF itself; it evaluates captured or synthetic model
responses through the production parser and identity/evidence checks.

Every executable reviewed-product case must include the exact bounded page text used by extraction,
the source title and URL, the structured barcode/name records used for identity validation, a SHA-256
of those frozen inputs, source attribution/rights notes, and exact evidence spans for supported
facts. The hash is over length-prefixed title, source URL, provider label, markdown, and structured
identity records in supplied order. `complete` means the supplied inputs are sufficient to make claims about fields absent from
the source; excerpts must remain `excerpt`/`blocked`. Do not mark an unknown value as zero or absent.
An absent field is a valid label only for a complete source. Conflicts and unresolved inventory
values are not forced into a gold answer.

Inventory values and source facts deliberately use different labels. For example, an inventory
review can confirm a bottle's size while an incomplete source excerpt may not mention size. That is
not extraction recall failure. Conversely, a value can be in a source but not yet confirmed in the
household inventory.

## Splits and reporting

Keep all pages, UPCs, variants, and captures from one product family in one split. In particular,
do not put different Esquimalt products or two Sons of Vancouver products on opposite sides of a
prompt-tuning split. Report development and held-out results separately. A quality claim needs
executable reviewed-product cases in both splits; synthetic cases never satisfy that gate. The
harness reports split metrics separately and keeps its aggregate ineligible until both are present.
The reviewed 18-bottle pilot is not a broad estimate of catalog-wide accuracy.

Useful measures include source-supported field precision/recall, correct abstention on absent or
conflicting fields, wrong-product acceptance, search hit presence/verified selection, malformed or
token-limited replies, and genuinely measured generation timing. Inventory-truth agreement is kept
as a separate report section. Compare like-for-like model build, quantization, runtime, prompt
version, temperature, token cap, and fixture version.

## Current limitations

The reviewed 18-bottle JSON/provenance/notes have not been materialized into this executor. The
available files include only synthetic contract cases. The reported retailer URLs and fact summaries
are not frozen source snapshots, so they cannot support a real extraction benchmark. The reviewed
set's unresolved barcode/source associations and Junicopia ABV conflict must remain masked. The
Auchentoshan debug report identified a useful failure but the user-provided excerpt is not the full
captured validator input or complete model response, so it is not yet an executable historical replay.
No local Qwen run, Pixel measurement, live search, or cloud-model evaluation is claimed by this suite.
