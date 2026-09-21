package com.mantraproductions.ndiscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 1 of the four: the mechanism, alone.
 *
 * four-tests.md: this one closes *the logic is wrong*. It runs with no phone,
 * no emulator and no Android SDK, because [Mechanism] imports nothing from
 * Android — `scripts/verify.py` fails the build the day that stops being true.
 *
 * Before writing each case, the question was the one that matters: what could
 * be true that would make this pass and the feature still be broken? The
 * answers are written above the cases that close them.
 */
class MechanismTest {

    // --- orientation -------------------------------------------------------

    @Test
    fun `a taller screen is portrait`() {
        assertEquals(Mechanism.Orientation.PORTRAIT, Mechanism.orientationOf(1080, 2400))
    }

    @Test
    fun `a wider screen is landscape`() {
        assertEquals(Mechanism.Orientation.LANDSCAPE, Mechanism.orientationOf(2400, 1080))
    }

    @Test
    fun `a square screen is neither`() {
        assertEquals(Mechanism.Orientation.SQUARE, Mechanism.orientationOf(1080, 1080))
    }

    // --- sizing ------------------------------------------------------------

    /**
     * The whole point of the app. A portrait phone must not be squeezed into a
     * landscape frame, which is what every "make it 1920×1080" shortcut does.
     */
    @Test
    fun `a portrait screen stays portrait`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 1920)
        assertTrue("got ${w}x$h", h > w)
        assertEquals(Mechanism.Orientation.PORTRAIT, Mechanism.orientationOf(w, h))
    }

    @Test
    fun `a landscape screen stays landscape`() {
        val (w, h) = Mechanism.outputSize(2400, 1080, 1920)
        assertTrue("got ${w}x$h", w > h)
    }

    @Test
    fun `the long edge is capped`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 1920)
        assertEquals(1920, maxOf(w, h))
    }

    /**
     * A test that only ever checked the long edge would pass on a squeeze. The
     * shape is what has to survive, so the ratio is the assertion.
     */
    @Test
    fun `the aspect ratio survives the cap`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 1920)
        val before = 1080.0 / 2400.0
        val after = w.toDouble() / h
        assertTrue("$before vs $after", kotlin.math.abs(before - after) < 0.01)
    }

    @Test
    fun `an odd screen still yields even numbers`() {
        for (source in listOf(1079 to 2399, 1081 to 2401, 999 to 1777, 1443 to 3121)) {
            val (w, h) = Mechanism.outputSize(source.first, source.second, 1920)
            assertEquals("width $w from $source", 0, w % 2)
            assertEquals("height $h from $source", 0, h % 2)
        }
    }

    /** Capping above the screen would upscale: bigger frames, no more picture. */
    @Test
    fun `a cap above the screen does not upscale`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 3840)
        assertEquals(1080, w)
        assertEquals(2400, h)
    }

    @Test
    fun `a cap equal to the screen changes nothing`() {
        val (w, h) = Mechanism.outputSize(1920, 1080, 1920)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `a tiny cap still yields a usable picture`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 720)
        assertTrue("got ${w}x$h", w >= Mechanism.MIN_EDGE && h >= Mechanism.MIN_EDGE)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `a cap below the floor is raised to it`() {
        val (w, h) = Mechanism.outputSize(1080, 2400, 10)
        assertTrue("got ${w}x$h", w >= Mechanism.MIN_EDGE)
        assertTrue("got ${w}x$h", h >= Mechanism.MIN_EDGE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a screen with no pixels is refused`() {
        Mechanism.outputSize(0, 2400, 1920)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative screen is refused`() {
        Mechanism.outputSize(-1080, 2400, 1920)
    }

    @Test
    fun `even rounds down and leaves even numbers alone`() {
        assertEquals(1080, Mechanism.even(1080))
        assertEquals(1080, Mechanism.even(1081))
        assertEquals(0, Mechanism.even(1))
        assertEquals(2, Mechanism.even(3))
    }

    // --- bitrate -----------------------------------------------------------

    @Test
    fun `a bigger picture asks for more bitrate`() {
        val small = Mechanism.bitrateFor(640, 360, 30, Mechanism.Quality.MEDIUM)
        val large = Mechanism.bitrateFor(1920, 1080, 30, Mechanism.Quality.MEDIUM)
        assertTrue("$small then $large", large > small)
    }

    @Test
    fun `more frames ask for more bitrate`() {
        val slow = Mechanism.bitrateFor(1920, 1080, 15, Mechanism.Quality.MEDIUM)
        val fast = Mechanism.bitrateFor(1920, 1080, 60, Mechanism.Quality.MEDIUM)
        assertTrue("$slow then $fast", fast > slow)
    }

    @Test
    fun `the three qualities are three different numbers`() {
        val low = Mechanism.bitrateFor(1920, 1080, 30, Mechanism.Quality.LOW)
        val medium = Mechanism.bitrateFor(1920, 1080, 30, Mechanism.Quality.MEDIUM)
        val high = Mechanism.bitrateFor(1920, 1080, 30, Mechanism.Quality.HIGH)
        assertTrue(low < medium)
        assertTrue(medium < high)
    }

    /** A tiny picture at low quality must not ask for a bitrate no encoder honours. */
    @Test
    fun `the bitrate has a floor`() {
        val b = Mechanism.bitrateFor(160, 160, 15, Mechanism.Quality.LOW)
        assertTrue("got $b", b >= 1_000_000)
    }

    @Test
    fun `the bitrate has a ceiling`() {
        val b = Mechanism.bitrateFor(3840, 2160, 60, Mechanism.Quality.HIGH)
        assertTrue("got $b", b <= 40_000_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero frames a second is refused`() {
        Mechanism.bitrateFor(1920, 1080, 0, Mechanism.Quality.MEDIUM)
    }

    // --- what the two modes cost -------------------------------------------

    /**
     * The number the interface warns with. NDI's own figure for 1080p60 High
     * Bandwidth is about 125 Mbit/s; if this drifts far from that the warning
     * is worse than none, because it would be believed.
     */
    @Test
    fun `full NDI at 1080p60 lands near NDI's own published figure`() {
        val bits = Mechanism.fullNdiBitsPerSecond(1920, 1080, 60)
        assertTrue("got $bits", bits in 100_000_000..150_000_000)
    }

    @Test
    fun `full NDI costs far more than compressed`() {
        val full = Mechanism.fullNdiBitsPerSecond(1920, 1080, 30)
        val hx = Mechanism.bitrateFor(1920, 1080, 30, Mechanism.Quality.HIGH)
        assertTrue("$hx vs $full", full > hx * 5)
    }

    @Test
    fun `raw frames are four bytes a pixel`() {
        assertEquals(1920L * 1080 * 4 * 30, Mechanism.rawBytesPerSecond(1920, 1080, 30))
    }

    @Test
    fun `a plan in full mode reports the full cost, not the encoder's`() {
        val plan = Mechanism.plan(
            Mechanism.Mode.FULL, 1080, 2400, 1920, 30, Mechanism.Quality.MEDIUM
        )
        assertEquals(
            Mechanism.fullNdiBitsPerSecond(plan.width, plan.height, 30),
            plan.estimatedBitsPerSecond
        )
    }

    @Test
    fun `a plan in compressed mode reports the bitrate it will ask for`() {
        val plan = Mechanism.plan(
            Mechanism.Mode.COMPRESSED, 1080, 2400, 1920, 30, Mechanism.Quality.MEDIUM
        )
        assertEquals(plan.bitrate.toLong(), plan.estimatedBitsPerSecond)
    }

    @Test
    fun `a plan carries the orientation of the picture it will send`() {
        val portrait = Mechanism.plan(
            Mechanism.Mode.COMPRESSED, 1080, 2400, 1920, 30, Mechanism.Quality.MEDIUM
        )
        assertTrue(portrait.isPortrait)
        val landscape = Mechanism.plan(
            Mechanism.Mode.COMPRESSED, 2400, 1080, 1920, 30, Mechanism.Quality.MEDIUM
        )
        assertFalse(landscape.isPortrait)
    }

    // --- rebuilding on a turn ----------------------------------------------

    @Test
    fun `a turn of the phone rebuilds`() {
        assertTrue(Mechanism.needsRebuild(864, 1920, 1920, 864))
    }

    /**
     * Android reports a rotation on every ninety degrees, including the two
     * that leave the geometry exactly as it was. Rebuilding on those costs a
     * second of black picture for nothing.
     */
    @Test
    fun `an upside-down turn does not rebuild`() {
        assertFalse(Mechanism.needsRebuild(864, 1920, 864, 1920))
    }

    @Test
    fun `a change of cap rebuilds`() {
        assertTrue(Mechanism.needsRebuild(864, 1920, 576, 1280))
    }

    // --- the source name ---------------------------------------------------

    @Test
    fun `an ordinary name comes back untouched`() {
        assertEquals("Pixel 7 Screen", Mechanism.sourceName("Pixel 7 Screen"))
    }

    /** An NDI name travels in mDNS: a dot or a colon in it advertises wrongly. */
    @Test
    fun `punctuation that breaks mDNS is taken out`() {
        val name = Mechanism.sourceName("Marko's Phone: screen.1")
        assertFalse(name, name.contains('.'))
        assertFalse(name, name.contains(':'))
        assertFalse(name, name.contains('\''))
    }

    @Test
    fun `runs of dashes and spaces collapse`() {
        assertEquals("a b", Mechanism.sourceName("a   ---   b"))
    }

    @Test
    fun `an empty name falls back rather than advertising nothing`() {
        assertEquals("Screen", Mechanism.sourceName(""))
        assertEquals("Screen", Mechanism.sourceName("   "))
        assertEquals("Screen", Mechanism.sourceName("---"))
    }

    @Test
    fun `a named fallback is used when given`() {
        assertEquals("Android Screen", Mechanism.sourceName("", "Android Screen"))
    }

    @Test
    fun `a very long name is cut rather than refused`() {
        val name = Mechanism.sourceName("x".repeat(200))
        assertTrue("got ${name.length}", name.length <= 60)
    }

    @Test
    fun `cleaning a name twice changes nothing the second time`() {
        val once = Mechanism.sourceName("Marko's Phone: screen.1")
        assertEquals(once, Mechanism.sourceName(once))
    }

    @Test
    fun `two phone models give two different names`() {
        assertNotEquals(
            Mechanism.sourceName("Pixel 7 Screen"),
            Mechanism.sourceName("Nothing Phone 2a Screen")
        )
    }

    // --- the readouts ------------------------------------------------------

    @Test
    fun `megabits reads in megabits above a million`() {
        assertEquals("12.0 Mbit/s", Mechanism.megabits(12_000_000))
        assertEquals("1.5 Mbit/s", Mechanism.megabits(1_500_000))
    }

    @Test
    fun `megabits drops the decimal when the number is large`() {
        assertEquals("124 Mbit/s", Mechanism.megabits(124_416_000))
    }

    @Test
    fun `megabits reads in kilobits below a million`() {
        assertEquals("800 kbit/s", Mechanism.megabits(800_000))
    }

    /**
     * The first tick after a start always has a window of zero. Dividing by it
     * is a crash; guessing a number for it is a lie on the one readout that
     * exists to be believed.
     */
    @Test
    fun `a rate over no time at all is no rate`() {
        assertEquals(0L, Mechanism.measuredBitsPerSecond(1_000_000, 0))
        assertEquals(0L, Mechanism.measuredBitsPerSecond(1_000_000, -5))
    }

    @Test
    fun `a rate over a second is the bytes in bits`() {
        assertEquals(8_000_000L, Mechanism.measuredBitsPerSecond(1_000_000, 1000))
    }

    @Test
    fun `a rate over half a second doubles`() {
        assertEquals(16_000_000L, Mechanism.measuredBitsPerSecond(1_000_000, 500))
    }

    @Test
    fun `one decimal rounds rather than truncating`() {
        assertEquals("1.5", Mechanism.round1(1.45))
        assertEquals("2.0", Mechanism.round1(1.96))
        assertEquals("0.0", Mechanism.round1(0.04))
    }

    @Test
    fun `describe says the size, the way up and the rate`() {
        val plan = Mechanism.plan(
            Mechanism.Mode.COMPRESSED, 1080, 2400, 1920, 30, Mechanism.Quality.MEDIUM
        )
        val text = Mechanism.describe(plan)
        assertTrue(text, text.contains("portrait"))
        assertTrue(text, text.contains("30"))
        assertTrue(text, text.contains("${plan.width}"))
    }
}
