// Finds which value of the embeddings row actually carries an option's score.
//
// modern-bert's decision head runs once per question type, selecting the type with a learned
// type embedding, and each pass emits n_cls_out values that are concatenated. The row is
// therefore not "one score per column" in the way the earlier code assumed, and which value
// holds a given score is a property of the checkpoint rather than of any enum in llama.cpp.
//
// The evidence that the earlier read was wrong: two score questions with opposite expected
// answers returned byte-identical values, which cannot happen if the read position carries
// the answer. So this dumps every value of every marker row and reports which offset actually
// separates correct cases from their opposites.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

struct Case {
    chuse::QuestionType type;
    const char* label;
    std::string instructions;
    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
    int expected;  // index of the option that should win
};

std::vector<Case> cases() {
    return {
        {chuse::QuestionType::Noul, "noul  TRUE  (2+2=4)", "Is two plus two equal to four?",
         {"true", "false"}, {"true", "false"}, 0},
        {chuse::QuestionType::Noul, "noul  FALSE (2+2=5)", "Is two plus two equal to four?",
         {"true", "false"}, {"true", "false"}, 1},

        {chuse::QuestionType::Choice, "choice  reinstall", "Which best fixes a laptop that will not boot?",
         {"reinstall", "restore"}, {"reinstall", "restore"}, 0},
        {chuse::QuestionType::Choice, "choice  restore", "Which best fixes a laptop that will not boot?",
         {"restore", "reinstall"}, {"restore", "reinstall"}, 1},

        {chuse::QuestionType::Score, "score  deleted db", "How severe is this?",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}, 4},
        {chuse::QuestionType::Score, "score  typo", "How severe is this?",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}, 1},
    };
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: offset_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const int width = model->n_embd_out();
    std::printf("\nEach marker's embeddings row holds %d values.\n", width);
    std::printf("Reported as: the head runs once per question type and the results are\n");
    std::printf("concatenated, so the row should hold one block per type.\n\n");

    // hits[type][offset] = how many cases that offset answers correctly for that type.
    std::vector<std::vector<int>> hits(3, std::vector<int>(width, 0));
    std::vector<int> totals(3, 0);

    for (const Case& c : cases()) {
        std::vector<std::vector<float>> rows;
        if (!model->probe_embeddings(c.instructions, "", c.type, c.keys, c.descriptions, &rows,
                                     &error)) {
            std::printf("  %-22s FAILED: %s\n", c.label, error.c_str());
            continue;
        }
        const int ti = static_cast<int>(c.type);
        totals[ti]++;

        std::printf("--- %s  (%d markers)\n", c.label, static_cast<int>(rows.size()));
        for (size_t i = 0; i < rows.size(); i++) {
            std::printf("    option %d:", static_cast<int>(i));
            for (float v : rows[i]) std::printf(" %8.4f", v);
            std::printf("%s\n", static_cast<int>(i) == c.expected ? "   <- expected winner" : "");
        }

        // Which offset makes the expected option win?
        for (int o = 0; o < width; o++) {
            int best = 0;
            for (size_t i = 1; i < rows.size(); i++) {
                if (rows[i][o] > rows[best][o]) best = static_cast<int>(i);
            }
            if (best == c.expected) hits[ti][o]++;
        }
        std::printf("\n");
    }

    const char* names[3] = {"noul  ", "choice", "score "};
    std::printf("Cases answered correctly, by read offset:\n\n");
    std::printf("%-8s", "type");
    for (int o = 0; o < width; o++) std::printf(" col%-4d", o);
    std::printf("\n");
    for (int t = 0; t < 3; t++) {
        std::printf("%-8s", names[t]);
        for (int o = 0; o < width; o++) {
            std::printf(" %-6d", hits[t][o]);
        }
        std::printf("  (of %d)\n", totals[t]);
    }
    return 0;
}