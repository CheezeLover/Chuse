// Isolates where the decision signal actually comes from.
//
// offset_probe showed two opposite questions returning byte-identical embeddings, with only
// the expected winner moving. That rules out "the read offset is wrong": identical inputs to
// the same read cannot produce a moving answer. It points instead at the encode context, or at
// the prompt, not varying.
//
// This varies one thing at a time — state, instructions, option count — and prints the raw
// scores, so it is visible which changes move the numbers and which do not.

#include <cstdio>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

void row(chuse::DecisionModel& model, const char* label, const std::string& instructions,
         const std::string& state, std::vector<std::string> keys,
         std::vector<std::string> descriptions) {
    std::vector<float> scores;
    std::string error;
    if (!model.score(instructions, state, chuse::QuestionType::Noul, keys, descriptions, &scores,
                     &error)) {
        std::printf("  %-46s FAILED: %s\n", label, error.c_str());
        return;
    }
    std::printf("  %-46s", label);
    for (float s : scores) std::printf(" %9.4f", s);
    std::printf("\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: signal_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const std::vector<std::string> k = {"true", "false"};
    const std::vector<std::string> d = {"true", "false"};

    std::printf("\nA: same instructions, different state. Expect the scores to move.\n");
    row(*model, "state: 'two plus two is four'", "Is two plus two equal to four?", "Two plus two equals four.", k, d);
    row(*model, "state: 'two plus two is five'", "Is two plus two equal to four?", "Two plus two equals five.", k, d);
    row(*model, "state: 'the sky is green'", "Is two plus two equal to four?", "The sky is green.", k, d);
    row(*model, "state: (none)", "Is two plus two equal to four?", "", k, d);

    std::printf("\nB: same state, different instructions. Expect the scores to move.\n");
    row(*model, "ask about 2+2", "Is two plus two equal to four?", "A number is wrong.", k, d);
    row(*model, "ask about the sky", "Is the sky blue during the day?", "A number is wrong.", k, d);
    row(*model, "ask something unrelated", "Is this a good day?", "A number is wrong.", k, d);

    std::printf("\nC: identical everything, run twice. Expect the same numbers.\n");
    row(*model, "run 1", "Is two plus two equal to four?", "Two plus two equals four.", k, d);
    row(*model, "run 2", "Is two plus two equal to four?", "Two plus two equals four.", k, d);
    row(*model, "run 3", "Is two plus two equal to four?", "Two plus two equals four.", k, d);

    std::printf("\nD: option wording, holding everything else fixed.\n");
    row(*model, "keys+desc = true/false", "Is two plus two equal to four?", "Two plus two equals four.", {"true", "false"}, {"true", "false"});
    row(*model, "bare keys, empty desc", "Is two plus two equal to four?", "Two plus two equals four.", {"true", "false"}, {"", ""});
    row(*model, "long fallback desc", "Is two plus two equal to four?", "Two plus two equals four.", {"true", "false"},
        {"yes, the statement holds", "no, the statement does not hold"});

    std::printf("\nIf A and B do not move the scores, the encode is not seeing the prompt.\n");
    return 0;
}