package com.music.bitchord

import com.music.bitchord.playback.tempoGlideFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tempo glide-back: the incoming deck rides a stretched rate onto the shared
 * grid, then eases home to its own tempo before the handoff so finish()
 * has no snap left to deliver. Pure JVM.
 */
class TempoGlideTest {

    @Test
    fun `starts locked to the shared grid`() {
        assertEquals(1.0, tempoGlideFactor(0f, 1.08), 1e-9)
        assertEquals(1.0, tempoGlideFactor(0f, 0.92), 1e-9)
        assertEquals(1.0, tempoGlideFactor(0f, 1.5), 1e-9)
    }

    @Test
    fun `lands home exactly at the handoff`() {
        assertEquals(0.0, tempoGlideFactor(1f, 1.08), 1e-9)
        assertEquals(0.0, tempoGlideFactor(1f, 0.5), 1e-9)
        assertEquals(0.0, tempoGlideFactor(1f, 1.0), 1e-9)
    }

    @Test
    fun `small stretch holds the grid late`() {
        // 8% -> 40% window starting at 0.6: still fully stretched at halfway.
        assertEquals(1.0, tempoGlideFactor(0.5f, 1.08), 1e-9)
        val mid = tempoGlideFactor(0.7f, 1.08)
        assertTrue(mid in 0.0..1.0)
        // Window midpoint rides exactly halfway home (smoothstep symmetry).
        assertEquals(0.5, tempoGlideFactor(0.8f, 1.08), 1e-6)
    }

    @Test
    fun `half-time stretch starts coming back early`() {
        // 50% -> capped 75% window starting at 0.25.
        assertEquals(1.0, tempoGlideFactor(0.2f, 1.5), 1e-9)
        val mid = tempoGlideFactor(0.5f, 1.5)
        assertTrue(mid in 0.0..1.0)
    }

    @Test
    fun `unity stretch keeps a minimal window`() {
        // Portion floors at 25%: nothing moves before 0.75.
        assertEquals(1.0, tempoGlideFactor(0.7f, 1.0), 1e-9)
    }

    @Test
    fun `slow-down mirrors speed-up`() {
        // Same magnitude, same window, same curve.
        var p = 0f
        while (p <= 1f) {
            assertEquals(tempoGlideFactor(p, 1.08), tempoGlideFactor(p, 0.92), 1e-9)
            p += 0.05f
        }
    }

    @Test
    fun `glide never rises`() {
        for (stretch in listOf(1.08, 0.92, 1.5, 0.5, 1.0)) {
            var prev = 2.0
            var p = 0f
            while (p <= 1f) {
                val v = tempoGlideFactor(p, stretch)
                assertTrue("p=$p stretch=$stretch rose: $v > $prev", v <= prev + 1e-9)
                prev = v
                p += 0.05f
            }
        }
    }
}
