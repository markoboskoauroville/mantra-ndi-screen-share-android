package com.mantraproductions.ndiscreen

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The tile in the Quick Settings shade.
 *
 * Marko, 21.9.2026, with a screenshot of his shade: "I want that my icon come
 * here. Mantra Streamer NDI." So the share starts and stops from the same place
 * as the torch and the screen recorder — pull down, one tap, live.
 *
 * WHY THIS IS NOT JUST A BUTTON. A tile cannot start the share by itself.
 * MediaProjection is only ever granted to an *activity*, through the system's
 * own dialog, and it is granted once — the token cannot be kept and reused, so
 * every start must show that dialog again. Android gives no way around it and
 * Screen Stream does not have one either. So:
 *
 *     tap while idle     ->  the shade closes, an invisible activity appears,
 *                            the system asks, the service starts, the activity
 *                            is gone before the picture settles
 *     tap while sending  ->  the service is told to stop, and nothing is shown
 *                            at all, because stopping needs no permission
 *
 * That asymmetry is the whole design. Stopping is the press that has to work
 * instantly and without a dialog, and it does — including over the lock screen.
 */
class NdiTileService : TileService() {

    /**
     * Called every time the shade opens on this tile, and every time the
     * service asks for it through [refresh]. The tile is drawn from
     * [ScreenShareService.status] and nothing else, so it cannot disagree with
     * the one screen or with the notification.
     */
    override fun onStartListening() {
        super.onStartListening()
        // Traced because a tile that shows the wrong state is invisible to every
        // other instrument: nothing crashes, nothing logs, the shade just says
        // something that is not true.
        Trace.state("tile: listening, service says running = ${ScreenShareService.status.running}")
        render()
    }

    override fun onStopListening() {
        super.onStopListening()
        Trace.state("tile: stopped listening")
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Trace.step("tile: added to the shade")
        render()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenShareService.status.running) {
            stopShare()
        } else {
            // A locked phone cannot show the capture dialog: it would sit
            // behind the lock screen and the tap would look ignored. Android
            // will take the person through the lock first and then run this.
            unlockAndRun { startShare() }
        }
    }

    // --------------------------------------------------------------- the two

    private fun stopShare() {
        Trace.step("tile: stop asked for")
        // Deliberately not unlockAndRun. Stopping is what you reach for when
        // something is on air that should not be, and making that wait for a
        // fingerprint would be the wrong way round.
        startService(
            Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_STOP)
        )
        // Drawn at once rather than waiting for the service to publish, so the
        // tile goes dark under the thumb. The service's own refresh follows a
        // moment later and is what the state finally rests on.
        draw(active = false, subtitle = SUBTITLE_IDLE)
    }

    private fun startShare() {
        // A build with no SDK in it can never send, and a tile has nowhere to
        // say so. The one screen does, so open it instead of failing silently.
        val target = if (NdiSender.available) {
            Trace.step("tile: start asked for")
            Intent(this, TileStartActivity::class.java)
        } else {
            Trace.refused("tile start", "NDI library not present in the APK")
            Intent(this, MainActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        collapseAndStart(target)
    }

    /**
     * Closes the shade and starts the activity.
     *
     * From 34 the Intent form throws UnsupportedOperationException — it is not
     * deprecated-but-working, it is gone — and the PendingIntent form does not
     * exist before it. Both are therefore real branches, and the phone this is
     * for (Android 15) takes the first.
     */
    private fun collapseAndStart(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    // -------------------------------------------------------------- drawing

    private fun render() {
        val s = ScreenShareService.status
        draw(
            active = s.running,
            subtitle = if (s.running) s.sourceName else SUBTITLE_IDLE
        )
    }

    /**
     * The tile says what is true now, not what the next press will do — a tile
     * is lit or unlit like the torch beside it, and the shade's own convention
     * wins over this app's key-says-the-next-press rule (design-language.md §5,
     * which is about keys on our own screen).
     */
    private fun draw(active: Boolean, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_ndi)
        tile.label = getString(R.string.tile_label)
        // The subtitle is the source name while sending, because the one thing
        // an operator needs from a glance is which of the phones in the room is
        // the one on the network.
        tile.subtitle = subtitle
        tile.contentDescription =
            if (active) "Mantra NDI, sending as $subtitle" else "Mantra NDI, not sending"
        tile.updateTile()
    }

    companion object {
        private const val SUBTITLE_IDLE = "not sending"

        /**
         * Told from the service whenever the share starts or stops, so a shade
         * that is already open redraws instead of showing the state from before
         * the tap. Wrapped because the tile may not be on the shade at all, and
         * that is not a fault.
         */
        fun refresh(context: Context) {
            // NOT a bare runCatching. Swallowing the throwable here cost an hour:
            // the tile sat saying "not sending" over a live share and the only
            // evidence anywhere was the absence of a line in the trace. If this
            // cannot ask, it has to say so.
            try {
                requestListeningState(
                    context,
                    ComponentName(context, NdiTileService::class.java)
                )
                Trace.state("tile: asked the shade to redraw")
            } catch (t: Throwable) {
                Trace.fault("tile refresh", t)
            }
        }
    }
}
