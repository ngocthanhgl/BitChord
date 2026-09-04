package com.music.bitchord

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.alignMixsetExitToIncomingDrop
import com.music.bitchord.playback.smart.cooldownLanding
import com.music.bitchord.playback.smart.overlapEnergyFactorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2 §6 overlap energy factor, §8b falling cooldown, §8c phrase-capped align.
 * Pure JVM — synthetic curves.
 */
class OverlapCooldownTest {

    @Test
    fun `A falling plus B rising stretches`() {
        assertEquals(1.50, overlapEnergyFactorFor(-0.01, 0.01), 1e-9)
    }

    @Test
    fun `both rising tightens`() {
        assertEquals(0.75, overlapEnergyFactorFor(0.01, 0.01), 1e-9)
    }

    @Test
    fun `flat holds at unity`() {
        assertEquals(1.0, overlapEnergyFactorFor(0.0, 0.0), 1e-9)
        assertEquals(1.0, overlapEnergyFactorFor(-0.01, 0.0), 1e-9)
        assertEquals(1.0, overlapEnergyFactorFor(0.01, -0.01), 1e-9)
    }

    private fun curve(length: Double, energyAt: (Double) -> Double): List<EnergySample> {
        val out = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= length) {
            out += EnergySample(t, energyAt(t))
            t += 1.0
        }
        return out
    }

    private fun base(length: Double, energyAt: (Double) -> Double): TrackAnalysis =
        TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = length,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = (0..100).map { it * 2.0 },
            firstBeat = 0.0,
            key = "C major",
            keyConfidence = 0.9,
            energyCurve = curve(length, energyAt),
        )

    @Test
    fun `falling quiet window lands the cooldown`() {
        val analysis = base(200.0) { t ->
            if (t in 100.0..108.0) 0.6 - (t - 100.0) * 0.025 else 1.0
        }
        assertEquals(100.0, cooldownLanding(analysis, 90.0, 150.0)!!, 1e-9)
    }

    @Test
    fun `flat quiet stretch is not a comedown`() {
        val analysis = base(200.0) { t ->
            if (t in 100.0..108.0) 0.5 else 1.0
        }.copy(downbeats = listOf(100.0))
        assertNull(cooldownLanding(analysis, 90.0, 150.0))
    }

    @Test
    fun `align tolerance stretches to one phrase capped at 16`() {
        // phrase16 = 32 s -> tolerance 16 s. Gap of 12 s pulls where the old
        // fixed 8 s tolerance refused.
        val outgoing = base(200.0) { 1.0 }
        val incoming = base(200.0) { 1.0 }.copy(structuredDropSec = 130.0)
        assertEquals(128.0, alignMixsetExitToIncomingDrop(outgoing, incoming, 140.0, true), 1e-9)
    }

    @Test
    fun `align beyond one phrase keeps the anchor`() {
        val outgoing = base(200.0) { 1.0 }
        val incoming = base(200.0) { 1.0 }.copy(structuredDropSec = 100.0)
        assertEquals(150.0, alignMixsetExitToIncomingDrop(outgoing, incoming, 150.0, true), 1e-9)
    }
}
