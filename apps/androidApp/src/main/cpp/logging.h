#pragma once

// Prompts, source text, model paths, and generated suggestions must never be
// copied to logcat. llama.cpp's native logging callback is intentionally quiet.
#define LOGv(...) ((void)0)
#define LOGd(...) ((void)0)
#define LOGi(...) ((void)0)
#define LOGw(...) ((void)0)
#define LOGe(...) ((void)0)

static void aichat_android_log_callback(enum ggml_log_level, const char *, void *);
