#pragma once

#include <algorithm>
#include <atomic>

inline int bounded_prompt_token_count(
    const int requested_tokens,
    const int current_position,
    const int context_size,
    const int overflow_headroom
) {
    const int available = std::max(0, context_size - overflow_headroom - current_position - 1);
    return std::min(std::max(0, requested_tokens), available);
}

class generation_budget {
public:
    void begin_request(const int max_output_tokens) {
        max_output_tokens_ = std::max(0, max_output_tokens);
        generated_tokens_ = 0;
    }

    bool has_remaining_tokens() const {
        return generated_tokens_ < max_output_tokens_;
    }

    void record_generated_token() {
        if (has_remaining_tokens()) {
            ++generated_tokens_;
        }
    }

    int generated_tokens() const {
        return generated_tokens_;
    }

private:
    int max_output_tokens_ = 0;
    int generated_tokens_ = 0;
};

/** Cross-thread cancellation flag; native inference polls it between bounded decode batches. */
class generation_cancellation {
public:
    void reset() { requested_.store(false, std::memory_order_release); }
    void request() { requested_.store(true, std::memory_order_release); }
    bool requested() const { return requested_.load(std::memory_order_acquire); }

private:
    std::atomic<bool> requested_{false};
};

inline bool generation_abort_callback(void *data) {
    return data != nullptr && static_cast<generation_cancellation *>(data)->requested();
}
