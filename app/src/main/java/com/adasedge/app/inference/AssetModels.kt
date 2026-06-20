package com.adasedge.app.inference

import android.content.Context
import java.io.File

/** Helpers for locating and materializing model assets bundled under assets/models/. */
object AssetModels {
    private const val DIR = "models"

    fun exists(ctx: Context, assetName: String): Boolean = try {
        ctx.assets.open("$DIR/$assetName").use { true }
    } catch (_: Throwable) { false }

    fun readBytes(ctx: Context, assetName: String): ByteArray =
        ctx.assets.open("$DIR/$assetName").use { it.readBytes() }

    /**
     * Copies an asset to a private file once (QNN's context loader needs a path)
     * and returns the absolute path. Re-copies only if size differs.
     */
    fun materialize(ctx: Context, assetName: String): String {
        val out = File(ctx.filesDir, "$DIR/$assetName").apply { parentFile?.mkdirs() }
        val srcLen = ctx.assets.openFd("$DIR/$assetName").use { it.length }
        if (!out.exists() || out.length() != srcLen) {
            ctx.assets.open("$DIR/$assetName").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out.absolutePath
    }
}
