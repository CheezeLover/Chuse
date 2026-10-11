package com.chuse.inference

import com.chuse.core.CloseableScorer
import com.chuse.core.ScoringRequest

/**
 * Runs a decision against llama.cpp on a background thread.
 *
 * The model is loaded once and kept for the life of the object: loading dominates the cost
 * of a one-shot decision, so reloading per question would make the app feel broken. Scoring
 * is serialised because one llama.cpp context cannot serve two callers at once, and this app
 * only ever has one.
 */
class LlamaScorer private constructor(handle: Long) : CloseableScorer {

    private val lock = Any()

    // Zeroed on close so a second close, or a score after close, fails loudly instead of
    // handing a freed pointer back to native code.
    private var handle: Long = handle

    override fun score(request: ScoringRequest, temperature: Float): FloatArray =
        synchronized(lock) {
            check(handle != 0L) { "this scorer has already been closed" }
            LlamaBridge.nativeScore(
                handle = handle,
                questionType = request.type.wireCode,
                instructions = request.instructions.toByteArray(Charsets.UTF_8),
                state = request.state.toByteArray(Charsets.UTF_8),
                keys = request.options.map { it.key.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                descriptions = request.options
                    .map { it.description.toByteArray(Charsets.UTF_8) }
                    .toTypedArray(),
            )
        }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                LlamaBridge.nativeFree(handle)
                handle = 0L
            }
        }
    }

    companion object {
        /**
         * Loads the model at [path]. Blocking and slow — call from a background thread.
         *
         * Throws [IllegalStateException] carrying llama.cpp's own complaint when the file is
         * missing, is not a GGUF, or is a model this bridge cannot answer questions with.
         */
        fun load(path: String): LlamaScorer {
            val handle = LlamaBridge.nativeLoad(path.toByteArray(Charsets.UTF_8))
            check(handle != 0L) { "llama.cpp returned no context for $path" }
            return LlamaScorer(handle)
        }
    }
}