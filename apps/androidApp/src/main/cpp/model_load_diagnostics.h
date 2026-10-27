#pragma once

#include <string_view>

enum NativeModelLoadStatus {
    NATIVE_MODEL_LOAD_OK = 0,
    NATIVE_MODEL_LOAD_FAILED = 1,
    NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED = 2,
    NATIVE_MODEL_ALLOCATION_FAILED = 3,
};

// Return only a fixed status. Native messages, paths, tensor names, and metadata
// are never retained or copied into app diagnostics.
inline int classify_native_model_load_log(std::string_view message) {
    if (message.find("unsupported model architecture:") != std::string_view::npos ||
        message.find("unknown model architecture:") != std::string_view::npos) {
        return NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED;
    }
    if (message.find("bad_alloc") != std::string_view::npos ||
        message.find("failed to allocate") != std::string_view::npos ||
        message.find("out of memory") != std::string_view::npos ||
        message.find("cannot allocate memory") != std::string_view::npos) {
        return NATIVE_MODEL_ALLOCATION_FAILED;
    }
    return NATIVE_MODEL_LOAD_OK;
}
