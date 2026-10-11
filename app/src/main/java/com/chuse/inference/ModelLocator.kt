package com.chuse.inference

import java.io.File

/**
 * Locates the bundled GGUF and hands back a path llama.cpp can open.
 *
 * llama.cpp opens a model by path, and an APK asset is not a path, so the model is copied
 * into the app's cache directory the first time it is needed and reused from there after.
 * The cache is deliberate: it is the one place the system may reclaim under storage
 * pressure, and the app can simply copy the asset again, which a file in `filesDir` could
 * not safely promise.
 *
 * The asset is marked `noCompress` in the build so the APK does not spend launch time
 * deflating hundreds of megabytes it will only inflate again.
 */
object ModelLocator {

    const val MODEL_ASSET = "model.gguf"

    /** Whether this build shipped a model at all. */
    fun assetExists(context: android.content.Context): Boolean = runCatching {
        context.assets.open(MODEL_ASSET).close()
    }.isSuccess

    /**
     * A filesystem path for the model, copying it out of the APK on first use, or null when
     * the build has no model bundled.
     *
     * Null is a supported state, not an error: the app runs its interface against a stand-in
     * scorer so it can be developed and demoed before the GGUF is dropped in.
     */
    fun resolve(context: android.content.Context): String? {
        if (!assetExists(context)) return null
        val cached = File(context.cacheDir, MODEL_ASSET)
        if (cached.exists() && cached.length() > 0) return cached.absolutePath

        return runCatching {
            context.assets.open(MODEL_ASSET).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            cached.absolutePath
        }.getOrNull()
    }
}