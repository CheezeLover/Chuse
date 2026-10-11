// Confirms whether Laya's noul options are ordered [false, true] or [true, false].
//
// The renderer cannot assume an order, because the order decides which column each score is
// read from. This asks the model both ways and prints the raw scores, so the ordering can be
// chosen from evidence rather than from reading a flag in someone else's source.

#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "../chuse_decision.h"

namespace {

struct Case {
    const char* label;
    std::string instructions;
    std::string state;
    bool expect_true;
};

std::vector<Case> cases() {
    return {
        {"obviously TRUE  (sky is blue)", "Is the sky blue during the day?", "The sky is blue.", true},
        {"obviously FALSE (sky is green)", "Is the sky blue during the day?", "The sky is green.", false},
        {"obviously FALSE (2+2=5)", "Is two plus two equal to five?", "Two plus two equals five.", false},
        {"obviously TRUE  (2+2=4)", "Is two plus two equal to four?", "Two plus two equals four.", true},
        {"no state given", "Is the sky blue during the day?", "", true},
        {"vague prompt", "Is this good?", "The deploy failed and rolled back cleanly.", false},
    };
}

void run(chuse::DecisionModel& model, const Case& c, bool true_first) {
    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
    if (true_first) {
        keys = {"true", "false"};
        descriptions = {"", ""};
    } else {
        keys = {"false", "true"};
        descriptions = {"", ""};
    }

    std::vector<float> scores;
    std::string error;
    if (!model.score(c.instructions, c.state, chuse::QuestionType::Noul, keys, descriptions,
                     &scores, &error)) {
        std::printf("  %-34s FAILED: %s\n", c.label, error.c_str());
        return;
    }

    // Softmax at the model's temperature, as the app does.
    const float temperature = model.temperature(chuse::QuestionType::Noul, 2);
    float max_score = scores[0];
    for (float s : scores) max_score = std::max(max_score, s);
    std::vector<double> probs(scores.size());
    double total = 0.0;
    for (size_t i = 0; i < scores.size(); i++) {
        probs[i] = std::exp((static_cast<double>(scores[i]) - max_score) / temperature);
        total += probs[i];
    }
    for (double& p : probs) p /= total;

    // Which column does the model think is "true"?
    const double p_if_true_first = probs[0];
    const double p_if_false_first = probs[1];

    int correct = 0;
    for (int order = 0; order < 2; order++) {
        const double p_true = order == 0 ? p_if_true_first : p_if_false_first;
        const bool says_true = p_true >= 0.5;
        if (says_true == c.expect_true) correct++;
    }

    std::printf("  %-34s col0=%.3f col1=%.3f | correct if [true,false]: %s | correct if [false,true]: %s\n",
                c.label, probs[0], probs[1],
                (p_if_true_first >= 0.5) == c.expect_true ? "yes" : "NO ",
                (p_if_false_first >= 0.5) == c.expect_true ? "yes" : "NO ");
    std::printf("  %-34s   scores=[%.4f, %.4f]\n", "", scores[0], scores[1]);
    (void)correct;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: noul_order_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    std::printf("\nRendering each case twice, once per candidate ordering.\n");
    std::printf("The raw columns never change — only the labels attached to them do.\n\n");
    for (const Case& c : cases()) {
        std::printf("--- %s\n", c.label);
        std::printf("    instructions: %s\n", c.instructions.c_str());
        std::printf("    state       : %s\n", c.state.empty() ? "(none)" : c.state.c_str());
        run(*model, c, true);
        run(*model, c, false);
        std::printf("\n");
    }
    return 0;
}