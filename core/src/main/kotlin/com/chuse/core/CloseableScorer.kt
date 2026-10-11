package com.chuse.core

/**
 * A scorer that holds resources beyond the call — a loaded model, an open file — and needs
 * releasing when it is done.
 *
 * Declared here, in the module that owns the scorer contract, so the inference layer and the
 * repository can both depend on it without either depending on the other.
 */
interface CloseableScorer : CandidateScorer, AutoCloseable {
    override fun close()
}