package com.mantraproductions.ndiscreen

import android.content.Context
import android.os.Build

/**
 * What the app remembers between runs.
 *
 * Everything here is a choice the person made on the one screen. Nothing is
 * derived, nothing is cached state; if a value can be worked out from the
 * phone it is worked out each time rather than stored, because a stored
 * derivation is a stored mistake.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("screen-share", Context.MODE_PRIVATE)

    var sourceName: String
        get() = prefs.getString(KEY_NAME, null) ?: defaultSourceName()
        set(v) = prefs.edit().putString(KEY_NAME, Mechanism.sourceName(v, defaultSourceName())).apply()

    var mode: Mechanism.Mode
        get() = runCatching {
            Mechanism.Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "")
        }.getOrDefault(Mechanism.Mode.COMPRESSED)
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    var quality: Mechanism.Quality
        get() = runCatching {
            Mechanism.Quality.valueOf(prefs.getString(KEY_QUALITY, null) ?: "")
        }.getOrDefault(Mechanism.Quality.MEDIUM)
        set(v) = prefs.edit().putString(KEY_QUALITY, v.name).apply()

    /** The cap on the long edge. The short edge follows from the screen's shape. */
    var capLongEdge: Int
        get() = prefs.getInt(KEY_CAP, 1920)
        set(v) = prefs.edit().putInt(KEY_CAP, v).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 30)
        set(v) = prefs.edit().putInt(KEY_FPS, v).apply()

    /** H.265 halves the bitrate for the same picture, and not every receiver takes it. */
    var useHevc: Boolean
        get() = prefs.getBoolean(KEY_HEVC, false)
        set(v) = prefs.edit().putBoolean(KEY_HEVC, v).apply()

    /**
     * The name the phone announces itself as, before anybody changes it.
     *
     * `Build.MODEL` rather than a fixed string, because the point of the name
     * is to tell two phones apart in a receiver's list, and two phones running
     * this app would otherwise both be called the same thing.
     */
    fun defaultSourceName(): String =
        Mechanism.sourceName("${Build.MODEL} Screen", "Android Screen")

    companion object {
        private const val KEY_NAME = "source-name"
        private const val KEY_MODE = "mode"
        private const val KEY_QUALITY = "quality"
        private const val KEY_CAP = "cap-long-edge"
        private const val KEY_FPS = "fps"
        private const val KEY_HEVC = "hevc"

        /** The caps offered on the screen, long edge in pixels. */
        val CAPS = intArrayOf(720, 1280, 1920, 2560)

        /** The frame rates offered on the screen. */
        val RATES = intArrayOf(15, 24, 30, 60)
    }
}
