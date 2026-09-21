package com.mantraproductions.ndiscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The screen on the network, and everything that has to stay true while it is.
 *
 * The service owns four things that have to be created and destroyed in order,
 * and almost every way this app can fail is one of them being out of step:
 *
 *   the foreground notification   before the projection exists, on 14 and up
 *   the multicast lock           before the sender, or nobody discovers it
 *   the NDI sender               once, and kept across a turn of the phone
 *   the capture pipeline         rebuilt whenever the geometry changes
 *
 * The sender outliving the rebuild is deliberate. A receiver watches a *source
 * name*; destroying and recreating the sender when the phone is turned drops
 * the source out of every list on the network and the operator has to find it
 * and click it again. Keeping it and changing only the format means a turn of
 * the phone is a resolution change, which is a thing NDI carries natively and
 * every receiver already handles.
 */
class ScreenShareService : Service() {

    // ---------------------------------------------------------------- state

    /**
     * What the interface reads.
     *
     * A plain immutable snapshot swapped in one write, rather than a set of
     * fields the activity reads one at a time — reading five fields off a
     * running service gets you four from one moment and one from the next, and
     * the line that results has been seen to say "stopped, 30 fps".
     */
    data class Status(
        val running: Boolean = false,
        val mode: Mechanism.Mode = Mechanism.Mode.COMPRESSED,
        val sourceName: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val orientation: Mechanism.Orientation = Mechanism.Orientation.PORTRAIT,
        val measuredBitsPerSecond: Long = 0,
        val framesSent: Long = 0,
        val connections: Int = -1,
        val tally: Int = -1,
        val address: String = "",
        val fault: String = ""
    )

    companion object {
        const val ACTION_START = "com.mantraproductions.ndiscreen.START"
        const val ACTION_STOP = "com.mantraproductions.ndiscreen.STOP"

        const val EXTRA_RESULT_CODE = "result-code"
        const val EXTRA_RESULT_DATA = "result-data"

        private const val CHANNEL_ID = "screen-share"
        private const val NOTIFICATION_ID = 1

        /**
         * Comfortably under the Advanced SDK's documented thirty-minute cap for
         * a sender with no registered vendor ID. Without this the share dies
         * silently after half an hour: the source disappears from every list
         * and the phone goes on believing it is sending.
         */
        private const val REFRESH_INTERVAL_MS = 25L * 60L * 1000L

        /** How often the readouts are recomputed. Slow enough to read, fast enough to trust. */
        private const val POLL_MS = 1000L

        @Volatile
        var status: Status = Status()
            private set

        /** Set by the activity while it is on screen; cleared when it is not. */
        @Volatile
        var onStatus: ((Status) -> Unit)? = null

        private fun publish(s: Status) {
            status = s
            val listener = onStatus
            if (listener != null) Handler(Looper.getMainLooper()).post { listener(s) }
        }
    }

    // --------------------------------------------------------------- fields

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var pipeline: Pipeline? = null
    private var plan: Mechanism.Plan? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sourceName = ""
    private var useHevc = false
    private var capLongEdge = 1920
    private var requestedFps = 30
    private var quality = Mechanism.Quality.MEDIUM
    private var mode = Mechanism.Mode.COMPRESSED

    private var lastBytes = 0L
    private var lastPollAt = 0L
    private var measured = 0L
    private var fault = ""

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Trace.step("stop asked for")
                stopEverything()
                stopSelf()
            }
            ACTION_START -> start(intent)
            else -> Trace.state("service started with no action, ignoring")
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) {
            Trace.state("already sharing, ignoring a second start")
            return
        }

        val settings = Settings(this)
        sourceName = settings.sourceName
        mode = settings.mode
        quality = settings.quality
        capLongEdge = settings.capLongEdge
        requestedFps = settings.fps
        useHevc = settings.useHevc
        fault = ""

        // On 14 and up the foreground service of type mediaProjection must
        // already be running before the projection is created. The other order
        // throws SecurityException at getMediaProjection, and the message does
        // not say which of the two calls was out of place.
        startForegroundNotice()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            failAndStop("the system gave no permission back; nothing was shared")
            return
        }

        if (!NdiSender.available) {
            failAndStop("built without the NDI SDK, so nothing can be sent. See README.")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Trace.fault("getMediaProjection", t)
            null
        }
        if (p == null) {
            failAndStop("the screen capture permission was refused or has expired")
            return
        }
        p.registerCallback(projectionCallback, main)
        projection = p
        Trace.step("media projection granted")

        acquireLocks()

        if (!NdiSender.create(sourceName)) {
            failAndStop("NDI would not open a source called \"$sourceName\"")
            return
        }
        Trace.step("NDI source open: $sourceName")

        if (!buildPipeline()) return

        displayManager.registerDisplayListener(displayListener, main)
        lastBytes = 0
        lastPollAt = SystemClock.elapsedRealtime()
        main.postDelayed(poll, POLL_MS)
        main.postDelayed(refresh, REFRESH_INTERVAL_MS)
        publishStatus()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------- pipeline

    /**
     * Builds the virtual display and whichever pipeline the mode asks for, at
     * whatever size the screen is right now. Answers false and stops the
     * service if it cannot.
     */
    private fun buildPipeline(): Boolean {
        val (sw, sh) = screenSize()
        val p = Mechanism.plan(mode, sw, sh, capLongEdge, requestedFps, quality)
        plan = p
        Trace.state(
            "plan: ${p.mode} ${Mechanism.describe(p)} from ${sw}x$sh, " +
                "about ${Mechanism.megabits(p.estimatedBitsPerSecond)}"
        )

        val built = try {
            when (p.mode) {
                Mechanism.Mode.COMPRESSED -> CompressedPipeline(p, useHevc) { onPipelineFault(it) }
                Mechanism.Mode.FULL -> FullPipeline(p)
            }.also { it.start() }
        } catch (t: Throwable) {
            Trace.fault("pipeline start", t)
            failAndStop("this phone would not start a ${p.mode.name.lowercase()} pipeline " +
                "at ${p.width}×${p.height}: ${Trace.describe(t)}")
            return false
        }
        pipeline = built

        display = try {
            projection?.createVirtualDisplay(
                "mantra-ndi-screen",
                p.width,
                p.height,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                built.surface,
                null,
                main
            )
        } catch (t: Throwable) {
            Trace.fault("createVirtualDisplay", t)
            null
        }

        if (display == null) {
            built.stop()
            pipeline = null
            failAndStop("the virtual display would not open at ${p.width}×${p.height}")
            return false
        }

        Trace.step("virtual display ${p.width}x${p.height} at ${densityDpi()} dpi")
        return true
    }

    private fun tearDownPipeline() {
        display?.release()
        display = null
        pipeline?.stop()
        pipeline = null
    }

    /**
     * The phone was turned, or the screen's geometry changed under us.
     *
     * Only a change in the *numbers* rebuilds. Android reports a rotation on
     * every ninety degrees, including the two that leave a square-to-the-screen
     * geometry exactly as it was, and rebuilding on those costs a second of
     * black picture for no reason.
     */
    private fun onGeometryChanged() {
        val current = plan ?: return
        val (sw, sh) = screenSize()
        val next = Mechanism.outputSize(sw, sh, capLongEdge)
        if (!Mechanism.needsRebuild(current.width, current.height, next.first, next.second)) return

        Trace.step(
            "screen turned: ${current.width}x${current.height} " +
                "-> ${next.first}x${next.second}, rebuilding"
        )
        tearDownPipeline()
        // The NDI sender is deliberately NOT recreated. The source keeps its
        // name and its place in every receiver's list; only the format changes,
        // which NDI carries and receivers already handle.
        buildPipeline()
        publishStatus()
    }

    private fun onPipelineFault(what: String) {
        fault = what
        publishStatus()
    }

    // ---------------------------------------------------------------- locks

    private fun acquireLocks() {
        // NDI discovery is mDNS over UDP multicast, and Android drops multicast
        // packets on Wi-Fi unless something holds this lock. Without it the
        // source sends perfectly well and no receiver ever finds it, which
        // reads as "NDI does not work" rather than as a missing lock.
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("ndi-screen-mcast")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        Trace.state("multicast lock ${if (multicastLock?.isHeld == true) "held" else "NOT held"}")

        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ndiscreen:share")?.apply {
            setReferenceCounted(false)
            acquire(4L * 60L * 60L * 1000L)
        }
    }

    private fun releaseLocks() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ----------------------------------------------------------------- stop

    private fun stopEverything() {
        main.removeCallbacks(poll)
        main.removeCallbacks(refresh)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }

        tearDownPipeline()

        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null

        if (NdiSender.available) NdiSender.destroy()
        releaseLocks()

        plan = null
        publish(Status(running = false, fault = fault, address = address()))
        stopForeground(STOP_FOREGROUND_REMOVE)
        Trace.step("share stopped")
    }

    private fun failAndStop(why: String) {
        Trace.refused("share", why)
        fault = why
        publish(Status(running = false, fault = why, address = address()))
        stopEverything()
        stopSelf()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The person pressed the system's own "Stop sharing", or another
            // app took the projection. This is the only notice we get.
            Trace.step("the system stopped the projection")
            stopEverything()
            stopSelf()
        }
    }

    // ------------------------------------------------------------- watchers

    private val displayManager: DisplayManager
        get() = getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onGeometryChanged()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val p = pipeline
            if (p != null) {
                val now = SystemClock.elapsedRealtime()
                val bytes = p.bytesSent
                measured = Mechanism.measuredBitsPerSecond(bytes - lastBytes, now - lastPollAt)
                lastBytes = bytes
                lastPollAt = now
                publishStatus()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Reopens the NDI source before the SDK closes it on its own.
     *
     * The Advanced SDK stops a sender after thirty minutes when no vendor ID
     * is registered. It does not report this; the source simply leaves the
     * network. Reopening on the same name every twenty-five minutes keeps the
     * share alive through a long session, and costs the receiver a reconnect
     * it does on its own.
     */
    private val refresh = object : Runnable {
        override fun run() {
            if (projection != null) {
                Trace.step("reopening the NDI source before the SDK's own timeout")
                NdiSender.create(sourceName)
                plan?.let { NdiSender.setVideoFormat(it.width, it.height, it.fps, 1) }
                main.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun publishStatus() {
        val p = plan
        val pipe = pipeline
        publish(
            Status(
                running = projection != null && pipe != null,
                mode = p?.mode ?: mode,
                sourceName = sourceName,
                width = p?.width ?: 0,
                height = p?.height ?: 0,
                fps = p?.fps ?: 0,
                orientation = p?.orientation ?: Mechanism.Orientation.PORTRAIT,
                measuredBitsPerSecond = measured,
                framesSent = pipe?.framesSent ?: 0,
                connections = NdiSender.connections(0),
                tally = NdiSender.tally(0),
                address = address(),
                fault = fault
            )
        )
    }

    // -------------------------------------------------------------- screen

    /**
     * The whole glass, in pixels, including what the system bars are drawn over.
     *
     * `maximumWindowMetrics` rather than the activity's own window: a service
     * has no window, and what is being mirrored is the display, not this app.
     */
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun densityDpi(): Int = resources.configuration.densityDpi

    /**
     * The phone's address on the network it is actually on.
     *
     * Shown because "the source is not in my list" is nearly always two
     * machines on two networks, and the phone is the one nobody can check.
     * Enumerating the interfaces needs no permission; asking the WifiManager
     * for the same number does.
     */
    private fun address(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: ""
    } catch (t: Throwable) {
        ""
    }

    // -------------------------------------------------------- notification

    private fun startForegroundNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen share",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing the screen over NDI")
            .setContentText(sourceName)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop)
                    .build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
