// Measures yes/no bias on a balanced set, with the wording variants.
//
// Two earlier probes disagreed about the bias, and the disagreement was mine: offset_probe
// reported byte-identical embeddings for different questions, which should have been
// impossible. signal_probe then showed the scores do move correctly with state and
// instructions and are deterministic run to run, so the earlier read was the artefact.
//
// This settles it with a balanced set — as many expected-true as expected-false — and reports
// both accuracy and the mean probability assigned to each side. A model that is merely
// uncertain scores near 0.5 on both; a model that leans false scores lower on the true cases.
//
// It also compares the wordings, since a real asymmetry in the option text would show up here
// as a gap between variants.

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
        {"Is the statement 'the meeting is at 3pm' true?", "The meeting is at 3pm.", true},
        {"Is the statement 'the meeting is at 3pm' true?", "The meeting is at 4pm.", false},
        {"Does this code compile?", "int main() { return 0; }", true},
        {"Does this code compile?", "int main() { return ;;; } }", false},
    };
}

struct Variant {
    const char* name;
    const char* true_desc;
    const char* false_desc;
};

void run(chuse::DecisionModel& model, const Variant& v,
         const std::vector<Case>& cases, bool with_state) {
    int correct = 0;
    int on_true_cases = 0;
    int on_false_cases = 0;
    double sum_true = 0.0;   // mean P(true) where the answer should be true
    double sum_false = 0.0;  // mean P(true) where the answer should be false
    int n_true = 0, n_false = 0;

    for (const Case& c : cases) {
        std::vector<std::string> descs = {v.true_desc, v.false_desc};
        std::vector<float> scores;
        std::string error;
        const std::string state = with_state ? c.state : std::string();
        if (!model.score(c.instructions, state, chuse::QuestionType::Noul, {"true", "false"},
                         descs, &scores, &error)) {
            continue;
        }

        const float T = model.temperature(chuse::QuestionType::Noul, 2);
        const float mx = std::max(scores[0], scores[1]);
        const double a = std::exp((scores[0] - mx) / T);
        const double b = std::exp((scores[1] - mx) / T);
        const double p_true = a / (a + b);

        if ((p_true >= 0.5) == c.expect_true) correct++;
        if (c.expect_true) { sum_true += p_true; n_true++; }
        else { sum_false += p_true; n_false++; }
    }

    const int n = n_true + n_false;
    std::printf("  %-24s %2d/%2d  mean P(true): %.3f when true, %.3f when false   gap %+.3f\n",
                v.name, correct, n,
                n_true ? sum_true / n_true : 0.0,
                n_false ? sum_false / n_false : 0.0,
                (n_true ? sum_true / n_true : 0.0) - (n_false ? sum_false / n_false : 0.0));
    (void)on_true_cases;
    (void)on_false_cases;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: noul_bias_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const auto cases = balanced_cases();
    int n_true = 0;
    for (const auto& c : cases) n_true += c.expect_true ? 1 : 0;

    const Variant variants[] = {
        {"bare keys", "", ""},
        {"true/false desc", "true", "false"},
        {"yes/no desc", "yes", "no"},
        {"GGUF long fallback", "yes, the statement holds", "no, the statement does not hold"},
    };

    std::printf("\n%d cases, %d expected true, %d expected false, balanced.\n",
                static_cast<int>(cases.size()), n_true, static_cast<int>(cases.size()) - n_true);
    std::printf("An unbiased model puts a high mean on true cases and a low one on false,\n");
    std::printf("with a gap near 1.0. A negative tilt shows as a gap below that.\n\n");

    std::printf("with a state:\n");
    for (const Variant& v : variants) run(*model, v, cases, true);
    std::printf("\nwithout a state (question only):\n");
    for (const Variant& v : variants) run(*model, v, cases, false);
    return 0;
}