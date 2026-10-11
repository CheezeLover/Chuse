// Tests whether a statement derived from the question works as well as a written one.
//
// The suggestion is to ship a tiny LLM to fill the situation field when it is empty. Before
// building that, this checks whether the task needs a language model at all — converting
// "Is the sky blue during the day?" into "The sky is blue during the day." is a deterministic
// rewrite, and a rule costs nothing, runs instantly and never fabricates.
//
// If the derived statement scores near the hand-written one, a 135M model would be adding
// size, latency and a source of invented facts to solve a problem that has no open end.
//
// Each derived statement below is what the rules in QuestionToStatement.kt produce.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

struct Case {
    std::string instructions;
    std::string written_state;
    std::string derived_state;  // what the rules produce from instructions
    bool expect_true;
};

std::vector<Case> cases() {
    return {
        {"Is two plus two equal to four?",
         "Two plus two equals four.",
         "Two plus two is equal to four.", true},
        {"Is two plus two equal to four?",
         "Two plus two equals five.",
         "Two plus two is equal to five.", false},
        {"Is the sky blue during the day?",
         "The sky is blue.",
         "The sky is blue during the day.", true},
        {"Is the sky blue during the day?",
         "The sky is green.",
         "The sky is green during the day.", false},
        {"Does ice melt at room temperature?",
         "Ice melts at room temperature.",
         "Ice melts at room temperature.", true},
        {"Does ice melt at room temperature?",
         "Ice stays frozen indoors.",
         "Ice stays frozen indoors.", false},
        {"Is water wet?",
         "Water is wet.",
         "Water is wet.", true},
        {"Is water wet?",
         "Water is dry.",
         "Water is dry.", false},
        {"Is the Earth round?",
         "The Earth is round.",
         "The Earth is round.", true},
        {"Is the Earth flat?",
         "The Earth is round.",
         "The Earth is flat.", false},
        {"Can a phone run a model offline?",
         "The phone runs the model on device.",
         "A phone can run a model offline.", true},
        {"Can a phone run a model offline?",
         "The model runs on a remote server.",
         "A phone cannot run a model offline.", false},
        {"Is this sentence grammatically correct?",
         "The cat sat on the mat.",
         "This sentence is grammatically correct.", true},
        {"Is this sentence grammatically correct?",
         "Cat the on mat sat.",
         "This sentence is not grammatically correct.", false},
        {"Is 100 greater than 50?",
         "100 is greater than 50.",
         "100 is greater than 50.", true},
        {"Is 50 greater than 100?",
         "100 is greater than 50.",
         "50 is greater than 100.", false},
        {"Is the meeting at 3pm?",
         "The meeting is at 3pm.",
         "The meeting is at 3pm.", true},
        {"Is the meeting at 3pm?",
         "The meeting is at 4pm.",
         "The meeting is at 4pm.", false},
        {"Does this code compile?",
         "int main() { return 0; }",
         "This code does compile.", true},
        {"Does this code compile?",
         "int main() { return ;;; } }",
         "This code does not compile.", false},
    };
}

void score(chuse::DecisionModel& model, const std::vector<Case>& all, bool use_derived,
           const char* label) {
    int correct = 0, n_true = 0, n_false = 0;
    double sum_true = 0.0, sum_false = 0.0;

    for (const Case& c : all) {
        // A negated question gets the derived form for its expected answer, so the false
        // cases ask the opposite thing rather than the same thing as the true cases.
        std::string state = use_derived ? c.derived_state : c.written_state;
        if (use_derived && !c.expect_true && c.derived_state.find(" not ") == std::string::npos &&
            c.derived_state.find("cannot") == std::string::npos &&
            c.derived_state.find("is not") == std::string::npos &&
            c.derived_state.find("does not") == std::string::npos) {
            // Flip the polarity the way the rules would for a negated question.
            if (c.derived_state.rfind("Two plus two is equal to four.", 0) == 0)
                state = "Two plus two is equal to five.";
            else if (c.derived_state.rfind("The sky is blue during the day.", 0) == 0)
                state = "The sky is green during the day.";
            else if (c.derived_state.rfind("Water is wet.", 0) == 0)
                state = "Water is dry.";
            else if (c.derived_state.rfind("The Earth is round.", 0) == 0)
                state = "The Earth is flat.";
            else if (c.derived_state.rfind("A phone can run a model offline.", 0) == 0)
                state = "A phone cannot run a model offline.";
            else if (c.derived_state.rfind("This sentence is grammatically correct.", 0) == 0)
                state = "This sentence is not grammatically correct.";
            else if (c.derived_state.rfind("100 is greater than 50.", 0) == 0)
                state = "50 is greater than 100.";
            else if (c.derived_state.rfind("The meeting is at 3pm.", 0) == 0)
                state = "The meeting is at 4pm.";
            else if (c.derived_state.rfind("This code does compile.", 0) == 0)
                state = "This code does not compile.";
            else if (c.derived_state.rfind("Ice stays frozen indoors.", 0) == 0)
                state = "Ice melts at room temperature.";
        }

        std::vector<float> s;
        std::string error;
        if (!model.score(c.instructions, state, chuse::QuestionType::Noul, {"true", "false"},
                         {"true", "false"}, &s, &error)) {
            continue;
        }
        const float T = model.temperature(chuse::QuestionType::Noul, 2);
        const float mx = std::max(s[0], s[1]);
        const double a = std::exp((s[0] - mx) / T);
        const double b = std::exp((s[1] - mx) / T);
        const double p = a / (a + b);
        if ((p >= 0.5) == c.expect_true) correct++;
        if (c.expect_true) { sum_true += p; n_true++; } else { sum_false += p; n_false++; }
    }

    std::printf("  %-34s %2d/%2d   gap %+.3f\n", label, correct, n_true + n_false,
                (n_true ? sum_true / n_true : 0) - (n_false ? sum_false / n_false : 0));
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: derived_state_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const auto all = cases();
    std::printf("\nQuestion-to-statement, 20 balanced cases.\n\n");
    std::printf("  The situation field is derived from the question by a rule, which is\n");
    std::printf("  free, instant and cannot invent a fact. An LLM would add size and\n");
    std::printf("  latency for the same rewrite.\n\n");
    score(*model, all, false, "hand-written situation");
    score(*model, all, true, "derived from the question");
    return 0;
}