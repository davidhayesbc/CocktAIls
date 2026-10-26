cmake_minimum_required(VERSION 3.31.6)
project(cocktails_local_llm LANGUAGES C CXX)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH ARM)
    set(GGML_CPU_KLEIDIAI ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH x86)
    set(GGML_CPU_KLEIDIAI OFF)
else()
    message(FATAL_ERROR "Unsupported CocktAIls local model ABI: ${ANDROID_ABI}")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS ON CACHE BOOL "Build llama.cpp shared Android libraries" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build llama.cpp common runtime" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Do not build native tests in the app" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Do not build native CLI tools in the app" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Do not build native examples in the app" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Do not build a server in the app" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "Do not build the unified llama binary" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "The local runtime does not make network requests" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Build portable Android CPU kernels" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "Load the compatible CPU backend from the app library directory" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "Select compatible CPU kernels at runtime" FORCE)
set(GGML_CPU_KLEIDIAI ${GGML_CPU_KLEIDIAI} CACHE BOOL "Use ARM CPU optimizations where available" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Avoid an additional OpenMP runtime dependency" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Do not build the llamafile backend" FORCE)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "Pinned llama.cpp source is missing. Initialize the third_party/llama.cpp submodule.")
endif()
add_subdirectory("${LLAMA_SRC}" llama-build)

add_library(ai-chat SHARED ai_chat.cpp)
target_compile_definitions(ai-chat PRIVATE GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH})
target_include_directories(ai-chat PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
)
target_link_libraries(ai-chat PRIVATE llama llama-common android log)
