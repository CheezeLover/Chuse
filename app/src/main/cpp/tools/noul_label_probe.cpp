// Does the *label* of a noul option decide the answer, independent of the state?
//
// Upstream issue #156 measured this on the PyTorch checkpoints: when the two noul options
// are labelled with boolean words — `true:`/`false:` or `yes:`/`no:` — the model picks the
// NEGATIVE label in both polarities, at confidence 1.0000. Neutral labels (`A`/`B`,
// `positive`/`negative`, `1`/`2`) answered correctly on the same states.
//
// If that holds here it is the most likely explanation for the reported lean towards false,
// and it would be a prompting defect rather than a property of the checkpoint.
//
// Every probe before this one missed it, for a structural reason worth recording:
// noul_bias_probe and noul_wording_probe both pass the keys {"true","false"} and vary only
// the *descriptions*. So even their "bare keys" variant renders "true: true" / "false: false"
// — the boolean words are in the label slot in every variant they ran. The label slot was
// never varied, which is the one thing the upstream finding is about.
//
// So this probe varies the labels and holds everything else fixed: same balanced set, same
// states, same question order. Two case sets are used, because they may be different regimes:
//
//   * the twenty balanced factual cases from noul_bias_probe, so the numbers are directly
//     comparable with the 18/20-with-a-state and 12/20-without figures already in the docs;
//   * the sentiment-style case from #156, which is the shape the upstream report used and is
//     the shape an app user is far more likely to type.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../chuse_decision.h"

namespace {

struct Case {
    std::string instructions;
    std::string state;
    bool expect_true;
};

// Case 20 "Does this code compile?" with the broken fragment is dropped relative to
// noul_bias_probe: a state that does not parse as C++ is a different question about the
// model's competence than a label experiment. The set is otherwise identical.
std::vector<Case> factual_cases() {
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
    };
}

// The shape the app actually ships: a judgement about a situation, not a crisp fact. The
// yes/no examples in app/src/main/res/values/strings.xml are all of this shape ("Is it safe
// to delete the backup first?"), so this is the regime a user hits by default, and it is the
// regime upstream issue #156 reported failing.
//
// The first three are shipped examples verbatim. The rest follow the same pattern — a
// decision the reader is being asked to make, and a state that settles it — balanced
// 12 true / 12 false so the mean on each side is comparable.
std::vector<Case> judgement_cases() {
    return {
        // Shipped examples, unchanged.
        {"Is it safe to delete the backup first?",
         "A verified backup exists and the migration has been tested.", true},
        {"Can a phone run a model entirely offline?",
         "The model runs on the phone itself, with no server.", true},
        {"Is the sky blue during the day?", "The sky is blue.", true},

        {"Is it safe to delete the backup first?", "No backup has ever been taken.", false},
        {"Can a phone run a model entirely offline?",
         "The model runs on a remote server the phone must reach.", false},
        {"Should we refund this customer?", "They were billed twice for March.", true},
        {"Should we refund this customer?", "They were billed once, correctly.", false},
        {"Is this a security incident?", "Credentials were pushed to a public repository.", true},
        {"Is this a security incident?", "An internal document with no secrets in it.", false},
        {"Will this customer churn?", "They said they are cancelling and want a refund.", true},
        {"Will this customer churn?", "They said thanks, that resolved it.", false},
        {"Should we roll back the release?", "Crash rate tripled after the deploy.", true},
        {"Should we roll back the release?", "The deploy is green and errors fell.", false},
        {"Is this invoice mismatched?", "The total does not match the purchase order.", true},
        {"Is this invoice mismatched?", "The total matches the purchase order exactly.", false},
        {"Is the refactor risky?", "It touches payment code with no test coverage.", true},
        {"Is the refactor risky?", "It renames a local variable and changes no logic.", false},
        {"Is the migration irreversible?", "No rollback exists and there is no backup.", true},
        {"Should this alert page someone now?", "Downtime is ongoing and users are affected.",
         true},
        {"Should this alert page someone now?", "A blip that already recovered on its own.",
         false},
        {"Is the deadline at risk?", "Two days remain and half the work is undone.", true},
        {"Is the deadline at risk?", "Everything shipped a day ahead of schedule.", false},
        {"Is the migration irreversible?", "A tested rollback path exists.", false},
        {"Is this a security incident?",
         "The report is a phishing email we already blocked.", false},
    };
}

// A variant is the label pair plus the description pair. An empty description makes
// chuse_decision render the key twice ("true: true"), which is what the app does today when
// the user supplies no criteria.
struct Variant {
    const char* name;
    const char* true_key;
    const char* false_key;
    const char* true_desc;
    const char* false_desc;
};

const Variant VARIANTS[] = {
    // What the app sends today: the key is always rendered, so the boolean word is in the
    // label slot even when the description is empty.
    {"current (true/false)", "true", "false", "", ""},
    {"current + yes/no desc", "true", "false", "yes", "no"},
    {"current + long wording", "true", "false", "yes, the statement holds",
     "no, the statement does not hold"},
    // #156's working shapes.
    {"neutral A/B, bare", "A", "B", "", ""},
    {"neutral A/B + yes/no", "A", "B", "yes", "no"},
    {"neutral A/B + wording", "A", "B", "yes, the statement holds",
     "no, the statement does not hold"},
    {"polar positive/negative", "positive", "negative", "yes", "no"},
    {"digits 1/2, bare", "1", "2", "", ""},
};

struct Outcome {
    int correct = 0;
    int shown = 0;
    double sum_true = 0.0;
    int n_true = 0;
    double sum_false = 0.0;
    int n_false = 0;
};

Outcome run(chuse::DecisionModel& model, const Variant& v, const std::vector<Case>& cases) {
    Outcome o;
    const std::string true_desc = v.true_desc[0] == '\0' ? v.true_key : v.true_desc;
    const std::string false_desc = v.false_desc[0] == '\0' ? v.false_key : v.false_desc;

    for (const Case& c : cases) {
        std::vector<float> scores;
        std::string error;
        // The first option is always the true side, so scores[0] is the true column whatever
        // the label says. That keeps the measurement independent of the wording under test.
        if (!model.score(c.instructions, c.state, chuse::QuestionType::Noul,
                         {v.true_key, v.false_key}, {true_desc, false_desc}, &scores, &error)) {
            std::fprintf(stderr, "  score failed: %s\n", error.c_str());
            continue;
        }
        if (scores.size() != 2) continue;

        const float T = model.temperature(chuse::QuestionType::Noul, 2);
        const float mx = std::max(scores[0], scores[1]);
        const double a = std::exp((static_cast<double>(scores[0]) - mx) / T);
        const double b = std::exp((static_cast<double>(scores[1]) - mx) / T);
        const double p_true = a / (a + b);

        if ((p_true >= 0.5) == c.expect_true) o.correct++;
        o.shown++;
        if (c.expect_true) { o.sum_true += p_true; o.n_true++; }
        else { o.sum_false += p_true; o.n_false++; }
    }
    return o;
}

void report(const std::vector<Case>& cases, chuse::DecisionModel& model) {
    for (const Variant& v : VARIANTS) {
        const Outcome with = run(model, v, cases);
        const Outcome without = run(model, v, [&] {
            std::vector<Case> c = cases;
            for (Case& x : c) x.state.clear();
            return c;
        }());
        const double t = with.n_true ? with.sum_true / with.n_true : 0.0;
        const double f = with.n_false ? with.sum_false / with.n_false : 0.0;
        const double nt = without.n_true ? without.sum_true / without.n_true : 0.0;
        const double nf = without.n_false ? without.sum_false / without.n_false : 0.0;
        std::printf("  %-24s  state %2d/%2d  %.2f/%.2f gap %+.2f  |  none %2d/%2d  %.2f/%.2f gap "
                    "%+.2f\n",
                    v.name, with.correct, with.shown, t, f, t - f,
                    without.correct, without.shown, nt, nf, nt - nf);
    }
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: noul_label_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    // Exactly what goes on the wire, so the log can be checked against the claims.
    std::printf("\nRendered prompt for the first variant (this is what 'current' sends):\n  %s\n",
                model->render_prompt("Is the sky blue during the day?", "The sky is blue.",
                                     chuse::QuestionType::Noul, {"true", "false"}, {"", ""})
                    .c_str());
    std::printf("Rendered prompt for the neutral variant:\n  %s\n\n",
                model->render_prompt("Is the sky blue during the day?", "The sky is blue.",
                                     chuse::QuestionType::Noul, {"A", "B"}, {"yes", "no"})
                    .c_str());

    const auto factual = factual_cases();
    int n_true = 0;
    for (const Case& c : factual) n_true += c.expect_true ? 1 : 0;
    std::printf("%d factual cases, %d expected true, %d expected false, balanced.\n",
                static_cast<int>(factual.size()), n_true,
                static_cast<int>(factual.size()) - n_true);
    std::printf("Columns read as mean P(true) when true / mean P(true) when false.\n");
    std::printf("A label-independent model scores the same on every row.\n\n");
    std::printf("  Factual set (comparable with docs/laya-measured.md):\n");
    report(factual, *model);

    const auto judgement = judgement_cases();
    int j_true = 0;
    for (const Case& c : judgement) j_true += c.expect_true ? 1 : 0;
    std::printf("\n  Judgement set (%d cases, %d true / %d false) — the shape the app ships:\n",
                static_cast<int>(judgement.size()), j_true,
                static_cast<int>(judgement.size()) - j_true);
    report(judgement, *model);
    return 0;
}