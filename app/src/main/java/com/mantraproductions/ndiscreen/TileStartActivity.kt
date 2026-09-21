package com.mantraproductions.ndiscreen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Nothing to look at. The dialog holder for the tile.
 *
 * MediaProjection is granted to an activity or to nobody, so the tile needs one
 * — but it must not be the one screen, or every tap from the shade would leave
 * the app's window sitting in front of whatever he was streaming. This activity
 * has a transparent theme and no layout at all: it asks, it starts the service,
 * it finishes. What the person sees is the system's own dialog over the app
 * they were already in.
 *
 * ORDER. The notification is asked for first and the screen second, because two
 * system dialogs cannot be on screen at once — the second is dropped, not
 * queued, and the dropped one is always the one that arrived last. So the
 * capture is only asked for from inside the notification answer.
 *
 * ONCE. Everything is behind savedInstanceState == null. A turn of the phone
 * while the dialog is up recreates this activity, and asking again would put a
 * second dialog behind the first and leave the person tapping through two.
 */
class TileStartActivity : AppCompatActivity() {

    private lateinit var askForScreen: ActivityResultLauncher<Intent>
    private lateinit var askForNotifications: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askForScreen = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Trace.step("tile: screen capture granted by the person")
                startService(
                    Intent(this, ScreenShareService::class.java)
                        .setAction(ScreenShareService.ACTION_START)
                        .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                )
            } else {
                Trace.refused("tile: screen capture", "the person said no")
                // The tile is still dark and the trace says why. Nothing is
                // shown, because a toast over somebody else's full screen is
                // worse than the silence of a tile that did not light up.
                NdiTileService.refresh(this)
            }
            finish()
        }

        askForNotifications = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Trace.state("tile: notifications permission = $granted")
            askForScreen()
        }

        if (savedInstanceState == null) begin()
    }

    private fun begin() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForScreen()
        }
    }

    private fun askForScreen() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        askForScreen.launch(manager.createScreenCaptureIntent())
    }
}
