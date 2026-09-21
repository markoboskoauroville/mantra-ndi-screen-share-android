package com.mantraproductions.ndiscreen

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * Puts a text file where a person can actually find it: Downloads, in a folder
 * with the app's name on it.
 *
 * This is the single reason the last build's crash reporter never produced a
 * file. It built a java.io.File path into the public Downloads directory and
 * wrote to it. From Android 10 that is refused, and it is refused SILENTLY:
 * the write throws nothing useful, the app carries on, and the folder stays
 * empty. Ten versions were shipped believing there was a crash log.
 *
 * So the public folder is written through MediaStore, which is the only route
 * an app has to it, and the answer is the display path or null. Never a
 * boolean nobody checks.
 */
object Downloads {

    private const val FOLDER = "Mantra NDI Screen Share"

    /**
     * Writes text into Downloads/Mantra NDI Screen Share and answers where it went, or null
     * if it could not be written.
     *
     * IS_PENDING is deliberately not used. It is the tidy way to publish a
     * file, and it needs a second call to clear it — which is one more thing
     * to complete while the process is being torn down by a crash. A report
     * that is visible while still being written beats a complete one that is
     * invisible because the process died between the write and the update.
     */
    fun writeText(context: Context, name: String, text: String): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: return null
            Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + name
        } catch (t: Throwable) {
            null
        }
    }

    /** Where a person should look, for showing on screen. */
    fun folder(): String = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
}
