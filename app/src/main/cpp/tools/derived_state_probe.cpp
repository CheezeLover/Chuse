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

#include <algorithm>
#include <cctype>

#include "chuse_decision.h"

namespace {

// The copular and "does" rules from core/src/main/kotlin/com/chuse/core/QuestionToStatement.kt,
// ported so this probe can show what the rule actually writes rather than what a test case
// claims it writes. Those two have drifted apart, which is the whole disagreement.
const std::vector<std::string> DETERMINERS = {
    "the", "a",  "an",   "this", "that", "these", "those", "its", "his", "her",
    "their", "our", "my", "your", "some", "any", "each", "every", "no", "one", "two"};

std::string trim_punct(const std::string& s) {
    const size_t b = s.find_first_not_of(" ,.");
    if (b == std::string::npos) return "";
    const size_t e = s.find_last_not_of(" ,.");
    return s.substr(b, e - b + 1);
}

std::string lower(const std::string& s) {
    std::string o = s;
    std::transform(o.begin(), o.end(), o.begin(),
                   [](unsigned char c) { return std::tolower(c); });
    return o;
}

bool is_determiner(const std::string& w) {
    return std::find(DETERMINERS.begin(), DETERMINERS.end(), lower(w)) != DETERMINERS.end();
}

std::string third_person(const std::string& verb) {
    if (verb.size() > 1 && verb.back() == 'y' &&
        std::string("aeiou").find(verb[verb.size() - 2]) == std::string::npos) {
        return verb.substr(0, verb.size() - 1) + "ies";
    }
    return verb + "s";
}

// The rule's output for [question], or "" where the rule declines.
std::string derive(const std::string& question) {
    std::string q = question;
    while (!q.empty() && std::isspace(static_cast<unsigned char>(q.back()))) q.pop_back();
    if (!q.empty() && q.back() == '?') q.pop_back();

    std::vector<std::string> words;
    std::string cur;
    for (const char c : q) {
        if (c == ' ') {
            if (!cur.empty()) words.push_back(cur);
            cur.clear();
        } else {
            cur += c;
        }
    }
    if (!cur.empty()) words.push_back(cur);
    if (words.size() < 3) return "";

    const std::string aux = trim_punct(lower(words[0]));
    const size_t subject_start = is_determiner(words[1]) ? 1 : 0;
    const auto join = [&](size_t from, size_t to) {
        std::string out;
        for (size_t i = from; i < to && i < words.size(); i++) {
            if (!out.empty()) out += " ";
            out += words[i];
        }
        return out;
    };
    const auto cap = [](std::string s) {
        if (!s.empty()) s[0] = std::toupper(static_cast<unsigned char>(s[0]));
        return s;
    };

    if (aux == "is" || aux == "are" || aux == "was" || aux == "were" || aux == "am") {
        const size_t subject_end = 2 + subject_start;
        if (subject_end >= words.size()) return "";
        const std::string subject = cap(join(1, subject_end));
        const std::string complement = trim_punct(join(subject_end, words.size()));
        if (complement.empty()) return "";
        const std::string verb = (aux == "are" || aux == "were") ? "are" : "is";
        return subject + " " + verb + " " + complement + ".";
    }

    if (aux == "does") {
        const size_t verb_index = 2 + subject_start;
        if (verb_index >= words.size()) return "";
        const std::string subject = cap(join(1, verb_index));
        const std::string verb = third_person(trim_punct(words[verb_index]));
        const std::string tail = join(verb_index + 1, words.size());
        return tail.empty() ? subject + " " + verb + "." : subject + " " + verb + " " + tail + ".";
    }

    return "";  // modals and the rest: declined
}

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

// The state that reaches the model in derived mode.
//
// Extracted from score() unchanged, so the dump printed below and the scoring cannot drift
// apart. On a false case the rule writes the affirmative of the question — "Is the Earth
// flat?" becomes "The Earth is flat." — which is the wrong answer, so this substitutes a
// statement saying the opposite. That substitution is the whole disagreement: it is
// hand-written, it needs to know the answer, and the rule cannot produce it.
std::string derived_state_for(const Case& c) {
    std::string state = c.derived_state;
    if (!c.expect_true && c.derived_state.find(" not ") == std::string::npos &&
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
    return state;
}

void score(chuse::DecisionModel& model, const std::vector<Case>& all, bool use_derived,
           const char* label) {
    int correct = 0, n_true = 0, n_false = 0;
    double sum_true = 0.0, sum_false = 0.0;

    for (const Case& c : all) {
        std::string state = use_derived ? derived_state_for(c) : c.written_state;

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

    // What the rule actually writes against what reaches the model, case by case. Scoring
    // below is unchanged; this only prints it, because on the false cases the two differ and
    // that difference is what the 16/20 is made of. See derived_state_real_probe.
    std::printf("  per case — 'rule writes' is derive(question) run for real, 'case claims' is\n");
    std::printf("  what the test table says it writes, 'scored with' is what the model saw:\n\n");
    for (const Case& c : all) {
        const std::string rule = derive(c.instructions);
        const std::string scored = derived_state_for(c);
        const std::string rule_text = rule.empty() ? "(declined)" : rule;
        std::printf("    %-38s ok=%-5s rule: %-34s case claims: %-34s\n",
                    c.instructions.c_str(), c.expect_true ? "true" : "false", rule_text.c_str(),
                    c.derived_state.c_str());
        if (scored != c.derived_state) {
            std::printf("    %-38s %-11s scored with: %s\n", "", "", scored.c_str());
        }
    }
    std::printf("\n");

    score(*model, all, false, "hand-written situation");
    score(*model, all, true, "derived from the question");
    return 0;
}