# Bottle lookup and extraction evaluation

The evaluation uses the app's production enrichment coordinator, prompt builder, page preprocessor,
response parser, evidence checks, and product identity binding. Default tests are offline. They do not
contact Firecrawl, Ollama, a retailer, or a model. Synthetic replay cases test contracts and are not
model-quality measurements. The current replay manifest has no model output for the seven newly
captured real-product cases; reports mark those cases unscored until actual local inference results
are imported.

## Offline test suite

From the repository root:

```sh
./gradlew :apps:androidApp:testDebugUnitTest --tests '*BottleLookupEvaluationHarnessTest'
```

The default run writes a coverage-gated summary to
`apps/androidApp/build/reports/bottle-evaluation/report.json`. It contains field names, counts,
source IDs, and measured timing metadata. It does not contain prompts, source passages, model
outputs, keys, photographs, or household metadata.

## Optional desktop GPU evaluation

Desktop inference is a separate three-stage handoff. The Android Gradle/JVM stage creates jobs from
the exact production extraction prompts. The desktop Python stage runs only local inference and
returns raw outputs with hashes. The Android Gradle/JVM importer verifies those hashes and feeds the
outputs through the same production parser and evidence/identity validation used by the app.

Prompt jobs contain public source text embedded in the model prompt, so keep the job and result files
private. The desktop runner does not receive source labels, inventory truth, bottle IDs, or answer
keys. It uses no external API, never downloads artifacts, does not start Android/Gradle, binds the
local llama.cpp server to `127.0.0.1`, and writes raw model output only to the caller-selected local
results file. Transfer that file back to the cloud workspace privately; never commit it.

### 1. Export prompts in the Android/JVM workspace

Run this only after complete, independently reviewed source captures and source-fact labels exist.
Use a private output path because it contains the exact prompt and bounded source input.

```sh
./gradlew :apps:androidApp:testDebugUnitTest \
  --tests '*BottleLookupEvaluationHarnessTest' \
  -PbottleEvalExportJobs=/private/path/bottle-prompt-jobs.json
```

The exporter uses the production coordinator and extractor, exports only prompts that actually reach
model inference, and includes no reference labels. It defaults to the `development` product split
so holdout prompts stay out of tuning runs. The job index separately lists every eligible
case, no-inference case IDs with bounded outcomes (`DETERMINISTIC_SOURCE`, `RETRIEVAL_ABSTENTION`,
or `PRE_INFERENCE_REJECTION`), and the subset with actual prompt tasks. Deterministic extraction and
retrieval abstention therefore remain part of the evaluation without creating fabricated prompts.
If there are eligible cases but none requires inference, the
job file records that explicitly with an empty task list; the desktop runner exits without starting
the model. It stops without creating a job file when there are no eligible cases.

After the model, prompt, and runtime settings are frozen, export held-out cases by adding
`-PbottleEvalExportSplit=holdout` to the same command. `full` is available for a one-time final
evaluation after configuration freeze. The importer checks the declared scope against the case
manifest. Do not use holdout results to select prompts or model settings.

To compare an archived supported prompt version with the current candidate, export each version
separately with the same case split. The baseline prompt is versioned in test resources; runtime,
model, grammar and decoding settings stay fixed:

```sh
./gradlew :apps:androidApp:testDebugUnitTest \
  --tests '*BottleLookupEvaluationHarnessTest' \
  -PbottleEvalExportSplit=holdout \
  -PbottleEvalPromptVersion=bottle-facts-v1 \
  -PbottleEvalExportJobs=/private/path/bottle-v1-holdout-jobs.json

./gradlew :apps:androidApp:testDebugUnitTest \
  --tests '*BottleLookupEvaluationHarnessTest' \
  -PbottleEvalExportSplit=holdout \
  -PbottleEvalPromptVersion=bottle-facts-v2-dev-candidate \
  -PbottleEvalExportJobs=/private/path/bottle-v2-holdout-jobs.json
```

The prompt-version contract test compares the same four previously consumed holdout cases within
one checkout: frozen sources and user prompts match, while the system prompt and prompt version
differ. This does not establish prompt equality across code revisions. In the `dc8c6eb0` versus
`bc293cd` code comparison, all four source hashes match but the Auchentoshan requested fields change
because labeled size/ABV are extracted deterministically. Verify exact task and runtime hashes before
reusing any prior output; otherwise generate a new response. These cases are regression data, not
fresh holdout validation.

### 2. Run local inference on the Windows GPU machine

The desktop needs Python 3, the exact official Qwen GGUF, and an already available CUDA-enabled
`llama-server` built from the pinned llama.cpp commit. This runner installs and downloads nothing.
The target model is `Qwen/Qwen3-0.6B-GGUF`, `Qwen3-0.6B-Q8_0.gguf`, SHA-256
`9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`. The pinned llama.cpp commit
is `d854d98bae598388ecea1ed84396587e5edc326d` (`b11889`). The official Windows CUDA 13.4 runtime
release for this exact commit is available separately; its runtime and DLL bundle total about
588 MB, and the model is about 639 MB. A separate authorized desktop setup verified the pinned
runtime/model artifacts. This runner still requires explicit local paths and never downloads or
installs them.

Run from a repository checkout with the prompt-job file and locally provisioned artifacts:

```powershell
py -3 tools/bottle-eval/run-local-qwen-gpu.py `
  --jobs C:\private\bottle-prompt-jobs.json `
  --model C:\models\Qwen3-0.6B-Q8_0.gguf `
  --llama-server C:\llama.cpp\llama-server.exe `
  --results C:\private\bottle-host-results.json
```

The runner checks the model size and SHA, llama.cpp commit reported by the binary, CUDA availability,
job/config/source/task hashes, and GPU layer offload. It sets startup verbosity to the pinned
runtime's supported level 5 because the layer-offload confirmation is otherwise omitted from the
startup log. It sends the exact exported system/user messages to the pinned runtime's legacy `/apply-template` endpoint and completes that rendered
prompt with the Android GBNF grammar and decoding settings. The Android baseline is context 4096,
batch and micro-batch 128, temperature 0.3, seed -1, and 160 output tokens. A unit regression checks
context, batch, temperature, output cap, GBNF text, sampler order and sampler defaults against the
Android JNI source and pinned llama.cpp common defaults.

The CUDA host differs from the Pixel CPU backend in hardware, driver, GPU kernels, and threading.
Host prompt-evaluation, generation, and request timings are recorded separately and must not be
treated as Pixel timings. `timings_per_token` is requested from llama.cpp, while the app's prefill and
generation stages are separately measured on device.

### Development-only prompt candidate

The current frozen candidate is `bottle-facts-v2-dev-candidate`. It keeps the same model artifact,
llama.cpp revision, GBNF, sampler order and values, context/batch sizes, temperature, and 160-token
output cap. Its prompt asks for quantity values with the source unit, exact labeled ABV evidence
(including English `Alcohol content` and French `Teneur en alcool`), and abstention on brand when a
source does not distinguish it from the product or variant title. The parser recognizes those
anchored labels while retaining exact quote and value checks. Paired holdout export supports both
`bottle-facts-v1` and the candidate version and verifies identical frozen cases and user prompts with
only the system prompt changed.

An audit of the three development excerpts found that their former brand gold values came from
product titles alone, without a distinct brand field. Those source labels are now `UNKNOWN`; inventory
brand truth remains unchanged. Recounting the original v1 responses with this corrected source-support
mask gives 9 supported facts, 5 true positives, 0 false positives and 4 false negatives (100% precision,
55.6% recall). The exact v2 development jobs ran on the pinned Qwen3-0.6B-Q8_0 artifact and llama.cpp
revision using the CUDA host runner; all three responses ended normally. Replayed through the production
parser, v2 had 9/9 supported-field true positives, 0 false positives and 0 false negatives, versus
corrected v1's 5/9 true positives and 4 false negatives. Inventory truth matched 7/12 fields for v2
versus 3/12 for v1. These are results for only three development products, not a general accuracy
claim; desktop timings are not phone timings.

All three v2 responses also proposed a brand copied from a product title. Brand source labels for these
cases are `UNKNOWN`, so those proposals are not scored as correct or incorrect. The production parser
now rejects brand claims that use the same title-only evidence passage as the product name without an
explicit brand label. Regression tests cover Banff Ice, Dubonnet Rouge, and Wild Turkey 101 title-only
claims, plus separately labeled brand evidence. This does not provide missing brand evidence or score
unknown brands as correct abstentions. The original v1 report remains preserved privately outside the
repository; raw model outputs and evaluation reports are not committed.

### 3. Import and score on the Android/JVM workspace

Copy the exact job file and matching host result file back privately. Import verifies exact job-file
SHA, config SHA, case IDs, source-input hashes, task hashes, model SHA, llama.cpp commit, backend,
finish status, and bounded response/timing values. Missing, extra, duplicated, or mismatched results
are rejected. Raw outputs are processed in memory through the production extraction pipeline and
are not copied into the summary report.

```sh
./gradlew :apps:androidApp:testDebugUnitTest \
  --tests '*BottleLookupEvaluationHarnessTest' \
  -PbottleEvalImportJobs=/private/path/bottle-prompt-jobs.json \
  -PbottleEvalImportResults=/private/path/bottle-host-results.json \
  -PbottleEvalReport=/private/path/bottle-evaluation-report.json
```

The importer verifies that scoped eligible cases, no-inference outcomes, and exported task IDs are
the exact sets reproduced by the current production coordinator and extractor. It evaluates all
eligible cases in the declared split, including deterministic and retrieval-abstention cases, while importing host responses only
for cases that actually invoked inference. A zero-task manifest needs no host result file; run the
offline evaluation instead. The report identifies imported host inference and measured host timings.
It does not claim the Android test JVM ran the model. Keep results files private and delete them when
no longer needed.

## Cases, labels, and limitations

The versioned dataset has 18 reviewed product labels grouped by product family and a frozen
development/holdout split. Seven cases now have a frozen, manually selected short product-detail
excerpt from a successful public HTTP response: three development products and four holdout
products. All seven real excerpts have now been consumed by development or evaluation; all future
runs on them are regression evidence, regardless of the retained split name. The excerpt is the complete model input fixture, not a full-page snapshot. These cases
measure extraction from that selected source text; they do not measure search, result ranking, or
full-page source selection. Eleven other cases remain without a usable frozen source input and are
not scored. Giffard's manufacturer page states a non-alcoholic grade but contains no numeric ABV and
does not show the reviewed UPC, so this page does not independently establish that barcode
association. Numeric ABV remains unknown in inventory truth.

Desktop exports default to development cases. The four captured cases assigned to `holdout` have
already been evaluated; subsequent runs using `-PbottleEvalExportSplit=holdout` are consumed-case
regressions, not fresh holdout results. Do not tune prompts or change gold using their outputs.
`full` selects both existing splits and does not make either split fresh. Both Esquimalt variants and
all Sons of Vancouver variants retain their existing family-level split assignments. The frozen
Auchentoshan excerpt is an executable regression case; its separate historical detailed diagnostic
still lacks a complete model/source replay and is not scored.

The reviewed product manifest and inventory truth are kept separate from what any supplied source
supports. Junicopia's barcode-linked identity and ABV remain unresolved; Giffard's unknown numeric
ABV is not zero; several name-level UPC associations are still unverified. Categories are not
scored as taxonomy gold. Do not add source text beyond permitted short excerpts or a suitable
license. Record URL, capture time, content hash, source selection method, rights note, and exact
evidence spans for each executable source fact.

Keep every UPC, variant, page, and capture from one family in one split. In particular, keep both
Esquimalt variants together and all Sons of Vancouver products together. Do not feed the expected
facts to the desktop model. Report retrieval coverage separately from extraction quality and
inventory truth. An 18-product pilot is not a catalog-wide accuracy estimate.

### Labeled specification fallback (development; not release validation)

The on-device extractor now recognizes a narrow plain-text specification record: its first line
must exactly match the page title, and all subsequent nonempty lines must be recognized labeled
specifications. It extracts only explicitly labeled size (`Container Size`, `Bottle size`,
`Taille du contenant`) and numeric alcohol strength (`ABV`, `Alcohol content`, `Teneur en alcool`).
It retains exact quotes, normalizes decimal separators and unit spelling, compares equivalent L/mL
quantities, and rejects conflicting, invalid, unlabeled, mixed-product, or incidental numeric claims.
For scanned bottles it additionally requires the exact barcode in that same record. Product name
and brand remain unresolved until independently supported by the existing model/identity path.
The existing web/provider workflow and cloud extractor are unchanged. This deliberately does not
parse arbitrary full pages or treat a nearby percentage as ABV. It never infers numeric zero from
“non-alcoholic”.

`LabeledSpecificationComparisonTest` runs the seven unchanged captured excerpts with the identical
empty model response on both revisions. This isolates deterministic recovery when a model abstains;
it is **not** an actual model run or an end-to-end model quality comparison. The test was run on
baseline `dc8c6eb0` in a separate worktree and on the candidate. Baseline recovered 0/13 source-supported
scalar fields; candidate recovered 6/13, with 0 scored false positives in either run. Candidate gains
are size and ABV for Banff Ice, Wild Turkey 101, and Auchentoshan. By the existing split, development
is 4/6 versus 0/6 and previously consumed holdout is 2/7 versus 0/7. This is reused regression evidence,
not a fresh holdout. Unknown labels are excluded, and source/inventory gold is unchanged. No name or
brand output is fabricated for this comparison. Per-case reports are written to
`build/reports/bottle-evaluation/deterministic-comparison.json`.

The remaining seven supported scalar fields are not recovered by this narrow fallback: Dubonnet
uses an inline UPC/size row, the two Esquimalt excerpts use unstructured inline facts and lack page
titles, and Giffard uses a different bottle-availability label with no verified UPC in the excerpt.
They continue through model extraction where configured; this test makes no claim about that model's
new outputs. Eleven of the 18 reviewed bottles still have no captured input. Source coverage,
barcode-association uncertainty, and extraction misses remain separate; no retrieval improvement is
claimed. Existing data suffices for this scoped regression, but broader release confidence requires
representative full-page inputs and a new paired model execution (the requested-field prompts have
changed), followed by fresh bottles. The earlier 4/13 versus 3/13 supported holdout-fact model result
and 2/15 inventory matches must not be replaced with this scalar-only comparison.

### Reused development outputs: name/title and brand-evidence repair

Offline scoring of the actual paired desktop outputs exposed two parser regressions: the labeled
record's size-bearing title rejected a valid shorter name, and a shortened title fragment could
escape the title-only brand check. The parser now normalizes only explicit trailing single-bottle
volumes, retains the original name for packaging/identity conflict checks, requires exact base-name
agreement when packaging normalization is used, and rejects bare/title-fragment brand evidence.
Variant, age, proof and multipack identifiers are retained. Explicit brand labels must bind the
claimed brand value. Synthetic tests cover different products, variants and bottle sizes.

Re-scoring the **same three development outputs** through this repaired local parser gives 9/9
source-supported facts, 0 scored false positives and 0 false negatives. The unmodified baseline
`dc8c6eb0` scored 8/9, 0 false positives and 1 false negative; the first labeled-record candidate
`3a19463` scored 7/9, 1 false positive and 2 false negatives. Correct size/ABV fields are 6/6 for the
repaired candidate versus 5/6 for baseline. Confirmed inventory matches are 7/12 versus baseline
6/12; the first candidate's 8/12 included an accepted brand whose source label was UNKNOWN. That
brand is now unresolved rather than credited as supported. All source and inventory labels remain
unchanged, including UNKNOWN brands.

The existing importer verified unchanged task/prompt/source/config hashes before scoring. No new
inference ran for this parser repair. Exact desktop outputs are retained only in private local
regression inputs, not committed. These are reused development observations on three selected
excerpts, not fresh holdout results, retrieval coverage, phone performance or a release gate.


### Opt-in source-line evidence experiment

`bottle-facts-v3-line-id-experiment` is an opt-in local extraction contract; the application default
remains `bottle-facts-v2-dev-candidate`. The existing prompt contains a literal placeholder quote
that appeared verbatim in rejected model outputs. The experiment instead numbers the bounded,
barcode-redacted source lines and requests a line ID in the existing `evidence` string field.
The rendered source is capped at 64 complete lines and 2,400 UTF-8 bytes, including prefixes.
Omitted lines never enter the resolver. Native inference rejects user prompts that exceed the
remaining token context rather than silently truncating them. The app resolves a valid ID to the
exact delivered source line before applying the unchanged evidence/value,
unit, conflict, product identity and brand checks. Unknown IDs, copied quote text and empty or
oversized evidence fail validation. This prevents the model from inventing accepted evidence
text; it does not prevent wrong line selection or invented values, which still require validation.
The JSON grammar and inference budget are unchanged.

Synthetic tests exercise reference validation, literal values/units, unrelated percentages,
wrong-product passages, mixed bottle sizes, explicit brand labels and unsupported product/site-title
fragments. V3 enforces explicit Brand/Brand name labels; non-title prose such as an unlabelled
producer mention is not sufficient in this experiment. A valid line ID establishes location only,
not factuality or trustworthiness of source text, and does not establish prompt-injection immunity.
Structured metadata currently retains barcode/name pairs only; it does not establish brand
provenance. Site names and title suffixes remain insufficient brand evidence. Do not relax this
rule or change reviewed gold to improve the experiment's score.

Export paired jobs with the default version and `-PbottleEvalPromptVersion=bottle-facts-v3-line-id-experiment`.
Use the exported configuration for each variant. Every task/source/config hash must match any reused
result; default-version responses cannot stand in for the new line-ID requests. No model-quality
improvement is established by the synthetic parser tests. All seven real excerpts remain consumed
regression data, and this experiment is not a release gate.


For a controlled desktop model-size comparison, freeze the same line-ID tasks for both models.
`-PbottleEvalHostModelDescriptor=/private/path/model.json` accepts only the verified model descriptor
fields (`repository`, `filename`, `bytes`, `sha256`, `download`) and updates its reporting label.
It cannot override the template, grammar, sampling, token budget, runtime or Android app default.
Use this same descriptor for export and offline import. The descriptor supports official Qwen3
Q8_0 GGUF artifacts; the runner verifies the actual file size and SHA before inference. Never invent
artifact metadata. Different model configs/job-file hashes are expected, but every task and source
hash must remain identical. The runner accepts `--config /private/path/exported-config.json` so each
job can use its matching frozen config without editing the pinned Android baseline file. Report
model identity accurately and keep desktop memory/latency separate from device measurements.
