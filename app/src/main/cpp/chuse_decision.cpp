#include "chuse_decision.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <vector>

#include "llama.h"

namespace chuse {
namespace {

std::string meta_str(const llama_model* model, const std::string& key) {
    char buf[512];
    const int32_t n = llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf));
    return n < 0 ? std::string() : std::string(buf);
}

bool meta_float(const llama_model* model, const std::string& key, float* out) {
    const std::string raw = meta_str(model, key);
    if (raw.empty()) return false;
    try {
        *out = std::stof(raw);
    } catch (...) {
        return false;
    }
    return true;
}

const char* type_name(QuestionType type) {
    switch (type) {
        case QuestionType::Choice: return "choice";
        case QuestionType::Score:  return "score";
        case QuestionType::Noul:   return "noul";
    }
    return "noul";
}

}  // namespace

struct DecisionModel::Impl {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::map<std::string, float> temperatures;
    int32_t column = 0;
};

DecisionModel::DecisionModel() : impl_(new Impl()) {}

DecisionModel::~DecisionModel() {
    if (impl_->ctx != nullptr) llama_free(impl_->ctx);
    if (impl_->model != nullptr) llama_model_free(impl_->model);
}

std::unique_ptr<DecisionModel> DecisionModel::load(const std::string& path, std::string* error) {
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model* model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        if (error) *error = "could not load model from " + path;
        return nullptr;
    }

    // Refuse a model this code cannot answer questions with, rather than loading it and
    // returning meaningless numbers later.
    const std::string arch = meta_str(model, "general.architecture");
    if (arch != "modern-bert") {
        if (error) *error = "expected a modern-bert model, got '" + arch + "'";
        llama_model_free(model);
        return nullptr;
    }
    const std::string decision_type = meta_str(model, arch + ".decision.type");
    if (decision_type != "laya") {
        if (error) *error = "expected decision type 'laya', got '" + decision_type + "'";
        llama_model_free(model);
        return nullptr;
    }

    float head_tokens = 0.0f;
    if (!meta_float(model, arch + ".decision.max_head_tokens", &head_tokens) || head_tokens <= 0.0f) {
        if (error) *error = "decision model has no valid max_head_tokens";
        llama_model_free(model);
        return nullptr;
    }

    std::unique_ptr<DecisionModel> self(new DecisionModel());
    self->max_head_tokens_ = static_cast<size_t>(head_tokens);
    self->impl_->model = model;
    self->impl_->vocab = llama_model_get_vocab(model);
    self->token_mask_ = llama_vocab_mask(self->impl_->vocab);
    self->token_sep_ = llama_vocab_sep(self->impl_->vocab);

    if (self->token_mask_ == LLAMA_TOKEN_NULL || self->token_sep_ == LLAMA_TOKEN_NULL) {
        if (error) *error = "decision model has no mask or sep token";
        return nullptr;
    }

    const std::string temp_prefix = arch + ".decision.temperature.";
    for (int32_t i = 0; i < llama_model_meta_count(model); i++) {
        char key[512];
        char val[64];
        if (llama_model_meta_key_by_index(model, i, key, sizeof(key)) < 0) continue;
        if (llama_model_meta_val_str_by_index(model, i, val, sizeof(val)) < 0) continue;
        const std::string k(key);
        if (k.rfind(temp_prefix, 0) != 0) continue;
        float t = 0.0f;
        if (!meta_float(model, k, &t) || t <= 0.0f) {
            if (error) *error = "invalid decision temperature: " + k;
            return nullptr;
        }
        self->impl_->temperatures[k.substr(temp_prefix.size())] = t;
    }
    if (self->impl_->temperatures.empty()) {
        if (error) *error = "decision model has no temperatures";
        return nullptr;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_NONE;

    self->impl_->ctx = llama_init_from_model(model, context_params);
    if (self->impl_->ctx == nullptr) {
        if (error) *error = "could not create an inference context";
        return nullptr;
    }
    return self;
}

float DecisionModel::temperature(QuestionType type, size_t option_count) const {
    const std::string bucket = option_count <= 2  ? "2"
                             : option_count <= 5  ? "3_5"
                             : option_count <= 10 ? "6_10"
                                                  : "11";
    for (const std::string& name :
         {std::string(type_name(type)) + "." + bucket, std::string(type_name(type))}) {
        const auto it = impl_->temperatures.find(name);
        if (it != impl_->temperatures.end()) return it->second;
    }
    return 1.0f;
}

std::string DecisionModel::render_prompt(
    const std::string& instructions,
    const std::string& state,
    QuestionType type,
    const std::vector<std::string>& option_keys,
    const std::vector<std::string>& option_descriptions) const {

    // Mirrors tokenizer.chat_template.systemone from the GGUF. Reimplemented rather than run
    // through Jinja: this is ~1.5 KB of known text branching only on question type, and an
    // interpreter would cost far more than these lines.
    std::string prompt = "[CLS]";
    prompt += type_name(type);
    prompt += " question: ";
    prompt += instructions;
    prompt += "[SEP]";

    for (size_t i = 0; i < option_keys.size(); i++) {
        prompt += "[MASK] ";
        if (type == QuestionType::Score) {
            prompt += "level ";
            prompt += option_keys[i];
            prompt += ": ";
            prompt += option_descriptions[i];
        } else if (type == QuestionType::Noul) {
            // Short and symmetric, on purpose. The GGUF's fallback wording is
            // "yes, the statement holds" versus "no, the statement does not hold", and
            // measuring the real model showed that phrasing biases answers negative: it is
            // longer and more emphatic on the false side. Across nine cases it answered 6/9
            // against 8/9 for "true"/"false", and it was confidently wrong on plainly true
            // statements — the model is weak on true, and this wording made it worse.
            //
            // See tools/noul_wording_probe.cpp, and docs/laya-measured.md.
            prompt += option_keys[i];
            prompt += ": ";
            prompt += option_descriptions[i].empty() ? option_keys[i] : option_descriptions[i];
        } else {
            // Choice renders the key alone unless it has a description. The earlier code let
            // this fall into the noul branch, which printed "no, the statement does not hold"
            // after every option — a prompt the model was never trained to read.
            prompt += option_keys[i];
            if (!option_descriptions[i].empty()) {
                prompt += ": ";
                prompt += option_descriptions[i];
            }
        }
        prompt += "[SEP]";
    }

    prompt += state;
    prompt += "[SEP]";
    return prompt;
}

std::vector<int32_t> DecisionModel::tokenize(const std::string& text) const {
    // llama.cpp reports a size query as the *negated* token count (upper = -n), so a negative
    // return is the normal success path, not an error. Only INT32_MIN signals a genuine
    // failure. Reading this as "negative means broken" made every prompt look untokenisable.
    const int32_t upper = llama_tokenize(
        impl_->vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, true);
    if (upper == INT32_MIN) return {};

    const int32_t capacity = upper < 0 ? -upper : upper;
    std::vector<llama_token> tokens(static_cast<size_t>(capacity));
    const int32_t written = llama_tokenize(
        impl_->vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), capacity,
        false, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string DecisionModel::detokenize(const std::vector<int32_t>& tokens) const {
    std::vector<llama_token> converted(tokens.begin(), tokens.end());
    std::string out;
    const int32_t n = llama_detokenize(
        impl_->vocab, converted.data(), static_cast<int32_t>(converted.size()), out.data(), 0,
        false, false);
    return n > 0 ? out : std::string();
}

bool DecisionModel::layout(
    std::vector<int32_t>* tokens,
    size_t option_count,
    std::vector<size_t>* markers_out,
    std::string* error) const {

    std::vector<size_t> markers;
    for (size_t i = 0; i < tokens->size(); i++) {
        if ((*tokens)[i] == token_mask_) markers.push_back(i);
    }
    if (markers.size() != option_count || markers[0] < 2 ||
        (*tokens)[markers[0] - 1] != token_sep_ || tokens->back() != token_sep_) {
        if (error) *error = "unexpected layout of the decision prompt";
        return false;
    }

    const size_t head_end = markers[0] - 1;
    const size_t opts_end = static_cast<size_t>(
        std::find(tokens->begin() + static_cast<long>(markers.back()), tokens->end(), token_sep_) -
        tokens->begin());
    if (opts_end + 1 >= tokens->size()) {
        if (error) *error = "decision prompt has no room for the state";
        return false;
    }

    std::vector<std::vector<int32_t>> options;
    size_t n_options_tokens = 0;
    auto set_max = [&](size_t n_max) {
        n_options_tokens = 0;
        for (auto& opt : options) {
            if (opt.size() > n_max) opt.resize(n_max);
            n_options_tokens += opt.size();
        }
    };
    for (size_t i = 0; i < option_count; i++) {
        const size_t end = i + 1 < option_count ? markers[i + 1] : opts_end;
        options.emplace_back(tokens->begin() + static_cast<long>(markers[i]),
                             tokens->begin() + static_cast<long>(end));
    }
    set_max(49);
    if (n_options_tokens + 16 > max_head_tokens_) {
        set_max(std::max<size_t>(4,
                                 (max_head_tokens_ - std::min(max_head_tokens_, static_cast<size_t>(16))) /
                                     option_count));
    }
    const size_t n_question_max = std::max<size_t>(
        8, max_head_tokens_ - std::min(max_head_tokens_, n_options_tokens));

    std::vector<int32_t> out;
    out.push_back((*tokens)[0]);
    out.insert(out.end(), tokens->begin() + 1,
               tokens->begin() + static_cast<long>(std::min(head_end, 1 + n_question_max)));
    out.push_back(token_sep_);
    markers_out->clear();
    for (const auto& opt : options) {
        markers_out->push_back(out.size());
        out.insert(out.end(), opt.begin(), opt.end());
    }
    out.insert(out.end(), tokens->begin() + static_cast<long>(opts_end), tokens->end());
    *tokens = std::move(out);
    return true;
}

bool DecisionModel::score(
    const std::string& instructions,
    const std::string& state,
    QuestionType type,
    const std::vector<std::string>& option_keys,
    const std::vector<std::string>& option_descriptions,
    std::vector<float>* scores_out,
    std::string* error) {

    if (option_keys.size() < 2 || option_keys.size() != option_descriptions.size()) {
        if (error) *error = "a decision needs at least two options";
        return false;
    }

    const std::string prompt =
        render_prompt(instructions, state, type, option_keys, option_descriptions);

    std::vector<int32_t> tokens = tokenize(prompt);
    if (tokens.empty()) {
        if (error) *error = "could not tokenize the decision prompt";
        return false;
    }

    std::vector<size_t> markers;
    if (!layout(&tokens, option_keys.size(), &markers, error)) return false;

    // seq_id is an array of per-token rows, not a shared row: seq_id[i] is the row for token
    // i. Writing every token through seq_id[0] overruns the first row and leaves the rest
    // uninitialised, which is what produced garbage sequence ids at encode time.
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;
    }
    for (const size_t marker : markers) batch.logits[marker] = 1;

    // modern-bert is an encoder: llama_decode refuses the batch and tells us to call
    // llama_encode. There is no KV cache and nothing is generated, which is exactly why a
    // decision model answers in one pass.
    const int32_t rc = llama_encode(impl_->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        if (error) *error = "the model failed to evaluate the decision";
        return false;
    }

    scores_out->clear();
    scores_out->reserve(markers.size());
    for (const size_t marker : markers) {
        const float* embd = llama_get_embeddings_ith(impl_->ctx, static_cast<int32_t>(marker));
        if (embd == nullptr) {
            if (error) *error = "could not read the decision scores";
            return false;
        }
        // One column per question type, in the order of chuse::QuestionType — upstream
        // selects it the same way (`column = question.type`). Reading column 0 for
        // everything scored noul against the *choice* head, which produced confident
        // nonsense for every yes/no question while looking entirely plausible.
        scores_out->push_back(embd[static_cast<int32_t>(type)]);
    }
    return true;
}

}  // namespace chuse