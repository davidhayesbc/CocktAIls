# Optional on-device bottle detail suggestions

CocktAIls does not bundle model weights. The optional model must be selected by the user from device storage. The current importer accepts only the reviewed Qwen3 0.6B Q8_0 GGUF artifact, requires the GGUF header, limits input to 900 MiB, verifies the complete SHA-256, and atomically replaces the prior model only after verification.

## Model weights

- Model: Qwen3-0.6B Q8_0 GGUF from the [official Qwen GGUF repository](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF).
- License: Apache License 2.0, as declared by the model repository. The weights are not part of CocktAIls source or APK.
- Expected file SHA-256: `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.
- The app reports verification failure without installing the file. It does not download model data automatically.

## Native inference runtime

- [llama.cpp](https://github.com/ggml-org/llama.cpp) is pinned as a Git submodule at commit `d854d98bae598388ecea1ed84396587e5edc326d` and is distributed under MIT. The corresponding license is included in `apps/androidApp/src/main/assets/licenses/llama.cpp-MIT.txt`.
- ARM builds use KleidiAI `v1.32.0`, fetched by the pinned llama.cpp CMake configuration from its official release URL and checked against upstream's declared MD5 `05f1953428ffe8e8c7f585dc6d508887`. Its upstream license bundle is included in the app assets under `apps/androidApp/src/main/assets/licenses/` (Apache-2.0 and BSD-3-Clause notices).
- The app's JNI integration is based on llama.cpp's Android sample. Its native library and all included notices are provided separately from the Qwen model weights.

## Data and limits

The local model receives only selected page text after the app's link, URL, and control-character cleanup. It has no tools and cannot follow page instructions. The app asks for JSON but this runtime does not guarantee constrained output; candidates are parsed, source-quoted, and validated before they are shown for review. Model output never changes bottle fields automatically. Local inference is opt-in, may use substantial memory and battery, and has not been benchmarked on all supported devices. It does not perform image/OCR recognition.
