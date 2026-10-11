// Chuse's native decision bridge for Laya (modern-bert, decision type "laya").
//
// Laya is not a language model that generates tokens. It is a classifier that answers a
// typed question in a single encoder pass:
//
//   [CLS]{type} question: {instructions}[SEP]
//   [MASK] option 1 [SEP] [MASK] option 2 [SEP] ...
//   [SEP]{state}[SEP]
//
// Each [MASK] marks one option, and the score for that option is a single float read from
// the embeddings output at that marker's position. Nothing is sampled and nothing is
// generated, so this file has no KV cache management and no teacher forcing — the earlier
// autoregressive implementation was for the wrong architecture entirely.
//
// The prompt shape is fixed by the GGUF's `tokenizer.chat_template.systemone`, and is
// reimplemented here rather than run through a Jinja engine: the template is ~1.5 KB of
// known text with no branches beyond the three question types, and embedding an interpreter
// for it would cost far more than the few lines that render it.
//
// Everything the model needs at run time — max_head_tokens, the per-type temperatures, the
// mask and separator tokens — is read from the GGUF at load, so a different Laya checkpoint
// works without a code change.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr int32_t QUESTION_CHOICE = 0;
constexpr int32_t QUESTION_SCORE  = 1;
constexpr int32_t QUESTION_NOUL   = 2;

/** A loaded model plus everything the decision path needs from its metadata. */
struct ChuseContext {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;

    llama_token token_mask = LLAMA_TOKEN_NULL;
    llama_token token_sep = LLAMA_TOKEN_NULL;

    // "modern-bert.decision.max_head_tokens"
    size_t max_head_tokens = 0;
    // "modern-bert.decision.temperature.<type>[.<bucket>]"
    std::map<std::string, float> temperatures;

    // The embeddings output has one column per question type.
    int32_t column = 0;
};

void throw_java(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

std::string meta_str(const llama_model * model, const std::string & key) {
    char buf[512];
    const int32_t n = llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf));
    return n < 0 ? std::string() : std::string(buf);
}

bool meta_float(const llama_model * model, const std::string & key, float & out) {
    const std::string raw = meta_str(model, key);
    if (raw.empty()) return false;
    try {
        out = std::stof(raw);
    } catch (...) {
        return false;
    }
    return true;
}

/**
 * Reads a UTF-8 byte array.
 *
 * Strings cross the boundary as byte arrays rather than jstring because GetStringUTFChars
 * returns *modified* UTF-8, in which anything outside the BMP becomes a six-byte surrogate
 * pair. Identical to standard UTF-8 for CJK, but it would mis-tokenise emoji.
 */
std::string jbytes(JNIEnv * env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return {};
    std::string result;
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

/**
 * Reads two parallel string arrays. Returns true on success.
 *
 * Deliberately false-means-ok so the call site reads as a guard: a malformed array is a
 * malformed request, not an empty one.
 */
bool read_option_arrays(
    JNIEnv * env, jobjectArray keys, jobjectArray descriptions,
    std::vector<std::string> * keys_out, std::vector<std::string> * descriptions_out) {
    if (keys == nullptr || descriptions == nullptr) return false;
    const jsize n_keys = env->GetArrayLength(keys);
    const jsize n_descriptions = env->GetArrayLength(descriptions);
    if (n_keys != n_descriptions) return false;

    for (jsize i = 0; i < n_keys; i++) {
        auto key = static_cast<jbyteArray>(env->GetObjectArrayElement(keys, i));
        auto description = static_cast<jbyteArray>(env->GetObjectArrayElement(descriptions, i));
        keys_out->push_back(jbytes(env, key));
        descriptions_out->push_back(jbytes(env, description));
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(description);
    }
    return true;
}

// ---------------------------------------------------------------------------
// Prompt construction
// ---------------------------------------------------------------------------

std::string or_empty(const std::string & value) { return value.empty() ? std::string() : value; }

/**
 * Renders the systemone template for one question.
 *
 * Mirrors `tokenizer.chat_template.systemone` from the GGUF:
 *
 *   [CLS]{type} question: {instructions}[SEP]
 *   {% for o in options %}[MASK] {per-type option text}[SEP]{% endfor %}
 *   {state}[SEP]
 *
 * The per-type option text is where the three question types differ:
 *   choice -> "key: description"
 *   score  -> "level {index}: {label}"
 *   noul   -> "true: yes, the statement holds" when no description is given
 */
std::string render_prompt(
    const ChuseContext * handle,
    int32_t question_type,
    const std::string & instructions,
    const std::string & state,
    const std::vector<std::string> & option_keys,
    const std::vector<std::string> & option_descriptions) {

    const char * type_name = question_type == QUESTION_CHOICE ? "choice"
                             : question_type == QUESTION_SCORE ? "score"
                                                              : "noul";

    std::string prompt = "[CLS]";
    prompt += type_name;
    prompt += " question: ";
    prompt += instructions;
    prompt += "[SEP]";

    for (size_t i = 0; i < option_keys.size(); i++) {
        prompt += "[MASK] ";
        if (question_type == QUESTION_SCORE) {
            // The scale index is the key here, which is why score levels must be keyed by
            // their position rather than by a user-supplied label.
            prompt += "level ";
            prompt += option_keys[i];
            prompt += ": ";
            prompt += option_descriptions[i];
        } else {
            prompt += option_keys[i];
            prompt += ": ";
            if (!option_descriptions[i].empty()) {
                prompt += option_descriptions[i];
            } else if (option_keys[i] == "true") {
                prompt += "yes, the statement holds";
            } else {
                prompt += "no, the statement does not hold";
            }
        }
        prompt += "[SEP]";
    }

    prompt += state;
    prompt += "[SEP]";
    return prompt;
}

bool tokenize(ChuseContext * handle, const std::string & text, std::vector<llama_token> & out) {
    const int32_t upper = llama_tokenize(handle->vocab, text.data(), static_cast<int32_t>(text.size()),
                                         nullptr, 0, false, true);
    if (upper < 0) return false;
    out.resize(upper);
    const int32_t written = llama_tokenize(handle->vocab, text.data(),
                                           static_cast<int32_t>(text.size()), out.data(), upper,
                                           false, true);
    if (written < 0) return false;
    out.resize(written);
    return true;
}

/**
 * Trims the prompt to the layout the model was trained on and records where each option's
 * marker ended up.
 *
 * The question and the options share a fixed budget (`max_head_tokens`), so an option list
 * that is too long shrinks every option evenly rather than truncating the last one. Mirrors
 * `fill_task_laya` in llama.cpp's server-decision.cpp.
 */
bool layout_prompt(
    ChuseContext * handle,
    std::vector<llama_token> & tokens,
    size_t n_options,
    std::vector<size_t> & markers_out) {

    std::vector<size_t> markers;
    for (size_t i = 0; i < tokens.size(); i++) {
        if (tokens[i] == handle->token_mask) markers.push_back(i);
    }
    // Every option must contribute exactly one marker, the head must be preceded by a
    // separator, and the prompt must end in one — otherwise the positions mean nothing.
    if (markers.size() != n_options || markers[0] < 2 ||
        tokens[markers[0] - 1] != handle->token_sep || tokens.back() != handle->token_sep) {
        return false;
    }

    const size_t head_end = markers[0] - 1;
    const size_t opts_end = static_cast<size_t>(
        std::find(tokens.begin() + static_cast<long>(markers.back()), tokens.end(),
                  handle->token_sep) -
        tokens.begin());
    if (opts_end + 1 >= tokens.size()) return false;

    // Split into marker + option text, then cap each option at 49 tokens (48 of text plus
    // its marker).
    std::vector<std::vector<llama_token>> options;
    size_t n_options_tokens = 0;
    auto set_max = [&](size_t n_max) {
        n_options_tokens = 0;
        for (auto & opt : options) {
            if (opt.size() > n_max) opt.resize(n_max);
            n_options_tokens += opt.size();
        }
    };
    for (size_t i = 0; i < n_options; i++) {
        const size_t end = i + 1 < n_options ? markers[i + 1] : opts_end;
        options.emplace_back(tokens.begin() + static_cast<long>(markers[i]),
                             tokens.begin() + static_cast<long>(end));
    }
    set_max(49);
    if (n_options_tokens + 16 > handle->max_head_tokens) {
        // Too many or too long options: shrink them evenly rather than dropping any.
        set_max(std::max<size_t>(4,
                                 (handle->max_head_tokens -
                                  std::min(handle->max_head_tokens, static_cast<size_t>(16))) /
                                     n_options));
    }
    const size_t n_question_max =
        std::max<size_t>(8, handle->max_head_tokens - std::min(handle->max_head_tokens, n_options_tokens));

    std::vector<llama_token> out;
    out.push_back(tokens[0]);
    out.insert(out.end(), tokens.begin() + 1,
               tokens.begin() + static_cast<long>(std::min(head_end, 1 + n_question_max)));
    out.push_back(handle->token_sep);
    markers_out.clear();
    for (const auto & opt : options) {
        markers_out.push_back(out.size());
        out.insert(out.end(), opt.begin(), opt.end());
    }
    out.insert(out.end(), tokens.begin() + static_cast<long>(opts_end), tokens.end());
    tokens = std::move(out);
    return true;
}

/**
 * The temperature for this question, which depends on both the type and how many options
 * there are. Looked up as `<type>.<bucket>` then `<type>`, matching the GGUF keys.
 */
float temperature_for(const ChuseContext * handle, int32_t question_type, size_t n_options) {
    const char * type_name = question_type == QUESTION_CHOICE ? "choice"
                             : question_type == QUESTION_SCORE ? "score"
                                                              : "noul";
    std::string bucket = n_options <= 2 ? "2"
                          : n_options <= 5 ? "3_5"
                          : n_options <= 10 ? "6_10"
                                           : "11";
    for (const std::string & name : {std::string(type_name) + "." + bucket, std::string(type_name)}) {
        const auto it = handle->temperatures.find(name);
        if (it != handle->temperatures.end()) return it->second;
    }
    return 1.0f;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_chuse_inference_LlamaBridge_nativeLoad(JNIEnv * env, jclass,
                                                                        jbyteArray jpath) {
    const std::string path = jbytes(env, jpath);
    if (path.empty()) {
        throw_java(env, "model path was empty");
        return 0;
    }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        throw_java(env, "could not load model from " + path);
        return 0;
    }

    auto * handle = new ChuseContext();
    handle->model = model;
    handle->vocab = llama_model_get_vocab(model);

    // Refuse a model this bridge cannot actually answer questions with, rather than
    // loading it and producing meaningless numbers later.
    const std::string arch = meta_str(model, "general.architecture");
    if (arch != "modern-bert") {
        llama_model_free(model);
        delete handle;
        throw_java(env, "expected a modern-bert model, got '" + arch + "'");
        return 0;
    }
    const std::string decision_type = meta_str(model, arch + ".decision.type");
    if (decision_type != "laya") {
        llama_model_free(model);
        delete handle;
        throw_java(env, "expected decision type 'laya', got '" + decision_type + "'");
        return 0;
    }

    handle->token_mask = llama_vocab_mask(handle->vocab);
    handle->token_sep = llama_vocab_sep(handle->vocab);
    if (handle->token_mask == LLAMA_TOKEN_NULL || handle->token_sep == LLAMA_TOKEN_NULL) {
        llama_model_free(model);
        delete handle;
        throw_java(env, "decision model has no mask or sep token");
        return 0;
    }

    float head_tokens = 0.0f;
    if (!meta_float(model, arch + ".decision.max_head_tokens", head_tokens) || head_tokens <= 0.0f) {
        llama_model_free(model);
        delete handle;
        throw_java(env, "decision model has no valid max_head_tokens");
        return 0;
    }
    handle->max_head_tokens = static_cast<size_t>(head_tokens);

    const std::string temp_prefix = arch + ".decision.temperature.";
    for (int32_t i = 0; i < llama_model_meta_count(model); i++) {
        char key[512];
        char val[64];
        if (llama_model_meta_key_by_index(model, i, key, sizeof(key)) < 0) continue;
        if (llama_model_meta_val_str_by_index(model, i, val, sizeof(val)) < 0) continue;
        const std::string k(key);
        if (k.rfind(temp_prefix, 0) != 0) continue;
        float t = 0.0f;
        if (!meta_float(model, k, t) || t <= 0.0f) {
            llama_model_free(model);
            delete handle;
            throw_java(env, "invalid decision temperature: " + k);
            return 0;
        }
        handle->temperatures[k.substr(temp_prefix.size())] = t;
    }
    if (handle->temperatures.empty()) {
        llama_model_free(model);
        delete handle;
        throw_java(env, "decision model has no temperatures");
        return 0;
    }

    // One encoder pass over the whole prompt; the scores come out of the embeddings.
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    // Not decoding tokens, so there is nothing to sample and no cache to grow.
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_NONE;

    llama_context * ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        delete handle;
        throw_java(env, "could not create an inference context");
        return 0;
    }
    handle->ctx = ctx;
    // Scores are read from one column of the embeddings, so more than one output column is
    // a sign the model is not what this bridge expects.
    handle->column = 0;
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_chuse_inference_LlamaBridge_nativeFree(JNIEnv *, jclass,
                                                                      jlong handle_value) {
    auto * handle = reinterpret_cast<ChuseContext *>(handle_value);
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) llama_free(handle->ctx);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}

JNIEXPORT jfloatArray JNICALL Java_com_chuse_inference_LlamaBridge_nativeScore(
    JNIEnv * env, jclass, jlong handle_value, jint question_type, jbyteArray jinstructions,
    jbyteArray jstate, jobjectArray jkeys, jobjectArray jdescriptions) {

    auto * handle = reinterpret_cast<ChuseContext *>(handle_value);
    if (handle == nullptr) {
        throw_java(env, "model is not loaded");
        return nullptr;
    }
    if (question_type != QUESTION_CHOICE && question_type != QUESTION_SCORE &&
        question_type != QUESTION_NOUL) {
        throw_java(env, "unknown question type");
        return nullptr;
    }

    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
    if (!read_option_arrays(env, jkeys, jdescriptions, &keys, &descriptions)) {
        throw_java(env, "could not read the options");
        return nullptr;
    }
    if (keys.size() < 2 || keys.size() != descriptions.size()) {
        throw_java(env, "a decision needs at least two options");
        return nullptr;
    }

    const std::string instructions = jbytes(env, jinstructions);
    const std::string state = jbytes(env, jstate);
    if (instructions.empty() || state.empty()) {
        throw_java(env, "a decision needs both an instruction and a state");
        return nullptr;
    }

    const std::string prompt =
        render_prompt(handle, question_type, instructions, state, keys, descriptions);

    std::vector<llama_token> tokens;
    if (!tokenize(handle, prompt, tokens) || tokens.empty()) {
        throw_java(env, "could not tokenize the decision prompt");
        return nullptr;
    }

    std::vector<size_t> markers;
    if (!layout_prompt(handle, tokens, keys.size(), markers)) {
        throw_java(env, "the decision prompt did not have the expected layout");
        return nullptr;
    }

    // One forward pass over the whole prompt, with every marker position marked as an
    // output so its embeddings are retained.
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[0][i] = 0;
        batch.logits[i] = 0;
    }
    for (const size_t marker : markers) {
        batch.logits[marker] = 1;
    }

    const int32_t rc = llama_decode(handle->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        throw_java(env, "the model failed to evaluate the decision");
        return nullptr;
    }

    // One float per option, read from the embeddings at that option's marker.
    std::vector<float> scores;
    scores.reserve(keys.size());
    for (const size_t marker : markers) {
        const float * embd = llama_get_embeddings_ith(handle->ctx, static_cast<int32_t>(marker));
        if (embd == nullptr) {
            throw_java(env, "could not read the decision scores");
            return nullptr;
        }
        scores.push_back(embd[handle->column]);
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(scores.size()), scores.data());
    return result;
}

}  // extern "C"