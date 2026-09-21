package com.mantraproductions.ndiscreen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RadioButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mantraproductions.ndiscreen.databinding.ActivityMainBinding

/**
 * The one screen.
 *
 * It does three things and nothing else: it remembers what was chosen, it asks
 * the system for permission to see the screen, and it reads the service's state
 * back out loud. No capture code lives here — an activity can be destroyed by a
 * turn of the phone and the share must not be.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var settings: Settings
    private val poll = Handler(Looper.getMainLooper())

    private lateinit var askForScreen: ActivityResultLauncher<Intent>
    private lateinit var askForNotifications: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        settings = Settings(this)

        applyInsets()
        ui.version.text = "v${BuildConfig.VERSION_NAME}"

        askForScreen = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Trace.step("screen capture granted by the person")
                startService(
                    Intent(this, ScreenShareService::class.java)
                        .setAction(ScreenShareService.ACTION_START)
                        .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                )
            } else {
                Trace.refused("screen capture", "the person said no")
                ui.faultLine.text = "Screen capture was refused, so nothing is being sent."
            }
        }

        askForNotifications = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Trace.state("notifications permission = $granted")
            // Refusing it does not stop the share. Android still runs the
            // foreground service; the person just does not see its notice, and
            // the only way to stop it is then this screen. Said here rather
            // than argued about with a second dialog.
            if (!granted) {
                ui.faultLine.text =
                    "Without the notification you can only stop the share from this screen."
            }
        }

        wireControls()
        loadSettings()
    }

    override fun onStart() {
        super.onStart()
        ScreenShareService.onStatus = { render(it) }
        render(ScreenShareService.status)
        poll.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ScreenShareService.onStatus = null
        poll.removeCallbacks(tick)
    }

    // ------------------------------------------------------------- the bars

    /**
     * design-language.md: from targetSdk 35 Android draws every app edge to
     * edge and stops insetting it. Nothing that can be pressed or read may sit
     * under the status bar or the gesture bar, so the content takes the safe
     * drawing insets itself. This is the half that gets forgotten.
     */
    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(ui.content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    // -------------------------------------------------------------- reading

    private val tick = object : Runnable {
        override fun run() {
            showTrace()
            poll.postDelayed(this, 1000L)
        }
    }

    private fun showTrace() {
        val lines = Trace.lines()
        val text = lines.takeLast(200).joinToString("\n")
        if (ui.trace.text.toString() != text) {
            ui.trace.text = text
            ui.traceScroll.post { ui.traceScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // -------------------------------------------------------------- wiring

    private fun wireControls() {
        ui.close.setOnClickListener { finish() }

        ui.mode.setOnCheckedChangeListener { _, id ->
            settings.mode =
                if (id == ui.modeFull.id) Mechanism.Mode.FULL else Mechanism.Mode.COMPRESSED
            refreshDerived()
        }
        ui.codec.setOnCheckedChangeListener { _, id ->
            settings.useHevc = id == ui.codecH265.id
            refreshDerived()
        }
        ui.quality.setOnCheckedChangeListener { _, id ->
            settings.quality = when (id) {
                ui.qualityLow.id -> Mechanism.Quality.LOW
                ui.qualityHigh.id -> Mechanism.Quality.HIGH
                else -> Mechanism.Quality.MEDIUM
            }
            refreshDerived()
        }
        ui.cap.setOnCheckedChangeListener { _, id ->
            settings.capLongEdge = when (id) {
                ui.cap720.id -> 720
                ui.cap1280.id -> 1280
                ui.cap2560.id -> 2560
                else -> 1920
            }
            refreshDerived()
        }
        ui.fps.setOnCheckedChangeListener { _, id ->
            settings.fps = when (id) {
                ui.fps15.id -> 15
                ui.fps24.id -> 24
                ui.fps60.id -> 60
                else -> 30
            }
            refreshDerived()
        }

        ui.sourceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitName()
        }

        ui.share.setOnClickListener {
            if (ScreenShareService.status.running) stopShare() else startShare()
        }

        ui.exportTrace.setOnClickListener {
            val where = Trace.export(this)
            ui.faultLine.text =
                if (where != null) "Trace written to Downloads/$where"
                else "The trace could not be written to Downloads."
        }
    }

    private fun commitName() {
        val cleaned = Mechanism.sourceName(
            ui.sourceName.text.toString(),
            settings.defaultSourceName()
        )
        settings.sourceName = cleaned
        if (ui.sourceName.text.toString() != cleaned) ui.sourceName.setText(cleaned)
        refreshDerived()
    }

    private fun loadSettings() {
        ui.sourceName.setText(settings.sourceName)
        check(ui.modeCompressed, ui.modeFull, settings.mode == Mechanism.Mode.COMPRESSED)
        check(ui.codecH264, ui.codecH265, !settings.useHevc)
        when (settings.quality) {
            Mechanism.Quality.LOW -> ui.qualityLow.isChecked = true
            Mechanism.Quality.MEDIUM -> ui.qualityMedium.isChecked = true
            Mechanism.Quality.HIGH -> ui.qualityHigh.isChecked = true
        }
        when (settings.capLongEdge) {
            720 -> ui.cap720.isChecked = true
            1280 -> ui.cap1280.isChecked = true
            2560 -> ui.cap2560.isChecked = true
            else -> ui.cap1920.isChecked = true
        }
        when (settings.fps) {
            15 -> ui.fps15.isChecked = true
            24 -> ui.fps24.isChecked = true
            60 -> ui.fps60.isChecked = true
            else -> ui.fps30.isChecked = true
        }
        refreshDerived()
    }

    private fun check(a: RadioButton, b: RadioButton, first: Boolean) {
        a.isChecked = first
        b.isChecked = !first
    }

    // ------------------------------------------------------------- derived

    /**
     * Everything on the screen that follows from a choice rather than being one.
     *
     * design-language.md §1: the codec and quality rows do not leave in full
     * NDI, because a row that leaves takes the rows under it up the screen.
     * They go inactive — an alpha and a listener, neither of which touches
     * layout, so the panel below stays exactly where the eye left it.
     */
    private fun refreshDerived() {
        val compressed = settings.mode == Mechanism.Mode.COMPRESSED
        setActive(compressed, ui.codecLabel, ui.codec, ui.qualityLabel, ui.quality)

        ui.modeNote.text = if (compressed) {
            "NDI HX. The phone's own encoder does the work: light on the battery " +
                "and light on the Wi-Fi. vMix, OBS and Studio Monitor all take it."
        } else {
            "Full NDI, the High Bandwidth kind. The SDK compresses whole frames " +
                "to SpeedHQ on this phone — every receiver ever made takes it, at " +
                "roughly a hundred times the bandwidth."
        }

        val (w, h) = screenSize()
        val plan = Mechanism.plan(
            settings.mode, w, h, settings.capLongEdge, settings.fps, settings.quality
        )
        val about = Mechanism.megabits(plan.estimatedBitsPerSecond)
        ui.estimate.text =
            "${Mechanism.describe(plan)}   about $about" +
                if (!compressed) "  (an estimate — a still screen costs less)" else ""
    }

    private fun setActive(active: Boolean, vararg views: View) {
        for (v in views) {
            v.alpha = if (active) 1f else 0.3f
            v.isEnabled = active
            setChildrenEnabled(v, active)
        }
    }

    private fun setChildrenEnabled(v: View, enabled: Boolean) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i).isEnabled = enabled
                setChildrenEnabled(v.getChildAt(i), enabled)
            }
        }
    }

    /** The whole glass, so the estimate matches what the service will actually capture. */
    private fun screenSize(): Pair<Int, Int> {
        val bounds = windowManager.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    // ------------------------------------------------------- start and stop

    private fun startShare() {
        commitName()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!NdiSender.available) {
            ui.faultLine.text =
                "This build has no NDI SDK in it, so nothing can be sent. See the README."
            Trace.refused("start", "NDI library not present in the APK")
            return
        }

        ui.faultLine.text = ""
        val manager = getSystemService(MediaProjectionManager::class.java)
        askForScreen.launch(manager.createScreenCaptureIntent())
    }

    private fun stopShare() {
        startService(
            Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_STOP)
        )
    }

    // ------------------------------------------------------------ the state

    private fun render(s: ScreenShareService.Status) {
        // design-language.md §5: the key says what the NEXT press does.
        ui.share.text = if (s.running) "STOP" else "START"

        ui.stateLine.text = if (s.running) {
            "sending   \"${s.sourceName}\""
        } else {
            "idle      \"${settings.sourceName}\""
        }

        ui.sizeLine.text = if (s.running) {
            "${s.width} × ${s.height} ${s.orientation.name.lowercase()} @ ${s.fps}"
        } else {
            "— nothing on the network"
        }

        ui.rateLine.text = if (s.running) {
            "${Mechanism.megabits(s.measuredBitsPerSecond)}   ${s.framesSent} frames"
        } else {
            "—"
        }

        // The only honest answer to "is anybody seeing this?". A source
        // advertises whether or not anyone is watching.
        ui.watchersLine.text = when {
            !s.running -> "—"
            s.connections < 0 -> "receivers: not known"
            s.connections == 0 -> "receivers: none yet"
            s.connections == 1 -> "receivers: 1"
            else -> "receivers: ${s.connections}"
        }
        ui.watchersLine.setTextColor(
            if (s.running && s.connections > 0) getColor(R.color.amber) else getColor(R.color.sand)
        )

        // Tally is red because it is red in every gallery Marko has worked in,
        // and a tally light that is not red is not a tally light. It is the one
        // place red does not mean a fault, and it is the only one.
        if (s.running && s.tally and 1 != 0) {
            ui.watchersLine.text = "${ui.watchersLine.text}   ON PROGRAM"
            ui.watchersLine.setTextColor(getColor(R.color.red))
        }

        ui.addressLine.text = if (s.address.isEmpty()) {
            "this phone has no address on any network"
        } else {
            "this phone is ${s.address}"
        }

        ui.faultLine.text = s.fault

        // The choices are the share's, not the screen's, while it is running:
        // changing them mid-share would describe a stream that is not the one
        // going out.
        setActive(!s.running, ui.sourceName, ui.mode, ui.cap, ui.fps)
        if (!s.running) {
            refreshDerived()
        } else {
            setActive(false, ui.codecLabel, ui.codec, ui.qualityLabel, ui.quality)
            // While a share is running the estimate must follow the share, not
            // the last time somebody touched a control. It was computed from
            // screenSize() when the screen was last built, so after the phone
            // was turned it sat there describing the shape that is no longer
            // being sent - disagreeing with the live line directly beneath it.
            ui.estimate.text =
                "${s.width} × ${s.height} ${s.orientation.name.lowercase()} @ ${s.fps}" +
                    "   ${Mechanism.megabits(s.measuredBitsPerSecond)} now"
        }
    }
}
