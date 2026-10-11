// Android JNI bridge for Chuse.
//
// A thin shim over chuse_decision.cpp, which holds the prompt layout and the scoring. Keeping
// the logic out of here is what lets tools/decision_probe.cpp run the same code against the
// real model on a workstation, so the prompt and the scores can be checked without a device.
// This file only converts between JNI types and calls it.

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "chuse_decision.h"

namespace {

void throw_java(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

/**
 * Reads a UTF-8 byte array.
 *
 * Strings cross as byte arrays rather than jstring because GetStringUTFChars returns
 * *modified* UTF-8, in which anything outside the BMP becomes a six-byte surrogate pair.
 * Identical to standard UTF-8 for CJK, but it would mis-tokenise emoji.
 */
std::string jbytes(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return {};
    std::string result;
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

bool read_option_arrays(
    JNIEnv* env, jobjectArray keys, jobjectArray descriptions,
    std::vector<std::string>* keys_out, std::vector<std::string>* descriptions_out) {
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

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_chuse_inference_LlamaBridge_nativeLoad(JNIEnv* env, jclass,
                                                                       jbyteArray jpath) {
    const std::string path = jbytes(env, jpath);
    if (path.empty()) {
        throw_java(env, "model path was empty");
        return 0;
    }
    std::string error;
    auto model = chuse::DecisionModel::load(path, &error);
    if (!model) {
        throw_java(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

JNIEXPORT void JNICALL Java_com_chuse_inference_LlamaBridge_nativeFree(JNIEnv*, jclass,
                                                                      jlong handle) {
    delete reinterpret_cast<chuse::DecisionModel*>(handle);
}

JNIEXPORT jfloatArray JNICALL Java_com_chuse_inference_LlamaBridge_nativeScore(
    JNIEnv* env, jclass, jlong handle, jint question_type, jbyteArray jinstructions,
    jbyteArray jstate, jobjectArray jkeys, jobjectArray jdescriptions) {

    auto* model = reinterpret_cast<chuse::DecisionModel*>(handle);
    if (model == nullptr) {
        throw_java(env, "model is not loaded");
        return nullptr;
    }
    if (question_type != static_cast<jint>(chuse::QuestionType::Choice) &&
        question_type != static_cast<jint>(chuse::QuestionType::Score) &&
        question_type != static_cast<jint>(chuse::QuestionType::Noul)) {
        throw_java(env, "unknown question type");
        return nullptr;
    }

    std::vector<std::string> keys;
    std::vector<std::string> descriptions;
    if (!read_option_arrays(env, jkeys, jdescriptions, &keys, &descriptions)) {
        throw_java(env, "could not read the options");
        return nullptr;
    }

    std::vector<float> scores;
    std::string error;
    if (!model->score(jbytes(env, jinstructions), jbytes(env, jstate),
                      static_cast<chuse::QuestionType>(question_type), keys, descriptions, &scores,
                      &error)) {
        throw_java(env, error);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(scores.size()), scores.data());
    return result;
}

}  // extern "C"