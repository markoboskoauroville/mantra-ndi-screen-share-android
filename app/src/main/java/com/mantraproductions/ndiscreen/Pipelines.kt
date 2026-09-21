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
    }

    override fun stop() {
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
