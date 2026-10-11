// Decides what to send as the state when the user leaves that field empty.
//
// Measured already: with a real state the model answers 18/20 on a balanced set, and with an
// empty one it answers 12/20 with every probability near a coin flip. That is why the field
// became mandatory — but it is not the only option. Leaving it empty sends a prompt ending in
// a bare separator, which is a degenerate shape the model was never trained to read.
//
// This compares the empty case against three fallbacks, so the choice is made on numbers:
//
//   empty      the state segment is blank            (what happens today)
//   question   the question is repeated as the state
//   generic    a fixed neutral placeholder is sent
//   question-as-instructions
//              the question becomes the instruction and the state is dropped entirely

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

struct Case {
    std::string instructions;
    std::string state;
    bool expect_true;
};

std::vector<Case> balanced_cases() {
    return {
        {"Is two plus two equal to four?", "Two plus two equals four.", true},
        {"Is two plus two equal to four?", "Two plus two equals five.", false},
        {"Is the sky blue during the day?", "The sky is blue.", true},
        {"Is the sky blue during the day?", "The sky is green.", false},
        {"Does ice melt at room temperature?", "Ice melts at room temperature.", true},
        {"Does ice melt at room temperature?", "Ice stays frozen indoors.", false},
        {"Is water wet?", "Water is wet.", true},
        {"Is water wet?", "Water is dry.", false},
        {"Is the Earth round?", "The Earth is round.", true},
        {"Is the Earth flat?", "The Earth is round.", false},
        {"Can a phone run a model offline?", "The phone runs the model on device.", true},
        {"Can a phone run a model offline?", "The model runs on a remote server.", false},
        {"Is this sentence grammatically correct?", "The cat sat on the mat.", true},
        {"Is this sentence grammatically correct?", "Cat the on mat sat.", false},
        {"Is 100 greater than 50?", "100 is greater than 50.", true},
        {"Is 50 greater than 100?", "100 is greater than 50.", false},
        {"Is the meeting at 3pm?", "The meeting is at 3pm.", true},
        {"Is the meeting at 3pm?", "The meeting is at 4pm.", false},
        {"Does this code compile?", "int main() { return 0; }", true},
        {"Does this code compile?", "int main() { return ;;; } }", false},
    };
}

template <typename Fill>
void run(chuse::DecisionModel& model, const char* label, Fill fill,
         const std::vector<Case>& cases) {
    int correct = 0, n_true = 0, n_false = 0;
    double sum_true = 0.0, sum_false = 0.0;

    for (const Case& c : cases) {
        std::vector<float> scores;
        std::string error;
        if (!model.score(c.instructions, fill(c), chuse::QuestionType::Noul, {"true", "false"},
                         {"true", "false"}, &scores, &error)) {
            continue;
        }
        const float T = model.temperature(chuse::QuestionType::Noul, 2);
        const float mx = std::max(scores[0], scores[1]);
        const double a = std::exp((scores[0] - mx) / T);
        const double b = std::exp((scores[1] - mx) / T);
        const double p = a / (a + b);
        if ((p >= 0.5) == c.expect_true) correct++;
        if (c.expect_true) { sum_true += p; n_true++; } else { sum_false += p; n_false++; }
    }

    const double mean_true = n_true ? sum_true / n_true : 0.0;
    const double mean_false = n_false ? sum_false / n_false : 0.0;
    std::printf("  %-26s %2d/%2d   mean P(true): %.3f when true, %.3f when false   gap %+.3f\n",
                label, correct, n_true + n_false, mean_true, mean_false, mean_true - mean_false);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: state_fallback_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const auto cases = balanced_cases();

    std::printf("\n20 balanced cases. With a real state the model answers 18/20.\n");
    std::printf("A usable fallback needs to be far closer to that than to chance.\n\n");

    std::printf("reference — the real state:\n");
    run(*model, "real state", [](const Case& c) { return c.state; }, cases);

    std::printf("\nwhen the field is left empty:\n");
    run(*model, "empty (today)", [](const Case&) { return std::string(); }, cases);
    run(*model, "question as state", [](const Case& c) { return c.instructions; }, cases);
    run(*model, "generic placeholder", [](const Case&) { return std::string("Not specified."); }, cases);
    run(*model, "state or question, whichever", [](const Case& c) {
        return c.state.empty() ? c.instructions : c.state;
    }, cases);

    std::printf("\nReading: a fallback that scores near 18/20 makes the field genuinely\n");
    std::printf("optional. One near 12/20 does not, and shipping it would hand the user a\n");
    std::printf("coin flip dressed as an answer.\n");
    return 0;
}