// Chuse's native scoring bridge.
//
// Scores candidate answers against a prompt and returns raw log-probabilities, one per
// candidate. The Kotlin side owns softmax and every derived figure; this layer only turns
// text into numbers, so swapping the runtime cannot change how answers are reported.
//
// Scoring is teacher forcing, not sampling: the prompt is decoded once and its KV cache
// kept, then each candidate is scored against that cache and its own positions removed.
// Sampling would give one arbitrary answer; ranking needs all candidates scored on the
// same footing, which is exactly what the cache reuse buys.

#include <jni.h>

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// A loaded model plus its context. Held as an opaque handle so the Kotlin side only ever
// deals in a Long and cannot accidentally free the wrong pointer.
struct ChuseContext {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_memory_t memory = nullptr;
    int n_vocab = 0;
};

void throw_java(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

std::string jstring_to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

// Reads a UTF-8 byte array into a string.
//
// Strings cross the boundary as byte arrays rather than jstring on purpose:
// GetStringUTFChars returns *modified* UTF-8, in which characters outside the BMP become
// six-byte surrogate pairs. For CJK that is identical to standard UTF-8, but emoji and rare
// hanzi extensions would arrive as CESU-8 and tokenise into the wrong tokens — a failure
// that would show up as nonsense answers on exactly the input a Chinese phone is asked for.
std::string jbyte_array_to_string(JNIEnv * env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return {};
    std::string result;
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

std::vector<std::string> jobject_array_to_strings(JNIEnv * env, jobjectArray array) {
    std::vector<std::string> result;
    if (array == nullptr) return result;
    const jsize count = env->GetArrayLength(array);
    result.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
        result.push_back(jbyte_array_to_string(env, element));
        env->DeleteLocalRef(element);
    }
    return result;
}

// Applies the chat template baked into the GGUF, so the model is prompted the way its own
// authors intended. Falls back to a plain concatenation when the file has no template.
std::string build_prompt(ChuseContext * handle, const std::string & system, const std::string & user) {
    std::vector<llama_chat_message> messages;
    // Explicitly constructed rather than brace-initialised: llama_chat_message is a C struct
    // of two pointers, and a braced list against it is ambiguous.
    messages.push_back(llama_chat_message{"system", system.c_str()});
    messages.push_back(llama_chat_message{"user", user.c_str()});

    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user +
               "<|im_end|>\n<|im_start|>assistant\n";
    }

    // Generous first guess, then grow once if the template needed more room.
    std::vector<char> buffer(2 * (system.size() + user.size() + 256));
    for (int attempt = 0; attempt < 3; ++attempt) {
        const int32_t written =
            llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                      buffer.data(), static_cast<int32_t>(buffer.size()));
        if (written > 0 && static_cast<size_t>(written) < buffer.size()) {
            return std::string(buffer.data(), static_cast<size_t>(written));
        }
        buffer.resize(buffer.size() * 2);
    }
    return {};
}

bool tokenize(ChuseContext * handle, const std::string & text, bool add_special,
              std::vector<llama_token> & out) {
    const int32_t upper_bound =
        llama_tokenize(handle->vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0,
                       add_special, true);
    if (upper_bound < 0) return false;

    out.resize(upper_bound);
    const int32_t written = llama_tokenize(handle->vocab, text.data(),
                                           static_cast<int32_t>(text.size()), out.data(),
                                           upper_bound, add_special, true);
    if (written < 0) return false;
    out.resize(written);
    return true;
}

// log softmax of one position, read for a single token. Doing the reduction over the whole
// vocabulary each time is what turns logits into a comparable log-probability.
//
// Wrapped because llama_get_logits_ith throws on an out-of-range index, and an exception
// unwinding through a JNI frame is undefined behaviour rather than a Kotlin exception.
float log_softmax_at(llama_context * ctx, int32_t batch_index, llama_token token, int n_vocab) {
    float * logits = nullptr;
    try {
        logits = llama_get_logits_ith(ctx, batch_index);
    } catch (...) {
        return 0.0f;
    }
    if (logits == nullptr) return 0.0f;

    float max_logit = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > max_logit) max_logit = logits[i];
    }

    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) {
        sum += std::exp(static_cast<double>(logits[i]) - max_logit);
    }
    const double log_denominator = std::log(sum);
    return static_cast<float>(static_cast<double>(logits[token]) - max_logit - log_denominator);
}

// A batch with explicit positions and per-token logits.
//
// llama_batch_get_one cannot be used here: it leaves `pos` null, which the allocator fills
// from `i + p0` with p0 = 0, and it leaves `logits` null, which the allocator resolves to
// the final token only. Scoring a candidate needs every token's logits at their real
// positions — continuing from the cached prompt — so both are built explicitly here.
llama_batch make_batch(const std::vector<llama_token> & tokens, llama_pos first_pos) {
    const int32_t count = static_cast<int32_t>(tokens.size());
    llama_batch batch = llama_batch_init(count, 0, 1);
    batch.n_tokens = count;
    for (int32_t i = 0; i < count; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = first_pos + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[0][i] = 0;
        // Every token needs its logits: each one predicts the next token of the candidate.
        batch.logits[i] = 1;
    }
    return batch;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_chuse_inference_LlamaBridge_nativeLoad(JNIEnv * env, jclass,
                                                                        jstring path) {
    const std::string model_path = jstring_to_std(env, path);
    if (model_path.empty()) {
        throw_java(env, "model path was empty");
        return 0;
    }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;              // CPU only for now; a Vulkan build can raise this.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;  // Page the weights in rather than reading
                                                     // them into the heap. Renamed from the old
                                                     // `use_mmap` bool in v0.6.0.

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        throw_java(env, "could not load model from " + model_path);
        return 0;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;

    llama_context * ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_java(env, "could not create an inference context");
        return 0;
    }

    auto * handle = new ChuseContext();
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = llama_model_get_vocab(model);
    handle->memory = llama_get_memory(ctx);
    handle->n_vocab = llama_vocab_n_tokens(handle->vocab);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_chuse_inference_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle_value) {
    auto * handle = reinterpret_cast<ChuseContext *>(handle_value);
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) llama_free(handle->ctx);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}

JNIEXPORT jfloatArray JNICALL Java_com_chuse_inference_LlamaBridge_nativeScore(
    JNIEnv * env, jclass, jlong handle_value, jbyteArray jsystem, jbyteArray juser,
    jobjectArray jcandidates) {
    auto * handle = reinterpret_cast<ChuseContext *>(handle_value);
    if (handle == nullptr) {
        throw_java(env, "model is not loaded");
        return nullptr;
    }

    const std::vector<std::string> candidates = jobject_array_to_strings(env, jcandidates);
    const std::string prompt = build_prompt(handle, jbyte_array_to_string(env, jsystem),
                                            jbyte_array_to_string(env, juser));
    if (prompt.empty()) {
        throw_java(env, "could not build a prompt from the given messages");
        return nullptr;
    }

    std::vector<llama_token> prompt_tokens;
    if (!tokenize(handle, prompt, true, prompt_tokens) || prompt_tokens.empty()) {
        throw_java(env, "could not tokenize the prompt");
        return nullptr;
    }

    // Decode the shared prefix once. Everything below reuses this cache.
    llama_batch prompt_batch = make_batch(prompt_tokens, 0);
    if (llama_decode(handle->ctx, prompt_batch) != 0) {
        llama_batch_free(prompt_batch);
        throw_java(env, "the model failed while reading the prompt");
        return nullptr;
    }
    llama_batch_free(prompt_batch);

    const int32_t prompt_length = static_cast<int32_t>(prompt_tokens.size());
    std::vector<float> scores;
    scores.reserve(candidates.size());

    for (const std::string & candidate : candidates) {
        // Tokenising prompt+candidate and keeping the tail, rather than tokenising the
        // candidate alone, is what makes a leading space land the same way it would mid
        // sentence. Tokenising it standalone is a classic off-by-one-word source of garbage
        // rankings.
        std::vector<llama_token> candidate_tokens;
        float total = 0.0f;
        if (!candidate.empty()) {
            std::vector<llama_token> full;
            if (!tokenize(handle, prompt + candidate, true, full) ||
                full.size() <= static_cast<size_t>(prompt_length)) {
                throw_java(env, "could not tokenize candidate '" + candidate + "'");
                return nullptr;
            }
            candidate_tokens.assign(full.begin() + prompt_length, full.end());
        }

        if (candidate_tokens.empty()) {
            // An empty candidate is legal input but scores as log(1): never the winner.
            scores.push_back(0.0f);
            continue;
        }

        // The first candidate token is predicted by the prompt's own last position, whose
        // logits are already in the cache.
        total += log_softmax_at(handle->ctx, prompt_length - 1, candidate_tokens[0],
                                handle->n_vocab);

        if (candidate_tokens.size() > 1) {
            std::vector<llama_token> tail(candidate_tokens.begin() + 1, candidate_tokens.end());
            // Positions continue from the end of the cached prompt, which is what keeps this
            // token continuing that sequence rather than overwriting positions 0..m-2 of it.
            llama_batch tail_batch = make_batch(tail, prompt_length);
            if (llama_decode(handle->ctx, tail_batch) != 0) {
                llama_batch_free(tail_batch);
                throw_java(env, "the model failed while scoring candidate '" + candidate + "'");
                return nullptr;
            }
            // Batch index i predicts candidate_tokens[i + 1].
            for (size_t i = 0; i + 1 < candidate_tokens.size(); ++i) {
                total += log_softmax_at(handle->ctx, static_cast<int32_t>(i), candidate_tokens[i + 1],
                                        handle->n_vocab);
            }
            llama_batch_free(tail_batch);
            // Drop only this candidate's positions; the prompt cache stays warm for the next.
            // Renamed from llama_kv_cache_seq_rm in v0.6.0, which made the memory type explicit.
            llama_memory_seq_rm(handle->memory, 0, prompt_length, -1);
        }

        scores.push_back(total);
    }

    // The prompt occupies the cache between calls; clear it so the next request starts clean.
    llama_memory_seq_rm(handle->memory, 0, -1, -1);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(scores.size()), scores.data());
    return result;
}

}  // extern "C"