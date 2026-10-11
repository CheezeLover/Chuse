// What the derived situation does when it is used the way the app uses it.
//
// derived_state_probe.cpp measured the rule at 16/20 against 12/20 for an empty situation,
// and the docs quote that number. But its false cases hand the model a state containing the
// correct answer — "Is the Earth flat?" is scored against "The Earth is round." — which is
// something the app cannot do. QuestionToStatement.derive sees only the question, so the
// statement it produces always asserts the affirmative of what was asked: a user who types
// "Is the Earth flat?" into an empty situation field gets "The Earth is flat."
//
// That is not a rounding difference. It means the measurement was of a prompt that leaks the
// label, and the 16/20 may not survive without the leak. Worse, a derived state that asserts
// the affirmative could bias every answer towards true — trading the measured lean towards
// false for a lean towards true, which reads as confidence.
//
// So this probe reproduces what the app actually sends. Each condition is measured on the
// same balanced set of questions:
//
//   empty     - the situation field left blank, which is the 12/20 the user reports
//   derived   - the statement QuestionToStatement.derive produces, used exactly as ChatScreen
//               uses it: question in, affirmative statement out, no knowledge of the answer
//   neutral   - topical but asserting nothing, which is the best an LLM can be asked to
//               produce without simply writing down the answer it was asked to judge
//   written   - a situation that states the fact, which is the ceiling
//
// The signature to look for: if the model follows the derived state it answers true
// everywhere, giving ~10/20 with mean P(true) near 1.0 on the false cases too. If it ignores
// the state and answers from its own knowledge it should reach ~20/20, which would mean the
// empty-field answers were already right and only miscalibrated.
//
// The set is 20 questions with determinate answers, ten true and ten false, all in the
// copular or "does" forms the rule accepts. The false cases are different questions rather
// than the same question with a contradicting situation, because a question's answer is a
// fact about the world and does not depend on what the user typed alongside it.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../chuse_decision.h"

namespace {

struct Case {
    std::string instructions;
    std::string derived_state;  // exactly what QuestionToStatement.derive produces
    std::string neutral_state;  // topical but asserting nothing: the best an LLM can be asked for
    std::string written_state;  // a situation that states the fact
    bool expect_true;
};

std::vector<Case> cases() {
    return {
        // --- ten whose answer is true ---
        {"Is the sky blue during the day?", "The sky is blue during the day.",
         "The colour of the sky during the day.", "The sky is blue during the day.", true},
        {"Is water wet?", "Water is wet.", "The state of water.", "Water is wet.", true},
        {"Is the Earth round?", "The Earth is round.", "The shape of the Earth.",
         "The Earth is round.", true},
        {"Is two plus two equal to four?", "Two plus two is equal to four.",
         "The sum of two and two.", "Two plus two is equal to four.", true},
        {"Does ice melt at room temperature?", "Ice melts at room temperature.",
         "What happens to ice at room temperature.", "Ice melts at room temperature.", true},
        {"Is 100 greater than 50?", "100 is greater than 50.",
         "The relative size of 100 and 50.", "100 is greater than 50.", true},
        {"Is this sentence grammatically correct?", "This sentence is grammatically correct.",
         "The grammar of the sentence above.", "The cat sat on the mat.", true},
        {"Is the Sun a star?", "The Sun is a star.", "What the Sun is.",
         "The Sun is a star.", true},
        {"Does water freeze at 0C?", "Water freezes at 0C.", "What happens to water at 0C.",
         "Water freezes at 0C.", true},
        {"Is Python a programming language?", "Python is a programming language.",
         "What kind of thing Python is.", "Python is a programming language.", true},

        // --- ten whose answer is false. The derived statement asserts the falsehood, which
        //     is what the app really sends. The neutral one asserts nothing at all. ---
        {"Is the Earth flat?", "The Earth is flat.", "The shape of the Earth.",
         "The Earth is round.", false},
        {"Is water dry?", "Water is dry.", "The state of water.", "Water is wet.", false},
        {"Is the sky green during the day?", "The sky is green during the day.",
         "The colour of the sky during the day.", "The sky is blue during the day.", false},
        {"Is 50 greater than 100?", "50 is greater than 100.",
         "The relative size of 100 and 50.", "100 is greater than 50.", false},
        {"Does ice boil at room temperature?", "Ice boils at room temperature.",
         "What happens to ice at room temperature.", "Ice melts at room temperature.", false},
        {"Is the Sun a planet?", "The Sun is a planet.", "What the Sun is.",
         "The Sun is a star.", false},
        {"Is two plus two equal to five?", "Two plus two is equal to five.",
         "The sum of two and two.", "Two plus two is equal to four.", false},
        {"Is this sentence grammatically incorrect?", "This sentence is grammatically incorrect.",
         "The grammar of the sentence above.", "The cat sat on the mat.", false},
        {"Does water freeze at 100C?", "Water freezes at 100C.",
         "What happens to water at 100C.", "Water freezes at 0C.", false},
        {"Is four plus four equal to nine?", "4 plus 4 is equal to 9.",
         "The sum of four and four.", "4 plus 4 is equal to 8.", false},
    };
}

// Returns P(true) for one case under one condition.
double probability(chuse::DecisionModel& model, const Case& c, int condition) {
    std::string state;
    if (condition == 1) state = c.derived_state;
    else if (condition == 2) state = c.neutral_state;
    else if (condition == 3) state = c.written_state;

    std::vector<float> s;
    std::string error;
    if (!model.score(c.instructions, state, chuse::QuestionType::Noul, {"true", "false"},
                     {"true", "false"}, &s, &error)) {
        return -1.0;
    }
    const float T = model.temperature(chuse::QuestionType::Noul, 2);
    const float mx = std::max(s[0], s[1]);
    const double a = std::exp((static_cast<double>(s[0]) - mx) / T);
    const double b = std::exp((static_cast<double>(s[1]) - mx) / T);
    return a / (a + b);
}

void score(chuse::DecisionModel& model, const std::vector<Case>& all, int condition,
           const char* label) {
    int correct = 0, n_true = 0, n_false = 0;
    double sum_true = 0.0, sum_false = 0.0;

    for (const Case& c : all) {
        const double p = probability(model, c, condition);
        if (p < 0.0) continue;

        if ((p >= 0.5) == c.expect_true) correct++;
        if (c.expect_true) { sum_true += p; n_true++; }
        else { sum_false += p; n_false++; }
    }

    const double t = n_true ? sum_true / n_true : 0.0;
    const double f = n_false ? sum_false / n_false : 0.0;
    std::printf("  %-42s %2d/%2d   P(true) %.3f when true, %.3f when false   gap %+.3f\n", label,
                correct, n_true + n_false, t, f, t - f);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: derived_state_real_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const auto all = cases();
    int n_true = 0;
    for (const Case& c : all) n_true += c.expect_true ? 1 : 0;

    std::printf("\nThe derived situation as the app actually sends it, 20 questions,\n");
    std::printf("%d true and %d false, balanced.\n\n", n_true,
                static_cast<int>(all.size()) - n_true);

    std::printf("  Two of the prompts, so the leak is visible rather than argued about:\n");
    for (const Case& c : all) {
        if (c.instructions == "Is the Earth flat?" || c.instructions == "Is water wet?") {
            std::printf("    %-34s + derived \"%s\"  (correct: %s)\n", c.instructions.c_str(),
                        c.derived_state.c_str(), c.expect_true ? "true" : "false");
        }
    }
    std::printf("\n");

    score(*model, all, 0, "situation left empty");
    score(*model, all, 1, "derived from the question (the old rule)");
    score(*model, all, 2, "neutral filler, asserts nothing");
    score(*model, all, 3, "a situation stating the fact (the ceiling)");

    std::printf("\nPer case. 'ok' is the correct answer.\n\n");
    std::printf("  %-36s %-4s  empty  rule   neutral  written\n", "question", "ok");
    for (const Case& c : all) {
        const double e = probability(*model, c, 0);
        const double d = probability(*model, c, 1);
        const double n = probability(*model, c, 2);
        const double w = probability(*model, c, 3);
        const auto mark = [&](double p) {
            const bool right = (p >= 0.5) == c.expect_true;
            char buf[16];
            std::snprintf(buf, sizeof(buf), "%.2f%s", p, right ? "  " : " x");
            return std::string(buf);
        };
        std::printf("  %-36s %-4s  %s %s %s %s\n", c.instructions.c_str(),
                    c.expect_true ? "true" : "false", mark(e).c_str(), mark(d).c_str(),
                    mark(n).c_str(), mark(w).c_str());
    }

    std::printf("\nA filler that asserts nothing is the best an LLM can be asked for without\n");
    std::printf("reimporting the answer. If it scores like the empty column, a second model\n");
    std::printf("buys nothing; if it scores like the written column, Laya is no longer deciding.\n");
    return 0;
}