package com.mantraproductions.ndiscreen

/**
 * The arithmetic of the screen share, with nothing from Android in it.
 *
 * android-app.md §1: the readers, the parsers and the arithmetic live in a file
 * that imports nothing from Android, so Test 1 runs on a desk in under a second
 * instead of waiting five minutes for a runner. `scripts/verify.py` fails the
 * build the day an `import android.` appears here.
 *
 * Everything that decides what leaves the phone is in this file: which way up
 * the screen is, what size the frame becomes, what it costs on the wire, and
 * whether a turn of the phone means the pipeline has to be rebuilt. The service
 * asks; it does not work any of it out itself.
 */
object Mechanism {

    // ---------------------------------------------------------------- modes

    /**
     * The two ways this app can put a screen on the network. Both are real NDI
     * and both appear in vMix, OBS and Studio Monitor as an ordinary source;
     * they differ in who does the compressing and what it costs.
     */
    enum class Mode {
        /**
         * NDI HX. The phone's hardware encoder makes H.264 or H.265 and the
         * bytes go out as an NDI compressed packet. Cheap on the battery,
         * cheap on the Wi-Fi, one to three frames of latency added by the
         * encoder's own queue.
         */
        COMPRESSED,

        /**
         * Full NDI, which NDI's own documentation calls High Bandwidth. The
         * phone hands whole uncompressed frames to the SDK and the SDK makes
         * SpeedHQ out of them. Every receiver ever made takes it, the latency
         * is a frame, and it costs roughly a bit per pixel per frame on the
         * wire — about a hundred times what HX costs.
         */
        FULL
    }

    /** Which way up the glass is. Decided from the numbers, never from a sensor. */
    enum class Orientation { PORTRAIT, LANDSCAPE, SQUARE }

    /**
     * How hard the encoder is asked to work in [Mode.COMPRESSED]. Screen
     * content is mostly flat colour and still text, so these are well under
     * what the same numbers would mean for a camera.
     */
    enum class Quality(val bitsPerPixelPerFrame: Double) {
        LOW(0.04),
        MEDIUM(0.08),
        HIGH(0.15)
    }

    // ------------------------------------------------------------ constants

    /** Below this the picture is not worth sending, and some encoders refuse it. */
    const val MIN_EDGE = 160

    /** No encoder on a phone is promised more than this on its long edge. */
    const val MAX_EDGE = 3840

    private const val MIN_BITRATE = 1_000_000
    private const val MAX_BITRATE = 40_000_000

    // ---------------------------------------------------------- orientation

    fun orientationOf(width: Int, height: Int): Orientation = when {
        width > height -> Orientation.LANDSCAPE
        height > width -> Orientation.PORTRAIT
        else -> Orientation.SQUARE
    }

    // --------------------------------------------------------------- sizing

    /**
     * The size the frame leaves at, for a screen of [sourceWidth] × [sourceHeight]
     * capped at [capLongEdge] on its longer side.
     *
     * Three things are true of the answer and each one has bitten somebody:
     *
     * - **The shape is kept.** A portrait phone streams a portrait picture. The
     *   old habit of forcing 16:9 by letterboxing put two black bars on a
     *   vertical screen and threw away half the pixels, which is the opposite
     *   of what a phone screen share is for.
     * - **Both numbers are even.** Every H.264 and H.265 profile on this phone
     *   is 4:2:0, and a 4:2:0 encoder handed an odd dimension either refuses
     *   the format outright or quietly shifts the chroma by half a pixel.
     * - **It never grows.** Capping at more than the screen has would upscale
     *   and send bigger frames carrying no more picture.
     */
    fun outputSize(sourceWidth: Int, sourceHeight: Int, capLongEdge: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "source is ${sourceWidth}x$sourceHeight, which is not a screen"
        }
        val cap = capLongEdge.coerceIn(MIN_EDGE, MAX_EDGE)
        val longEdge = maxOf(sourceWidth, sourceHeight)

        // Never upscale: a cap above the screen is the screen.
        val scale = if (longEdge <= cap) 1.0 else cap.toDouble() / longEdge

        var w = even((sourceWidth * scale).toInt())
        var h = even((sourceHeight * scale).toInt())

        // The floor applies per edge, because a very tall screen scaled to fit
        // a small cap can leave the short edge under it while the long edge
        // is fine.
        if (w < MIN_EDGE) w = MIN_EDGE
        if (h < MIN_EDGE) h = MIN_EDGE
        return w to h
    }

    /** The nearest even number at or below [n]. */
    fun even(n: Int): Int = n and 1.inv()

    // -------------------------------------------------------------- bitrate

    /**
     * The bitrate to ask the hardware encoder for, in bits per second.
     *
     * Asking for the number rather than a quality knob is deliberate:
     * MediaCodec's own quality settings are advisory and differ per vendor,
     * whereas `KEY_BIT_RATE` is honoured on every phone tried.
     */
    fun bitrateFor(width: Int, height: Int, fps: Int, quality: Quality): Int {
        require(fps > 0) { "fps is $fps" }
        val raw = width.toDouble() * height * fps * quality.bitsPerPixelPerFrame
        return raw.toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    /**
     * What full NDI costs on the wire, in bits per second, near enough to warn
     * with.
     *
     * NDI's own figure for 1080p60 High Bandwidth is about 125 Mbit/s, which
     * is a shade over one bit per pixel per frame, and that is the number used
     * here. It is an estimate and the interface says so — SpeedHQ is a real
     * codec and a still screen costs less than a moving one.
     */
    fun fullNdiBitsPerSecond(width: Int, height: Int, fps: Int): Long =
        width.toLong() * height * fps

    /** What the same frames cost before the SDK compresses them: RGBA, four bytes a pixel. */
    fun rawBytesPerSecond(width: Int, height: Int, fps: Int): Long =
        width.toLong() * height * 4 * fps

    // ------------------------------------------------------------- restarts

    /**
     * Whether a change of screen geometry means the capture has to be torn down
     * and built again.
     *
     * A virtual display is created at a fixed size and an encoder is configured
     * at a fixed size; neither can be resized in place. So a turn of the phone
     * is a rebuild — but only when the numbers actually change. Android reports
     * a rotation whenever the sensor crosses a boundary, including the two
     * boundaries that leave the geometry exactly as it was, and rebuilding on
     * those drops a second of picture for nothing.
     */
    fun needsRebuild(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): Boolean =
        oldWidth != newWidth || oldHeight != newHeight

    // ----------------------------------------------------------------- name

    /**
     * The NDI source name as it will appear in the receiver's list.
     *
     * NDI names travel in mDNS records, so a name with a control character, a
     * dot or a stray space in it either fails to advertise or advertises as
     * something nobody typed. Everything outside letters, digits, space, dash
     * and underscore becomes a dash; runs collapse; the ends are trimmed; and
     * an empty answer falls back rather than advertising a source with no name.
     */
    fun sourceName(typed: String, fallback: String = "Screen"): String {
        val cleaned = buildString {
            for (c in typed) {
                append(if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_') c else '-')
            }
        }
        val collapsed = cleaned.replace(Regex("[-\\s]{2,}"), " ").trim(' ', '-', '_')
        return if (collapsed.isEmpty()) fallback else collapsed.take(60)
    }

    // ---------------------------------------------------------------- plans

    /**
     * Everything the service needs to start, worked out in one place so the
     * whole decision can be tested on a desk and printed into the trace before
     * a single frame is captured.
     */
    data class Plan(
        val mode: Mode,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val orientation: Orientation,
        val bitrate: Int,
        val estimatedBitsPerSecond: Long
    ) {
        val isPortrait: Boolean get() = orientation == Orientation.PORTRAIT
    }

    fun plan(
        mode: Mode,
        sourceWidth: Int,
        sourceHeight: Int,
        capLongEdge: Int,
        fps: Int,
        quality: Quality
    ): Plan {
        val (w, h) = outputSize(sourceWidth, sourceHeight, capLongEdge)
        val bitrate = bitrateFor(w, h, fps, quality)
        val estimate =
            if (mode == Mode.COMPRESSED) bitrate.toLong() else fullNdiBitsPerSecond(w, h, fps)
        return Plan(
            mode = mode,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            width = w,
            height = h,
            fps = fps,
            orientation = orientationOf(w, h),
            bitrate = bitrate,
            estimatedBitsPerSecond = estimate
        )
    }

    // -------------------------------------------------------------- reading

    /** Bits per second as a person reads it. One decimal, because two is noise. */
    fun megabits(bitsPerSecond: Long): String {
        val mbit = bitsPerSecond / 1_000_000.0
        return when {
            mbit >= 100 -> "${mbit.toInt()} Mbit/s"
            mbit >= 1 -> "${round1(mbit)} Mbit/s"
            else -> "${(bitsPerSecond / 1000.0).toInt()} kbit/s"
        }
    }

    /**
     * The rate a counter has actually reached, from bytes and the time they
     * took. A window of zero or less is **no rate** rather than a division by
     * it — the first tick after a start always has one.
     */
    fun measuredBitsPerSecond(bytes: Long, elapsedMs: Long): Long =
        if (elapsedMs <= 0) 0 else bytes * 8 * 1000 / elapsedMs

    /**
     * One decimal place, rounded the way the number is read rather than the way
     * it is stored.
     *
     * The obvious version — round(v * 10) / 10 — is wrong for the commonest
     * case there is. 1.45 is not 1.45 in binary, it is 1.4499999999999999556,
     * so v * 10 is 14.499999999999998, and round takes it DOWN: a rate of
     * 1.45 Mbit/s was displayed as 1.4. Off by a tenth, always in the same
     * direction, and invisible unless someone does the arithmetic by hand.
     *
     * BigDecimal over the number's own decimal text, HALF_UP, is the only way
     * to round a decimal as a decimal. It also fixes a second thing the old
     * line had: for a negative v, scaled % 10 is negative too, and it printed
     * "-1.-5".
     */
    fun round1(v: Double): String =
        java.math.BigDecimal(v.toString())
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .toPlainString()

    /** `1080 × 1920 portrait` — the line the state panel shows. */
    fun describe(plan: Plan): String =
        "${plan.width} × ${plan.height} ${plan.orientation.name.lowercase()} @ ${plan.fps}"
}
