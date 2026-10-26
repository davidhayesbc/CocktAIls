# ADR-0012: Keep intake retrieval, extraction, and persistence separate

- **Status:** Accepted; Firecrawl retrieval and Ollama Cloud extraction are implemented, local inference remains future work
- **Date:** 2026-10-25

## Context

Large bottle collections benefit from optional source discovery, but scanning and manual entry must remain fast and offline. Search-provider credentials and source text have distinct privacy and licensing concerns. The user wants the option to add local or user-configured cloud language models later, including Ollama Cloud, without coupling inference to a particular retrieval source.

## Decision

- Keep barcode capture, label photos, draft editing, and batch confirmation independent from online requests. A scan never triggers web search.
- Use separate interfaces for web search and selected-page retrieval. Firecrawl is the first implementation; the interface does not expose Firecrawl response types to the intake model.
- Each Android user enters their own Firecrawl API key in Settings. The key is encrypted with AES-GCM and a key stored in Android Keystore, remains on that device, and is sent only in the Authorization header to the fixed Firecrawl HTTPS API host. There is no shared key, proxy, or backend.
- Search requires a separate opt-in setting and an explicit per-bottle action. The search term uses the draft's product/brand/ingredient text, not barcode, household, home, member, photo, or recipe data. Fetching page content requires the user to select a result and sends that URL.
- Bound input, result count, response bytes, displayed page text, and request timeouts. Do not follow redirects or retry automatically. Distinguish authentication, quota, rate-limit, invalid-response, and connectivity errors; never change the user's plan or enable paid top-ups.
- Show source title and URL with provider attribution. Treat page content as untrusted and do not automatically populate bottle fields, persist it into household records, or include it in household backups. Search/page results are session-only in this version.
- Keep extraction behind a provider-neutral boundary, separate from search and retrieval. Ollama Cloud is the first extraction provider, configured with the user's own model name and API key. Its key is encrypted locally with Android Keystore and sent only to `https://ollama.com/api/chat`. No shared credential or backend is used.
- Cloud extraction runs only after the user selects a fetched source page and taps the extraction action. The disclosure states that the selected page text is sent to Ollama Cloud. Requests are bounded and cancellable locally, with no automatic retry or silent fallback. A request already received by the provider may still consume quota after local cancellation.
- Ollama Cloud does not support constrained JSON output. The request asks for a compact candidate, then the client strictly parses the response and accepts only allowlisted fields whose value is supported by an exact quoted source passage. Invalid or unsupported output is rejected. Suggestions are temporary, visibly attributed to provider/model/source, and never copied into bottle fields automatically.
- Before cloud inference, remove HTML/Markdown link targets, raw URLs (including query strings), and control characters from the selected page text. This is a narrow data reduction step, not general personal-information redaction; warn users that other personal details in source text may still be sent.
- Persist only bounded enrichment-job metadata (operation, draft/session IDs, selected source URL or model, and status). Never persist search query text, source page text, model output, or credentials. On restart, in-flight jobs become paused and require explicit user action; a retried fetch may consume provider quota again.
- Keep on-device inference behind the same extraction boundary. The app does not yet include a local runtime or model download. Do not raise the app's minimum Android version to satisfy a model library; the manual and online paths remain available without a local model. Public contribution/upload is not included.
- A language model may only produce a nullable, typed candidate tied to explicit source evidence. It receives no tools or authority to follow instructions embedded in web content. There is no silent fallback from local inference to a cloud provider.

## Consequences

- Users can look up and inspect sources without waiting for AI or giving the app a shared service credential. Offline intake remains complete. They may optionally ask Ollama Cloud for evidence-backed suggestions after selecting a source.
- Each Firecrawl request consumes the user's own provider-plan quota. The app does not make background requests or use the credential for unrelated destinations.
- Extraction is a review-only suggestion flow, not an automatic field update. Cloud results depend on the user's own API plan and selected model; no live credential or provider call is part of repository testing.
- A metadata-only resumable queue is implemented. It does not save page text or model output and never restarts provider requests automatically.
- Firecrawl results remain device-session data and are not restored through household backups. A future persistent cache or contribution path needs a separate license and transfer review.

## References

- Firecrawl search API: <https://docs.firecrawl.dev/api-reference/endpoint/search>
- Firecrawl scrape API: <https://docs.firecrawl.dev/api-reference/endpoint/scrape>
- Android Keystore: <https://developer.android.com/privacy-and-security/keystore>
- Ollama chat API: <https://docs.ollama.com/api/chat>
- Ollama Cloud: <https://docs.ollama.com/cloud>
- Ollama structured outputs (cloud does not currently support constrained output): <https://docs.ollama.com/capabilities/structured-outputs>
- Related decisions: [physical bottle intake](ADR-0007-physical-bottle-intake.md), [assistant provider boundary](ADR-0005-assistant-provider-boundary.md)
