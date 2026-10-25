# ADR-0012: Keep intake retrieval, extraction, and persistence separate

- **Status:** Accepted for the current Firecrawl source-review slice; model providers remain future work
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
- Keep model inference behind a future provider-neutral extraction boundary, separate from search and retrieval. A local runtime and user-configured cloud providers (including Ollama Cloud) require verified API/runtime contracts, credential handling, explicit settings and disclosure, source evidence, bounded structured output, and user review. No model runtime, model artifact, cloud inference provider, or contribution upload is included now.
- A language model may only produce a nullable, typed candidate tied to explicit source evidence. It receives no tools or authority to follow instructions embedded in web content. There is no silent fallback from local inference to a cloud provider.

## Consequences

- Users can look up and inspect sources without waiting for AI or giving the app a shared service credential. Offline intake remains complete.
- Each Firecrawl request consumes the user's own provider-plan quota. The app does not make background requests or use the credential for unrelated destinations.
- The current provider flow is source review only; it does not extract or save bottle details. Model inference and resumable enrichment queues must be designed and tested before claiming automated extraction.
- Firecrawl results remain device-session data and are not restored through household backups. A future persistent cache or contribution path needs a separate license and transfer review.

## References

- Firecrawl search API: <https://docs.firecrawl.dev/api-reference/endpoint/search>
- Firecrawl scrape API: <https://docs.firecrawl.dev/api-reference/endpoint/scrape>
- Android Keystore: <https://developer.android.com/privacy-and-security/keystore>
- Related decisions: [physical bottle intake](ADR-0007-physical-bottle-intake.md), [assistant provider boundary](ADR-0005-assistant-provider-boundary.md)
