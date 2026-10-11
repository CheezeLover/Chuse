// Works out why noul answers lean negative.
//
// A column swap is the obvious suspect and is easy to disprove: swapping the candidate order
// changes the raw columns, but neither order scored better than chance across the probe set.
// The next suspect is the text the model reads for each option, since the fallback wording is
// ours rather than the model's — "yes, the statement holds" versus "no, the statement does
// not hold" are not symmetric, and the second is a longer, more emphatic phrase.
//
// This renders each variant and reports how often the answer comes out right.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../chuse_decision.h"

namespace {

struct Case {
    std::string label;
    std::string instructions;
    std::string state;
    bool expect_true;
};

std::vector<Case> cases() {
    return {
        {"sky blue / sky is blue", "Is the sky blue during the day?", "The sky is blue.", true},
        {"sky blue / sky is green", "Is the sky blue during the day?", "The sky is green.", false},
        {"2+2=4 / true", "Is two plus two equal to four?", "Two plus two equals four.", true},
        {"2+2=4 / false", "Is two plus two equal to four?", "Two plus two equals five.", false},
        {"ice melts", "Does ice melt at room temperature?", "Ice melts at room temperature.", true},
        {"ice melts / false", "Does ice melt at room temperature?", "Ice does not melt at room temperature.", false},
        {"no state", "Is the sky blue during the day?", "", true},
        {"simple true claim", "Is this statement true?", "Water boils at 100C at sea level.", true},
        {"simple false claim", "Is this statement true?", "Water boils at 10C at sea level.", false},
    };
}

// The wording variants under test.
struct Variant {
    const char* name;
    const char* true_text;
    const char* false_text;
};

const Variant VARIANTS[] = {
    // What the app currently sends: the GGUF's own fallback, as a description.
    {"current (long fallback)", "yes, the statement holds", "no, the statement does not hold"},
    // Shorter and symmetric.
    {"short symmetric", "yes", "no"},
    // Symmetric, and the phrasing a classifier is most likely to have seen.
    {"true/false keywords", "true", "false"},
    // The key alone, no description at all.
    {"bare keys", "", ""},
    // Asymmetric in the other direction, to see whether length drives it either way.
    {"long true / short false", "yes, that is definitely correct", "no"},
};

void run(chuse::DecisionModel& model, const Variant& variant, const std::vector<Case>& all) {
    int correct = 0;
    double true_mean = 0.0;
    int shown = 0;

    for (const Case& c : all) {
        std::vector<std::string> keys = {"true", "false"};
        std::vector<std::string> descriptions;
        if (variant.true_text[0] == '\0') {
            descriptions = {"", ""};
        } else {
            descriptions = {variant.true_text, variant.false_text};
        }

        std::vector<float> scores;
        std::string error;
        if (!model.score(c.instructions, c.state, chuse::QuestionType::Noul, keys, descriptions,
                         &scores, &error)) {
            continue;
        }

        const float temperature = model.temperature(chuse::QuestionType::Noul, 2);
        const float max_score = std::max(scores[0], scores[1]);
        std::vector<double> probs(2);
        double total = 0.0;
        for (size_t i = 0; i < 2; i++) {
            probs[i] = std::exp((static_cast<double>(scores[i]) - max_score) / temperature);
            total += probs[i];
        }
        for (double& p : probs) p /= total;

        const bool says_true = probs[0] >= 0.5;
        if (says_true == c.expect_true) correct++;
        true_mean += probs[0];
        shown++;
    }

    std::printf("  %-28s %d/%d correct   mean P(true) = %.3f\n", variant.name, correct, shown,
                shown > 0 ? true_mean / shown : 0.0);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: noul_wording_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const auto all = cases();
    int expect_true_count = 0;
    for (const auto& c : all) expect_true_count += c.expect_true ? 1 : 0;

    std::printf("\n%d cases, %d expected true and %d expected false.\n", static_cast<int>(all.size()),
                expect_true_count, static_cast<int>(all.size()) - expect_true_count);
    std::printf("A model with no negative bias would sit near that split.\n\n");
    std::printf("  Option wording as sent to the model:\n");
    for (const Variant& v : VARIANTS) run(*model, v, all);
    return 0;
}