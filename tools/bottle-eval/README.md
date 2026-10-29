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
responses through the production parser and identity/evidence checks. `sourceSelectionMode` can be
`barcode_search` to measure the production retrieval-plus-extraction coordinator, or
`independent_product_source` to replay extraction against a manually selected, independently
identified product page without scoring barcode retrieval. The latter requires an expected source ID
present in the frozen pages and is reported as retrieval-not-evaluated, not as a barcode match.

Every executable reviewed-product case must include the exact bounded page text used by extraction,
the source title and URL, the structured barcode/name records used for identity validation, a SHA-256
of those frozen inputs, a UTC capture time, selection method, source attribution/rights notes, and exact evidence spans for supported
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

The current split map is frozen as `product-family-holdout-2026-10-29-v1` before prompt tuning. Keep all pages, UPCs, variants, and captures from one product family in one split. In particular,
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

The versioned manifest includes the user's 18 product-level labels, selected research URLs, family
groups, and the reviewed corrections/conflicts. It does not contain source snapshots. Each case is
therefore `blocked`, and source-supported facts remain unlabeled rather than being inferred from the
inventory list. Junicopia's barcode-linked identity/name/brand/ABV remain unresolved; Giffard ABV is
unknown rather than zero. The four name-level-only UPC associations remain unverified. Categories
are intentionally not scored as taxonomy gold.

The current managed network policy is enforced and restricted. The supplied hosts `anbl.com`,
`liquormarts.ca`, `esquimaltvermouth.ca`, `giffardcanada.com`, `unitedbeverages.pl`, `saq.com`,
`sonsofvancouver.ca`, and `whiskybase.com` are not in its egress allowlist. No page requests were
attempted. There is no supported web fetch tool in this executor. No local Android device/`adb` or
bottle model is available here; the repository's small `ggml-vocab-aquila.gguf` is tokenizer data,
not the Qwen bottle model. There is therefore no Qwen inference run or Pixel timing. The Auchentoshan debug excerpt is useful for future
regressions but is not the complete captured validator input or model response, so it is not an
executable historical replay. No live search, paid provider, or cloud-model evaluation is claimed.

## Optional local Qwen GPU evaluation

`qwen3-0.6b-q8-gpu.json` records the official model artifact, expected SHA-256, pinned llama.cpp
submodule commit, and Android decoding/context settings. The model weights are not checked into
this repository. `run-local-qwen-gpu.py` never downloads or builds the model/runtime. It requires a
caller-provided GGUF whose size and SHA-256 match the manifest, and an existing CUDA-enabled
`llama-server` whose version identifies the pinned commit. It checks for a CUDA device, binds the
server only to `127.0.0.1`, requires a GPU-offload startup record, and stops the server when done.

Preflight needs only Python 3 and the case manifest:

```sh
python3 tools/bottle-eval/run-local-qwen-gpu.py --preflight
```

When a complete, source-labeled case is available and the user has separately placed the official
model and built the pinned server, run:

```sh
python3 tools/bottle-eval/run-local-qwen-gpu.py \
  --model /path/to/Qwen3-0.6B-Q8_0.gguf \
  --llama-server /path/to/pinned/llama-server
```

The default run evaluates every eligible development and held-out case together. `--case-id` can
select development cases only for prompt iteration; it rejects held-out subsets so the frozen
holdout is not accidentally used as tuning feedback.

For a later CUDA build from the already checked-out submodule (not run in this task), the pinned
source can be built with the existing local CMake/CUDA toolchain:

```sh
cmake -S third_party/llama.cpp -B third_party/llama.cpp/build-cuda \
  -DGGML_CUDA=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TESTS=OFF
cmake --build third_party/llama.cpp/build-cuda --target llama-server --config Release --parallel
```

This requires a compatible local CUDA toolkit and CMake/compiler setup. The runner does not install
those prerequisites and verifies that the resulting server identifies the pinned submodule commit.

The current manifest intentionally has zero eligible cases, so preflight reports that no model
request can run yet. The script will stop before starting the server until a reviewed product case
has a complete frozen source, independently reviewed source-supported labels, and the normal source
hash/provenance checks. Synthetic contract cases are never submitted to a model in this mode.

The opt-in path invokes the same production `LocalBottleDetailExtractor`, prompt construction,
preprocessing, parser and evidence/identity validation as the app. It sends the resulting system and
user messages to the pinned local server's legacy `/apply-template` endpoint, then completes the
rendered prompt. This mirrors Android's `use_jinja=false`; generation receives the Android sampler
order and values, 160-token cap, random seed mode, no grammar, and an 8,192-token context. A local
HTTP mock test checks the request contract. No actual Qwen host or Pixel inference was run here.

The host uses CUDA and a different GPU/driver, batch implementation and threading environment from
the Pixel's CPU backend. Reports separate server-reported generation time from host request
round-trip time and identify the host backend. They must not be interpreted as Pixel performance
measurements. The runner keeps prompts and model responses in memory only; the JSON report includes
metrics and timing metadata, not source text or generated output. Search and cloud inference remain
disabled.
