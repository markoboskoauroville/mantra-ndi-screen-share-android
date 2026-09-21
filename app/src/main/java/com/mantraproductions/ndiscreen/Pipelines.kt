package com.mantraproductions.ndiscreen

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The two ways a captured screen becomes NDI.
 *
 * Both present the same face to the service: a [Surface] for the virtual
 * display to draw into, a [start], a [stop], and a running count of the bytes
 * that actually left. The service does not know which one it is holding, which
 * is the point — a mode is a choice made once when the share starts, not a
 * branch threaded through the capture code.
 */
interface Pipeline {
    /** What the virtual display draws into. Valid only between [start] and [stop]. */
    val surface: Surface

    fun start()
    fun stop()

    /** Bytes handed to NDI since the pipeline started. */
    val bytesSent: Long

    /** Frames handed to NDI since the pipeline started. */
    val framesSent: Long
}

/**
 * NDI HX: the phone's hardware encoder makes H.264 or H.265 and the packets go
 * out compressed.
 *
 * The virtual display draws straight into the encoder's input surface, so no
 * pixel is ever read back into the app's memory. At 1080p30 that is the
 * difference between a few megabits a second of work and a quarter of a
 * gigabyte a second of memory copying.
 */
class CompressedPipeline(
    private val plan: Mechanism.Plan,
    private val useHevc: Boolean,
    private val onFault: (String) -> Unit
) : Pipeline {

    private val thread = HandlerThread("ndi-encoder")
    private var codec: MediaCodec? = null
    private var input: Surface? = null

    @Volatile
    override var bytesSent: Long = 0
        private set

    @Volatile
    override var framesSent: Long = 0
        private set

    private var startedAtUs = 0L

    override val surface: Surface
        get() = input ?: error("CompressedPipeline.surface read before start()")

    val mime: String =
        if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /**
     * The last keyframe, kept so that a still screen is still a stream.
     *
     * KEY_REPEAT_PREVIOUS_FRAME_AFTER is meant to make this unnecessary: a
     * virtual display draws only when something changes, so the encoder is
     * asked to repeat its last frame when nothing arrives within a frame
     * period. On the emulator's software encoder, c2.android.avc.encoder, it
     * does nothing whatever — MEASURED 21.9.2026, a still screen produced
     * **0 frames in 10 seconds** with the counter stuck at 51. Marko's own
     * report was the same shape from a real Pixel 7: a stopwatch went out at
     * 6.3 fps where an NDI HX Camera on the same phone managed 29.2.
     *
     * So this is a floor under that hint, not a replacement for it, and it is
     * deliberately a slow floor. If the encoder does repeat, the heartbeat
     * never fires at all, because its only condition is that nothing has gone
     * out for a whole second.
     *
     * It does NOT make a still screen arrive at 30 fps, and it is not meant to.
     * A screen source sends what the screen draws; a camera sends 30 because a
     * sensor produces 30. What this guarantees is only that the stream never
     * looks stalled.
     *
     * A KEYFRAME is what is repeated, never a P-frame. An IDR is a whole
     * picture and decodes to the same thing however many times it is sent; a
     * repeated P-frame would advance a decoder's state against a picture that
     * never changed, and the image would drift away from the truth.
     */
    @Volatile private var lastKeyframe: ByteArray? = null
    @Volatile private var lastSentAtMs = 0L
    private var heartbeat: Handler? = null

    /** How many frames the screen did not produce. Said in the trace on stop. */
    @Volatile
    var repeated: Long = 0
        private set

    /**
     * How long a stream may be silent before a frame is repeated: one second.
     *
     * NOT one frame period, and the difference is measured. Repeating at the
     * full rate gave a still screen 24.2 fps and **20.1 Mbit/s against a 4.0
     * Mbit/s budget** — five times over, because what is being repeated is a
     * whole keyframe of about 105 KB. A screen share that costs five times its
     * own budget while nothing is happening is worse than the problem it fixes.
     *
     * One second keeps a receiver's clock moving, which is the thing that
     * matters: a stream whose timestamps have stopped is read as stalled and
     * eventually dropped. It costs about 0.8 Mbit/s on a completely still
     * screen and exactly nothing on a moving one.
     */
    private val quietMs = 1000L

    /** How often the silence is checked. */
    private val checkMs = 250L

    override fun start() {
        thread.start()

        val format = MediaFormat.createVideoFormat(mime, plan.width, plan.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            // One second. A receiver that joins mid-share waits at most that
            // long for a picture, and on screen content a keyframe is cheap
            // because most of the frame is flat.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // THE LINE THAT MAKES A STILL SCREEN WORK. A virtual display
            // produces a frame only when something changes, so a phone sitting
            // on a menu hands the encoder nothing and the encoder emits
            // nothing — the receiver shows the last thing it got and then
            // times out. This asks the encoder to repeat the previous frame
            // when none has arrived within one frame period, which is exactly
            // what it was added to MediaFormat for.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / plan.fps)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }

        val c = MediaCodec.createEncoderByType(mime)
        // setCallback before configure, in that order. The other way round is
        // an IllegalStateException on some vendors and silence on others.
        c.setCallback(callback, Handler(thread.looper))
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = c.createInputSurface()
        startedAtUs = SystemClock.elapsedRealtimeNanos() / 1000
        c.start()
        codec = c

        NdiSender.setVideoFormat(plan.width, plan.height, plan.fps, 1)
        Trace.step("encoder started: $mime ${plan.width}x${plan.height} @${plan.fps} " +
            "${Mechanism.megabits(plan.bitrate.toLong())}")

        lastSentAtMs = SystemClock.elapsedRealtime()
        heartbeat = Handler(thread.looper).also { it.post(tick) }
    }

    /**
     * Sends the last keyframe again when the encoder has gone quiet.
     *
     * The gap has to be longer than one period, or a stream running exactly at
     * rate would race this and slip a duplicate in between every pair of real
     * frames. One and a half periods is clear of both.
     */
    private val tick = object : Runnable {
        override fun run() {
            val key = lastKeyframe
            val quiet = SystemClock.elapsedRealtime() - lastSentAtMs
            if (key != null && quiet >= quietMs) {
                // The presentation time must keep moving. A stream whose clock
                // has stopped is read by a receiver as a stalled stream, which
                // is the very thing this exists to prevent.
                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000 - startedAtUs
                NdiSender.sendCompressed(key, isKeyframe = true, ptsUs = ptsUs, isHevc = useHevc)
                bytesSent += key.size
                framesSent++
                lastSentAtMs = SystemClock.elapsedRealtime()
                repeated++
            }
            heartbeat?.postDelayed(this, checkMs)
        }
    }

    override fun stop() {
        heartbeat?.removeCallbacksAndMessages(null)
        heartbeat = null
        if (repeated > 0) Trace.state("still-screen frames repeated: $repeated")
        lastKeyframe = null
        val c = codec
        codec = null
        try {
            c?.stop()
        } catch (t: Throwable) {
            Trace.fault("encoder stop", t)
        }
        try {
            c?.release()
        } catch (t: Throwable) {
            Trace.fault("encoder release", t)
        }
        input?.release()
        input = null
        thread.quitSafely()
        // The parameter sets describe a picture size that no longer exists.
        NdiSender.clearVideoInfo()
    }

    private val callback = object : MediaCodec.Callback() {

        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            // Nothing: the input is a Surface, so the display fills it.
        }

        override fun onOutputBufferAvailable(
            c: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                val buffer = c.getOutputBuffer(index)
                if (buffer == null) {
                    c.releaseOutputBuffer(index, false)
                    return
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS arriving as a buffer rather than in the format.
                    // Both routes happen depending on the vendor, so both are
                    // handled; whichever arrives first wins and the other is
                    // identical.
                    val csd = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(csd)
                    NdiSender.setVideoInfo(csd, null, null)
                    Trace.state("parameter sets from buffer, ${csd.size} bytes")
                    c.releaseOutputBuffer(index, false)
                    return
                }

                if (info.size > 0) {
                    val frame = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(frame)
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    NdiSender.sendCompressed(
                        data = frame,
                        isKeyframe = keyframe,
                        ptsUs = info.presentationTimeUs,
                        isHevc = useHevc
                    )
                    bytesSent += frame.size
                    framesSent++
                    lastSentAtMs = SystemClock.elapsedRealtime()
                    if (keyframe) lastKeyframe = frame
                }
                c.releaseOutputBuffer(index, false)
            } catch (t: Throwable) {
                // Throwable, not Exception: MediaCodec in a bad state throws
                // IllegalStateException, but a codec the system tore down under
                // us has been seen to arrive as an Error.
                Trace.fault("encoder output", t)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            val what = "encoder: ${e.diagnosticInfo} " +
                "(recoverable=${e.isRecoverable}, transient=${e.isTransient})"
            Trace.refused("encoder", what)
            onFault(what)
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            val sps = format.getByteBuffer("csd-0")?.let { copyOf(it) }
            val pps = format.getByteBuffer("csd-1")?.let { copyOf(it) }
            if (sps != null) {
                // For H.265 csd-0 already holds VPS, SPS and PPS together, so
                // it goes in as the one block; for H.264 csd-0 is the SPS and
                // csd-1 the PPS.
                NdiSender.setVideoInfo(sps, pps, null)
                Trace.state(
                    "parameter sets from format, ${sps.size} + ${pps?.size ?: 0} bytes"
                )
            }
        }
    }

    private fun copyOf(b: ByteBuffer): ByteArray {
        val out = ByteArray(b.remaining())
        b.get(out)
        b.rewind()
        return out
    }
}

/**
 * Full NDI: whole uncompressed frames, compressed to SpeedHQ by the SDK itself.
 *
 * The virtual display draws into an [ImageReader], the reader's own direct
 * buffer is handed to NDI without a copy, and the SDK does the work. Every
 * receiver ever built takes this, at about a hundred times the bandwidth of HX.
 *
 * A still screen is handled here rather than by the encoder, because there is
 * no encoder: the last frame is held open and sent again on the tick whenever
 * the display has produced nothing. That is why the reader is given four
 * buffers — one can be held indefinitely without starving the display.
 */
class FullPipeline(
    private val plan: Mechanism.Plan
) : Pipeline {

    private val thread = HandlerThread("ndi-frames")
    private var reader: ImageReader? = null
    private var handler: Handler? = null

    private val lock = Any()
    private var held: Image? = null
    private var lastSendAt = 0L
    private var startedAtUs = 0L

    private val intervalMs: Long = (1000L / plan.fps).coerceAtLeast(1L)

    @Volatile
    override var bytesSent: Long = 0
        private set

    @Volatile
    override var framesSent: Long = 0
        private set

    override val surface: Surface
        get() = reader?.surface ?: error("FullPipeline.surface read before start()")

    override fun start() {
        thread.start()
        val h = Handler(thread.looper)
        handler = h

        // Four, not two. One is held as the repeat frame, one is being drawn
        // into, and the two spare stop a slow tick from stalling the display.
        val r = ImageReader.newInstance(plan.width, plan.height, PixelFormat.RGBA_8888, 4)
        r.setOnImageAvailableListener({ onImage(it) }, h)
        reader = r

        startedAtUs = SystemClock.elapsedRealtimeNanos() / 1000
        NdiSender.setVideoFormat(plan.width, plan.height, plan.fps, 1)
        h.postDelayed(tick, intervalMs)
        Trace.step(
            "full NDI started: ${plan.width}x${plan.height} @${plan.fps}, " +
                "estimated ${Mechanism.megabits(plan.estimatedBitsPerSecond)}"
        )
    }

    override fun stop() {
        handler?.removeCallbacks(tick)
        synchronized(lock) {
            held?.close()
            held = null
        }
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        handler = null
        thread.quitSafely()
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Trace.fault("acquireLatestImage", t)
            null
        } ?: return

        synchronized(lock) {
            send(image)
            held?.close()
            held = image
        }
    }

    /**
     * Sends the same frame again when the display has produced nothing.
     *
     * NDI is a live source: a receiver that stops being fed decides the source
     * has gone. A phone showing a static menu produces no frames at all, so
     * without this the share dies whenever nobody touches the screen — which
     * is most of the time somebody is looking at it.
     */
    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val since = SystemClock.elapsedRealtime() - lastSendAt
                if (since >= intervalMs) held?.let { send(it) }
            }
            handler?.postDelayed(this, intervalMs)
        }
    }

    /** Caller holds [lock]. */
    private fun send(image: Image) {
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000 - startedAtUs
            NdiSender.sendRaw(
                buffer = buffer,
                width = plan.width,
                height = plan.height,
                stride = plane.rowStride,
                ptsUs = ptsUs
            )
            bytesSent += plane.rowStride.toLong() * plan.height
            framesSent++
            lastSendAt = SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            Trace.fault("full send", t)
        }
    }
}
