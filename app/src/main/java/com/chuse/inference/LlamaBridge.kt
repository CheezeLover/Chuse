package com.chuse.inference

import com.chuse.core.CloseableScorer
import com.chuse.core.ScoringRequest

/**
 * The JNI surface to llama.cpp. Every method here is blocking and touches native memory, so
 * it must be called off the main thread — [LlamaScorer] is what enforces that.
 */
object LlamaBridge {

    /**
     * Loads a GGUF from [path] (UTF-8 bytes) and returns an opaque handle.
     *
     * Returns a handle rather than a context object because the native side owns the
     * lifetime; Kotlin only ever passes the value back.
     */
    external fun nativeLoad(path: ByteArray): Long

    /** Frees the model behind [handle]. Safe to call with 0. */
    external fun nativeFree(handle: Long)

    /**
     * Answers one typed question, returning one raw score per option in option order.
     *
     * Strings cross as UTF-8 byte arrays because JNI's `GetStringUTFChars` returns modified
     * UTF-8, in which anything outside the BMP becomes a six-byte surrogate pair and
     * tokenises into the wrong tokens.
     *
     * The values are un-normalised; the temperature is applied by the caller so that the
     * engine owns every step from raw score to probability.
     */
    external fun nativeScore(
        handle: Long,
        questionType: Int,
        instructions: ByteArray,
        state: ByteArray,
        keys: Array<ByteArray>,
        descriptions: Array<ByteArray>,
    ): FloatArray

    init {
        System.loadLibrary("chuse")
    }
}