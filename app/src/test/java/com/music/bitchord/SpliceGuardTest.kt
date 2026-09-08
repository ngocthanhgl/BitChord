package com.music.bitchord

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.bitchord.playback.SpliceGuardProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splice-guard micro-fades: beat-snapped cues and INSTANT cuts land
 * mid-waveform, so the guard softens every splice with short equal-power
 * ramps. Pure JVM — the processor only touches short arrays.
 */
class SpliceGuardTest {

    private val sr = 48_000
    private val format = AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT)

    private fun fullScale(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { buf.putShort(Short.MAX_VALUE) }
        buf.flip()
        return buf
    }

    private fun drain(g: SpliceGuardProcessor, frames: Int): ShortArray {
        g.queueInput(fullScale(frames))
        val out = g.output
        val samples = ShortArray(out.remaining() / 2)
        repeat(samples.size) { samples[it] = out.short }
        g.reset()
        return samples
    }

    private fun fresh(): SpliceGuardProcessor = SpliceGuardProcessor().also {
        val out = it.configure(format)
        assertEquals(format, out)
    }

    @Test
    fun `flush arms a fade-in from silence`() {
        val g = fresh()
        g.onFlush()
        val samples = drain(g, 600)
        // First sample is (near) silence, ramp reaches full scale at 10 ms.
        assertTrue(samples[0] < 500)
        val fadeFrames = (sr * SpliceGuardProcessor.FADE_IN_MS / 1000).toInt()
        assertTrue(samples[fadeFrames - 1] > Short.MAX_VALUE - 500)
        assertEquals(Short.MAX_VALUE, samples.last())
    }

    @Test
    fun `steady state passes through bit-exact`() {
        val g = fresh()
        g.onFlush()
        drain(g, 600) // drain the fade-in
        val samples = drain(g, 600)
        assertTrue(samples.all { it == Short.MAX_VALUE })
    }

    @Test
    fun `cut dips to zero and returns without a gap`() {
        val g = fresh()
        g.onFlush()
        drain(g, 600)
        g.triggerCut()
        val outFrames = (sr * SpliceGuardProcessor.CUT_OUT_MS / 1000).toInt()
        val inFrames = (sr * SpliceGuardProcessor.CUT_IN_MS / 1000).toInt()
        val samples = drain(g, outFrames + inFrames + 100)
        val min = samples.min()
        assertTrue("cut reaches silence, min=$min", min < 500)
        // No silent gap: the cut-in starts the sample the cut-out ends.
        val lastZero = samples.indexOfLast { it < 500 }
        assertTrue(samples[lastZero + 1] >= 500)
        assertEquals(Short.MAX_VALUE, samples.last())
    }

    @Test
    fun `cut then flush still fades in cleanly`() {
        val g = fresh()
        g.onFlush()
        drain(g, 600)
        g.triggerCut()
        g.onFlush()
        val samples = drain(g, 600)
        assertTrue(samples[0] < 500)
        assertEquals(Short.MAX_VALUE, samples.last())
    }
}
