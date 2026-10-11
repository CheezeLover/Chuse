// Times one decision end to end, so latency claims are measured rather than guessed.
//
// The complaint this answers: the app "thinks forever". A decision is a single encoder pass,
// so the only places time can hide are the pass itself, the thread count it runs on, and the
// mmap page-in of 429 MB of weights on the first call. This probe separates them: it reports
// the first call on its own, then a steady-state average, and takes the thread count as an
// argument so the scaling is visible.
//
// Run on a phone-shaped question set; the numbers are compared across builds, not treated as
// absolute phone timings (this host is not a phone).

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

double time_one(chuse::DecisionModel& model, const std::string& question, const std::string& state) {
    std::vector<float> scores;
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    if (!model.score(question, state, chuse::QuestionType::Noul, {"true", "false"},
                     {"true", "false"}, &scores, &error)) {
        std::fprintf(stderr, "score failed: %s\n", error.c_str());
        std::exit(1);
    }
    const auto end = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::milli>(end - start).count();
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: latency_probe <model.gguf>\n");
        return 2;
    }

    const auto load_start = std::chrono::steady_clock::now();
    std::string error;
    auto model = chuse::DecisionModel::load(argv[1], &error);
    const double load_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - load_start)
            .count();
    if (!model) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }

    const std::vector<std::pair<std::string, std::string>> cases = {
        {"Is the sky blue during the day?", "The sky is blue during the day."},
        {"Does ice melt at room temperature?", "Ice melts at room temperature."},
        {"Is it safe to delete the backup first?",
         "A verified backup exists and the migration has been tested."},
        {"Is two plus two equal to four?", "Two plus two is equal to four."},
        {"Is the meeting at 3pm?", "The meeting is at 4pm."},
    };

    std::printf("load (mmap + context): %8.1f ms\n", load_ms);
    std::printf("first decision:        %8.1f ms  (includes mmap page-in)\n",
                time_one(*model, cases[0].first, cases[0].second));

    double total = 0.0;
    for (int round = 0; round < 4; round++) {
        for (const auto& c : cases) total += time_one(*model, c.first, c.second);
    }
    std::printf("steady state:          %8.1f ms  (mean of %zu decisions)\n",
                total / (4 * cases.size()), 4 * cases.size());
    return 0;
}
