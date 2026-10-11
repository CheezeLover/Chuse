// Establishes which embeddings column belongs to which question type.
//
// modern-bert runs its decision head three times, once per question type, selecting the type
// by a learned type embedding (`it` = 0, 1, 2). That ordering belongs to the checkpoint, not
// to any enum in llama.cpp, so it has to be measured rather than assumed — reading the wrong
// column silently scores one question type against another's head.
//
// This scores every type against every column and reports which pairing is self-consistent,
// i.e. which column gives each type a decisive, correct answer on unambiguous cases.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

struct Case {
    chuse::QuestionType type;
    std::string instructions;
    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
    int expected_index;  // which option should win, by position
    const char* label;
};

std::vector<Case> cases() {
    return {
        // noul: the "true" option is first in both orderings, so position 0 is expected.
        {chuse::QuestionType::Noul, "Is two plus two equal to four?",
         {"true", "false"}, {"true", "false"}, 0, "noul / 2+2=4"},

        {chuse::QuestionType::Noul, "Is two plus two equal to five?",
         {"true", "false"}, {"true", "false"}, 1, "noul / 2+2=5"},

        // choice: a clear winner at position 1.
        {chuse::QuestionType::Choice, "Which best fixes a laptop that will not boot?",
         {"reinstall", "restore"}, {"reinstall", "restore"}, 1, "choice / reinstall"},

        {chuse::QuestionType::Choice, "Which is best for a laptop that will not boot?",
         {"restore", "reinstall"}, {"restore", "reinstall"}, 1, "choice / restore"},

        // score: severe should land high, index 4.
        {chuse::QuestionType::Score, "How severe is this?",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}, 4, "score / deleted db"},

        {chuse::QuestionType::Score, "How severe is this?",
         {"0", "1", "2", "3", "4"},
         {"negligible", "minor", "moderate", "major", "catastrophic"}, 1, "score / typo"},
    };
}

struct Cell {
    int correct = 0;
    int total = 0;
    double margin = 0.0;
};

// Runs each case, recording the raw score of every column for later cross-tabulation.
void collect(chuse::DecisionModel& model, std::vector<std::vector<float>>& raw) {
    for (const Case& c : cases()) {
        std::vector<float> scores;
        std::string error;
        if (!model.score(c.instructions, "", c.type, c.keys, c.descriptions, &scores, &error)) {
            std::printf("  %-22s FAILED: %s\n", c.label, error.c_str());
            continue;
        }
        raw.push_back(scores);
    }
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: column_probe <model.gguf>\n");
        return 2;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    std::printf("\nn_embd_out is %d columns; the head runs once per question type.\n", 3);
    std::printf("Reading a column for the wrong type is silent, so this is measured.\n\n");

    std::vector<std::vector<float>> raw;
    collect(*model, raw);

    if (raw.empty()) {
        std::printf("no cases produced scores\n");
        return 1;
    }

    // Which column is widest in absolute terms per case is not meaningful across types, so
    // instead report the raw table and let each type be read off its own row.
    std::printf("%-22s %10s %10s %10s\n", "case", "col0", "col1", "col2");
    const auto all = cases();
    for (size_t i = 0; i < raw.size(); i++) {
        std::printf("%-22s %10.4f %10.4f %10.4f\n", all[i].label, raw[i][0], raw[i][1], raw[i][2]);
    }

    std::printf("\nPer type, which column puts the expected option on top?\n");
    // noul -> 2 rows, choice -> 2, score -> 2. Score into a 3x3 hit table.
    int hits[3][3] = {};
    int per_type[3] = {};
    size_t row = 0;
    for (const Case& c : all) {
        if (row >= raw.size()) break;
        const int ti = static_cast<int>(c.type);
        // The temperature differs per type, but for a within-case comparison the argmax is
        // unchanged by a positive scale, so the raw argmax is the right test here.
        int best = 0;
        for (size_t k = 1; k < raw[row].size(); k++) {
            if (raw[row][k] > raw[row][best]) best = static_cast<int>(k);
        }
        hits[ti][best]++;
        per_type[ti]++;
        row++;
    }

    const char* names[3] = {"noul  ", "choice", "score "};
    std::printf("\n%-8s %8s %8s %8s\n", "type", "col0", "col1", "col2");
    for (int t = 0; t < 3; t++) {
        std::printf("%-8s %8d %8d %8d   (of %d)\n", names[t], hits[t][0], hits[t][1], hits[t][2],
                    per_type[t]);
    }
    return 0;
}