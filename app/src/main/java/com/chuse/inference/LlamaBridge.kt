package com.chuse.inference

import com.chuse.core.CandidateScorer
import com.chuse.core.ScoringRequest

/**
 * The JNI surface to llama.cpp. Every method here is blocking and touches native memory, so
 * it must be called off the main thread — [LlamaScorer] is what enforces that.
 */
object LlamaBridge {

    /**
     * Loads a GGUF from [path] and returns an opaque handle.
     *
     * Returns a handle rather than a context object because the native side owns the
     * lifetime; Kotlin only ever passes the value back.
     */
    external fun nativeLoad(path: String): Long

    /** Frees the model behind [handle]. Safe to call with 0. */
    external fun nativeFree(handle: Long)

    /**
     * Returns one raw log-probability per candidate, in candidate order.
     *
     * Text arrives as UTF-8 byte arrays rather than strings: JNI's `GetStringUTFChars`
     * returns modified UTF-8, in which anything outside the BMP — emoji, rare hanzi
     * extensions — becomes a six-byte surrogate pair and tokenises into the wrong tokens.
     *
     * Higher is more likely. Values are un-normalised: softmax belongs to the caller, which
     * keeps this layer free of any opinion about what a probability means.
     */
    external fun nativeScore(
        handle: Long,
        system: ByteArray,
        user: ByteArray,
        candidates: Array<ByteArray>,
    ): FloatArray

    init {
        System.loadLibrary("chuse")
    }
}