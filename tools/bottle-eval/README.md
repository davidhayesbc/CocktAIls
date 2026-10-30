# Bottle lookup and extraction evaluation

The evaluation uses the app's production enrichment coordinator, prompt builder, page preprocessor,
response parser, evidence checks, and product identity binding. Default tests are offline. They do not
contact Firecrawl, Ollama, a retailer, or a model. Synthetic replay cases test contracts and are not
model-quality measurements.

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
model inference, and includes no reference labels. The job index separately lists every eligible
case, no-inference case IDs with bounded outcomes (`DETERMINISTIC_SOURCE`, `RETRIEVAL_ABSTENTION`,
or `PRE_INFERENCE_REJECTION`), and the subset with actual prompt tasks. Deterministic extraction and
retrieval abstention therefore remain part of the evaluation without creating fabricated prompts.
If there are eligible cases but none requires inference, the
job file records that explicitly with an empty task list; the desktop runner exits without starting
the model. It stops without creating a job file when there are no eligible cases.

### 2. Run local inference on the Windows GPU machine

The desktop needs Python 3, the exact official Qwen GGUF, and an already available CUDA-enabled
`llama-server` built from the pinned llama.cpp commit. This runner installs and downloads nothing.
The target model is `Qwen/Qwen3-0.6B-GGUF`, `Qwen3-0.6B-Q8_0.gguf`, SHA-256
`9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`. The pinned llama.cpp commit
is `d854d98bae598388ecea1ed84396587e5edc326d` (`b11889`). The official Windows CUDA 13.4 runtime
release for this exact commit is available separately; its runtime and DLL bundle total about
588 MB, and the model is about 639 MB. Those artifacts have not been downloaded or installed.

Run from a repository checkout with the prompt-job file and locally provisioned artifacts:

```powershell
py -3 tools/bottle-eval/run-local-qwen-gpu.py `
  --jobs C:\private\bottle-prompt-jobs.json `
  --model C:\models\Qwen3-0.6B-Q8_0.gguf `
  --llama-server C:\llama.cpp\llama-server.exe `
  --results C:\private\bottle-host-results.json
```

The runner checks the model size and SHA, llama.cpp commit reported by the binary, CUDA availability,
job/config/source/task hashes, and GPU layer offload. It sends the exact exported system/user
messages to the pinned runtime's legacy `/apply-template` endpoint and completes that rendered
prompt with the Android GBNF grammar and decoding settings. The Android baseline is context 4096,
batch and micro-batch 128, temperature 0.3, seed -1, and 160 output tokens. A unit regression checks
context, batch, temperature, output cap, GBNF text, sampler order and sampler defaults against the
Android JNI source and pinned llama.cpp common defaults.

The CUDA host differs from the Pixel CPU backend in hardware, driver, GPU kernels, and threading.
Host prompt-evaluation, generation, and request timings are recorded separately and must not be
treated as Pixel timings. `timings_per_token` is requested from llama.cpp, while the app's prefill and
generation stages are separately measured on device.

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

The importer verifies that eligible cases, no-inference outcomes, and exported task IDs are the exact
sets reproduced by the current production coordinator and extractor. It evaluates all eligible
cases, including deterministic and retrieval-abstention cases, while importing host responses only
for cases that actually invoked inference. A zero-task manifest needs no host result file; run the
offline evaluation instead. The report identifies imported host inference and measured host timings.
It does not claim the Android test JVM ran the model. Keep results files private and delete them when
no longer needed.

## Cases, labels, and limitations

The versioned dataset has 18 reviewed product labels grouped by product family and a frozen
development/holdout split. All cases currently lack frozen source snapshots and field-level source
evidence, so there are zero executable real-product cases. The prompt exporter will therefore stop
with an explanatory message. No quality score or actual local Qwen run is available yet. The
Auchentoshan detailed diagnostic is a useful regression lead, but it is incomplete as a historical
source/model replay and is not promoted to a scored case.

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
