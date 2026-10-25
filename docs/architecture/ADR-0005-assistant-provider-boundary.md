# ADR-0005: Optional model providers and data minimization

- **Status:** Accepted direction; provider implementations pending validation
- **Date:** 2026-10-25

## Context

The user wants ideas from an on-device model and may use Ollama Cloud. Cloud access may cost money and transmits data. OpenAI Codex product access is not equivalent to an API credential for a third-party Android app.

## Decision

- Define a provider-neutral assistant interface; keep recipes, inventory search, and manual editing fully functional without it.
- Provider implementations are optional: on-device inference and user-configured cloud API providers. Prototype local model/runtime support on both Pixel 10 Pro and Pixel 8 before selecting a supported model.
- Initial cloud candidate is Ollama Cloud, subject to verification of endpoint, authentication, quota, privacy, and current terms. OpenAI API is a separate optional integration only for users with API access and is not authenticated through Codex login.
- Cloud credentials are device-local, stored using Android Keystore-backed facilities, never logged, exported, or written into shared domain records.
- Before each cloud request, disclose the provider and data categories sent. Send the minimum selected-location ingredients and explicit constraints by default; omit reviews and unrelated household data.
- Model output is an untrusted draft. Parse/validate its structure and references, show provenance, and require user confirmation before persistence.
- Do not bundle a model in the app. If local models are supported, offer explicit download, storage-size display, and removal.

## Consequences

- Provider choice can change without altering the recipe model or core workflow.
- BYOK avoids a developer-operated proxy but does not guarantee zero cost or eliminate key-exposure risk on a compromised phone.
- Runtime capabilities differ; structured output, tool calls, and context limits need a common minimum plus provider-specific handling.
- Cloud and local inference need separate privacy, error, and resource tests.
