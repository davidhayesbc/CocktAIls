#include <cassert>
#include <thread>

#include "../../main/cpp/generation_budget.h"

int main() {
    generation_budget budget;
    assert(budget.finish_reason() == generation_finish_reason::unknown);

    // Prompt length is not output allowance, even when the prompt nearly fills context.
    constexpr int long_prompt_tokens = 9000;
    constexpr int system_prompt_tokens = 128;
    constexpr int context_size = 4096;
    constexpr int headroom = 128;
    constexpr int requested_output_tokens = 256;
    const int accepted_prompt_tokens = bounded_prompt_token_count(
        long_prompt_tokens, system_prompt_tokens, context_size, headroom);
    assert(accepted_prompt_tokens == context_size - headroom - system_prompt_tokens - 1);
    assert(system_prompt_tokens + accepted_prompt_tokens < context_size - headroom);

    // Even with the largest prompt that fits, output stays independently bounded.
    budget.begin_request(requested_output_tokens);
    assert(budget.finish_reason() == generation_finish_reason::running);

    int shifted_context_position = system_prompt_tokens + accepted_prompt_tokens;
    int sampled_tokens = 0;
    while (budget.has_remaining_tokens()) {
        budget.record_generated_token();
        ++sampled_tokens;
        // Simulate context compaction; it must not reset or extend this request's budget.
        shifted_context_position -= 1900;
    }

    assert(sampled_tokens == requested_output_tokens);
    assert(budget.generated_tokens() == requested_output_tokens);
    assert(shifted_context_position < 0);
    assert(!budget.has_remaining_tokens());
    budget.finish(generation_finish_reason::token_limit);
    assert(budget.finish_reason() == generation_finish_reason::token_limit);

    assert(bounded_prompt_token_count(9000, 3900, context_size, headroom) == 67);
    assert(bounded_prompt_token_count(9000, context_size, context_size, headroom) == 0);

    budget.begin_request(3);
    assert(budget.generated_tokens() == 0);
    budget.record_generated_token();
    budget.record_generated_token();
    assert(budget.generated_tokens() == 2);

    // Cancellation can be requested from the timeout thread while JNI runs on its own dispatcher.
    generation_cancellation cancellation;
    assert(!cancellation.requested());
    assert(!generation_abort_callback(&cancellation));
    std::thread timeout_thread([&cancellation] { cancellation.request(); });
    timeout_thread.join();
    assert(cancellation.requested());
    assert(generation_abort_callback(&cancellation));
    const bool may_generate = !cancellation.requested() && budget.has_remaining_tokens();
    assert(!may_generate);
    cancellation.reset();
    assert(!cancellation.requested());
    assert(!generation_abort_callback(&cancellation));
    assert(!generation_abort_callback(nullptr));

    budget.begin_request(0);
    assert(!budget.has_remaining_tokens());
    assert(budget.finish_reason() == generation_finish_reason::running);
}
