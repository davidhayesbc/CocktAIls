# ADR-0012: Keep intake retrieval, extraction, and persistence separate

- **Status:** Accepted; Firecrawl retrieval, Ollama Cloud extraction, and the optional local-model integration are implemented. Local weight verification is tested; device inference still needs physical-device validation.
- **Date:** 2026-10-25

## Context

Large bottle collections benefit from optional source discovery, but scanning and manual entry must remain fast and offline. Search-provider credentials and source text have distinct privacy and licensing concerns. The user wants the option to add local or user-configured cloud language models later, including Ollama Cloud, without coupling inference to a particular retrieval source.

## Decision

- Keep barcode capture, label photos, draft editing, and batch confirmation independent from online requests. A scan starts online lookup only when it is a user-initiated bottle scan and the user separately enabled the provider.
- Use separate interfaces for web search and selected-page retrieval. Firecrawl is the first implementation; the interface does not expose Firecrawl response types to the intake model.
- Each Android user enters their own Firecrawl API key in Settings. The key is encrypted with AES-GCM and a key stored in Android Keystore, remains on that device, and is sent only in the Authorization header to the fixed Firecrawl HTTPS API host. There is no shared key, proxy, or backend.
- Search requires a separate opt-in setting. Automatic bottle intake searches the scanned barcode; manual source search uses the draft's product/brand/ingredient text. It sends no household, home, member, photo, or recipe data. The app checks at most three distinct search-result pages per scan (one search plus up to three page fetches), stopping at the first exact barcode match or provider quota/authentication/rate-limit failure. It never increases plan limits or enables paid top-ups.
- Bound input, result count, response bytes, displayed page text, structured raw-HTML parsing, and request timeouts. Do not follow redirects or retry automatically. Preserve progress and per-candidate rejection reasons in the local debug report. A fetched page is verified by an exact barcode in its text, or by one unambiguous exact barcode in bounded JSON-LD/application-json product data whose product name matches the page title. Ambiguous structured records are rejected. For structured matches, each extracted fact must quote the verified product name and its value in the same passage; otherwise that field remains unresolved. Raw HTML is discarded after extracting the barcode/product-name pair.
- Show source title and URL with provider attribution. Treat page content as untrusted. Evidence-backed candidates may prefill the review form, but the user must accept the bottle; no provider request or result writes inventory before that confirmation.
- Keep extraction behind a provider-neutral boundary, separate from search and retrieval. Ollama Cloud is the first extraction provider, configured with the user's own model name and API key. Its key is encrypted locally with Android Keystore and sent only to `https://ollama.com/api/chat`. No shared credential or backend is used.
- Cloud extraction runs only after explicit Ollama Cloud opt-in and a page has passed barcode identity checks. The disclosure states that selected page text is sent to Ollama Cloud. For structured barcode matches, only the matched product name is supplied as extraction context; the barcode is not sent to the model. Requests are bounded and cancellable locally, with no automatic retry or silent fallback. A request already received by the provider may still consume quota after local cancellation.
- Ollama Cloud does not support constrained JSON output. The request asks for a compact candidate, then the client strictly parses the response and accepts only allowlisted fields whose value is supported by an exact quoted source passage. Invalid or unsupported output is rejected. Suggestions are visibly attributed to provider/model/source and remain review-only until the user confirms the intake batch.
- Before cloud inference, remove HTML/Markdown link targets, raw URLs (including query strings), and control characters from the selected page text. This is a narrow data reduction step, not general personal-information redaction; warn users that other personal details in source text may still be sent.
- Persist only bounded enrichment-job metadata (operation, draft/session IDs, selected source URL or model, and status). Never persist search query text, source page text, or credentials. Before confirmation, pending suggestions are stored separately from the bottle draft. On acceptance, retain per-field provider/model, source URL, fact value, and bounded evidence quote with the product; do not retain the raw page or model response. Household bundle format v2 transfers these accepted source records while excluding credentials, provider settings, and pending jobs. Repeated imports deduplicate accepted evidence.
- Keep on-device inference behind the same extraction boundary. The first runtime is llama.cpp pinned to commit `d854d98bae598388ecea1ed84396587e5edc326d`, building arm64-v8a and x86_64 without raising the app's minimum Android version. The user may select the official Qwen3 0.6B Q8_0 GGUF from device storage; the importer checks the GGUF header and pinned SHA-256, enforces a 900 MiB limit, installs atomically in app-private storage, and never downloads weights automatically. Model installation and local-AI enablement are separate opt-ins. Local prompts use selected page text only and share the same strict evidence validator as cloud suggestions. Model downloads, output, and prompts are not persisted in household records or backups. Public contribution/upload is not included.
- A language model may only produce a nullable, typed candidate tied to explicit source evidence. It receives no tools or authority to follow instructions embedded in web content. There is no silent fallback from local inference to a cloud provider.

## Consequences

- Users can look up and inspect sources without waiting for AI or giving the app a shared service credential. Offline intake remains complete. They may optionally ask Ollama Cloud for evidence-backed suggestions after selecting a source.
- Each Firecrawl search and page fetch consumes the user's own provider-plan quota. A scan may use one search and up to three page fetches; the app does not run unbounded searches, make requests when intake is not open, or use the credential for unrelated destinations.
- Extraction is a review-only suggestion flow, not an automatic field update. Cloud results depend on the user's own API plan and selected model; no live credential or provider call is part of repository testing.
- A metadata-only resumable queue is implemented. It does not save page text or model output and never restarts provider requests automatically.
- Local runtime initialization and native libraries compile for both Android ABIs. The model was not downloaded here and no real device inference has been run; performance, memory use, and output quality remain to be checked on the Pixel 10 Pro.
- Search results, raw fetched pages, model prompts/output, and unaccepted suggestions remain session/device data and are not restored through household backups. Accepted field-level product attribution is shared in bundle v2; no bulk provider dataset or public contribution is included.

## References

- Firecrawl search API: <https://docs.firecrawl.dev/api-reference/endpoint/search>
- Firecrawl scrape API: <https://docs.firecrawl.dev/api-reference/endpoint/scrape>
- Android Keystore: <https://developer.android.com/privacy-and-security/keystore>
- Ollama chat API: <https://docs.ollama.com/api/chat>
- Ollama Cloud: <https://docs.ollama.com/cloud>
- Ollama structured outputs (cloud does not currently support constrained output): <https://docs.ollama.com/capabilities/structured-outputs>
- llama.cpp Android sample/runtime: <https://github.com/ggml-org/llama.cpp/tree/d854d98bae598388ecea1ed84396587e5edc326d/examples/llama.android>
- Qwen3 0.6B GGUF source and model license: <https://huggingface.co/Qwen/Qwen3-0.6B-GGUF>
- Native and model notices: [LOCAL_AI.md](../licensing/LOCAL_AI.md)
- Related decisions: [physical bottle intake](ADR-0007-physical-bottle-intake.md), [assistant provider boundary](ADR-0005-assistant-provider-boundary.md)
