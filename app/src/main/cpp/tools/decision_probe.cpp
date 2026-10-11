// Runs the real Laya model on a workstation, outside Android.
//
// This exists because the interesting questions about a decision model cannot be answered
// from Kotlin unit tests: does the rendered prompt produce the scores we expect, and does a
// question with no separate "state" still work? Both need the actual weights, and both are
// testable here in seconds rather than after a device build and install.
//
// It calls the same chuse_decision.cpp the app uses, so a difference found here is a
// difference on the phone.
//
//   decision_probe <model.gguf> [--prompt-only] [--state-empty]
//
// With --state-empty the state segment is omitted entirely, which is what a one-field UI
// would send.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../chuse_decision.h"

namespace {

struct Case {
    const char* label;
    chuse::QuestionType type;
    std::string instructions;
    std::string state;
    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
};

std::vector<Case> cases() {
    return {
        {"yes/no: obviously true", chuse::QuestionType::Noul,
         "Is the sky blue during the day?", "The sky is blue.",
         {"true", "false"}, {"", ""}},

        {"yes/no: obviously false", chuse::QuestionType::Noul,
         "Is the sky blue during the day?", "The sky is green.",
         {"true", "false"}, {"", ""}},

        {"yes/no: no state given", chuse::QuestionType::Noul,
         "Is the sky blue during the day?", "",
         {"true", "false"}, {"", ""}},

        {"yes/no: ambiguous", chuse::QuestionType::Noul,
         "Is this claim true?", "The invoice total matches the purchase order.",
         {"true", "false"}, {"", ""}},

        {"choice: clear winner", chuse::QuestionType::Choice,
         "Which is the best next step?", "The laptop will not boot after an update.",
         {"restore", "reinstall", "buy"}, {"", "", ""}},

        {"choice: no state given", chuse::QuestionType::Choice,
         "Which is the best next step?", "",
         {"restore", "reinstall", "buy"}, {"", "", ""}},

        {"score: severe", chuse::QuestionType::Score,
         "How severe is this?", "Production database deleted.",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}},

        {"score: trivial", chuse::QuestionType::Score,
         "How severe is this?", "A typo in a comment.",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}},

        {"score: no state given", chuse::QuestionType::Score,
         "How severe is this?", "",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}},
    };
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: decision_probe <model.gguf> [--prompt-only] [--state-empty]\n");
        return 2;
    }

    const bool prompt_only = std::strstr(argv[1], "--prompt-only") != nullptr ||
                             (argc > 2 && std::strcmp(argv[2], "--prompt-only") == 0);
    const bool state_empty = argc > 2 && std::strcmp(argv[2], "--state-empty") == 0;

    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    std::printf("model loaded: max_head_tokens=%zu mask=%d sep=%d\n",
                model->max_head_tokens(), model->mask_token(), model->sep_token());
    if (state_empty) std::printf("(forcing state empty for every case)\n");
    std::printf("\n");

    for (const Case& c : cases()) {
        Case test = c;
        if (state_empty) test.state.clear();

        const std::string prompt =
            model->render_prompt(test.instructions, test.state, test.type, test.keys, test.descriptions);

        std::printf("=== %s\n", test.label);
        std::printf("    prompt: %s\n", prompt.c_str());

        if (prompt_only) {
            std::printf("\n");
            continue;
        }

        const float temperature = model->temperature(test.type, test.keys.size());
        std::vector<float> scores;
        if (!model->score(test.instructions, test.state, test.type, test.keys, test.descriptions,
                          &scores, &error)) {
            std::printf("    FAILED: %s\n\n", error.c_str());
            continue;
        }

        // Softmax at the model's temperature, matching what the app does.
        float max_score = scores[0];
        for (float s : scores) max_score = std::max(max_score, s);
        std::vector<double> probs(scores.size());
        double total = 0.0;
        for (size_t i = 0; i < scores.size(); i++) {
            probs[i] = std::exp((static_cast<double>(scores[i]) - max_score) / temperature);
            total += probs[i];
        }
        for (double& p : probs) p /= total;

        std::printf("    temperature=%.4f  scores=[", temperature);
        for (size_t i = 0; i < scores.size(); i++) {
            std::printf("%s%.4f", i ? ", " : "", scores[i]);
        }
        std::printf("]\n    probs   =[");
        for (size_t i = 0; i < probs.size(); i++) {
            std::printf("%s%.4f", i ? ", " : "", probs[i]);
        }
        std::printf("]\n\n");
    }
    return 0;
}