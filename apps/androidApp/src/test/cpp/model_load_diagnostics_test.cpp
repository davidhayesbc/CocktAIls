#include <cassert>
#include <string_view>

#include "../../main/cpp/model_load_diagnostics.h"

int main() {
    using namespace std::literals;

    assert(classify_native_model_load_log("error loading model: unsupported model architecture: 'future-model'"sv) ==
           NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED);
    assert(classify_native_model_load_log("error loading model: unknown model architecture: 'future-model'"sv) ==
           NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED);
    assert(classify_native_model_load_log("ggml: failed to allocate 650.00 MB"sv) ==
           NATIVE_MODEL_ALLOCATION_FAILED);
    assert(classify_native_model_load_log("std::bad_alloc"sv) == NATIVE_MODEL_ALLOCATION_FAILED);
    assert(classify_native_model_load_log("failed to load model from file"sv) == NATIVE_MODEL_LOAD_OK);
    assert(classify_native_model_load_log("model path=/private/models/user-model.gguf"sv) == NATIVE_MODEL_LOAD_OK);
}
