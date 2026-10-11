// Laya decision logic, shared by the Android JNI bridge and the host probe.
//
// Keeping it out of the JNI layer is what makes it testable: tools/decision_probe.cpp runs
// this exact code against the real model on a workstation, so the prompt layout and the
// scores can be checked without a device. A bug fixed in the probe is a bug fixed on the
// phone too, because there is only one copy.

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace chuse {

// The question types, matching the columns of the model's embeddings output.
enum class QuestionType : int32_t {
    Choice = 0,
    Score  = 1,
    Noul   = 2,
};

/**
 * A loaded Laya model.
 *
 * Owns the llama.cpp model and context, and answers one typed question per call in a single
 * encoder pass. Construction reports failures through a message rather than throwing, so the
 * JNI and CLI wrappers can each report them in their own idiom.
 */
class DecisionModel {
public:
    ~DecisionModel();

    DecisionModel(const DecisionModel&) = delete;
    DecisionModel& operator=(const DecisionModel&) = delete;

    /** Loads [path]. Returns nullptr on failure, with the reason in [error]. */
    static std::unique_ptr<DecisionModel> load(const std::string& path, std::string* error);

    /**
     * Scores [options] for a question, returning one raw score per option in order.
     *
     * [instructions] is the question. [state] is the situation being judged; an empty string
     * is allowed, which changes how the prompt is built but not the model's contract.
     */
    bool score(
        const std::string& instructions,
        const std::string& state,
        QuestionType type,
        const std::vector<std::string>& option_keys,
        const std::vector<std::string>& option_descriptions,
        std::vector<float>* scores_out,
        std::string* error);

    /** The temperature the model calibrated for this question shape. */
    float temperature(QuestionType type, size_t option_count) const;

    /** Renders the prompt without running it, so a caller can compare against upstream. */
    std::string render_prompt(
        const std::string& instructions,
        const std::string& state,
        QuestionType type,
        const std::vector<std::string>& option_keys,
        const std::vector<std::string>& option_descriptions) const;

    /** Trims a tokenised prompt to the model's budget and reports where the markers land. */
    bool layout(
        std::vector<int32_t>* tokens,
        size_t option_count,
        std::vector<size_t>* markers_out,
        std::string* error) const;

    std::vector<int32_t> tokenize(const std::string& text) const;
    std::string detokenize(const std::vector<int32_t>& tokens) const;

    size_t max_head_tokens() const { return max_head_tokens_; }
    int32_t mask_token() const { return token_mask_; }
    int32_t sep_token() const { return token_sep_; }

private:
    DecisionModel();

    struct Impl;
    std::unique_ptr<Impl> impl_;

    size_t max_head_tokens_ = 0;
    int32_t token_mask_ = -1;
    int32_t token_sep_  = -1;
};

}  // namespace chuse