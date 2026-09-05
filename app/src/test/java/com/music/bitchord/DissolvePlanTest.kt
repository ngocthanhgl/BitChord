package com.music.bitchord

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.StructureLabel
import com.music.bitchord.playback.smart.StructureSectionType
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.findPlainCutPoint
import com.music.bitchord.playback.smart.halfTimeRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v2 §9a/§9b plan-side tests: the silence-gap cutter's four tiers and
 * the half-time deck-rate math. Renderer envelopes are progress functions
 * over these outputs; gains are covered by existing controller tests.
 */
class DissolvePlanTest {

    private fun curve(vararg points: Pair<Double, Double>): List<EnergySample> =
        points.map { EnergySample(it.first, it.second) }

    /** Flat loud track with a 1.5 s hole at 150–151.5 s (spans ≥0.6 s). */
    private fun gapTrack(): TrackAnalysis {
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 100.0
        while (t <= 200.0) {
            pts += t to if (t >= 150.0 && t < 151.5) 0.01 else 0.9
            t += 0.5
        }
        return TrackAnalysis(energyCurve = curve(*pts.toTypedArray()))
    }

    @Test
    fun silenceGap_cutAtGapStart_twoSeconds() {
        val (cut, dur) = findPlainCutPoint(gapTrack(), 100.0, 200.0)
        assertEquals(150.0, cut, 0.6)
        assertEquals(2.0, dur, 0.001)
    }

    @Test
    fun breakLabel_cutAtBreakStart_twoSeconds() {
        val a = TrackAnalysis(
            energyCurve = curve(100.0 to 0.8, 150.0 to 0.8, 200.0 to 0.8),
            structureMap = listOf(StructureLabel(160.0, 190.0, StructureSectionType.BREAK)),
        )
        val (cut, dur) = findPlainCutPoint(a, 100.0, 200.0)
        assertEquals(160.0, cut, 0.001)
        assertEquals(2.0, dur, 0.001)
    }

    @Test
    fun minEnergyWindow_cutAtQuietest_fourSeconds() {
        // Loud everywhere except a 8 s dip at 170–178 s; 4-bar window = 8 s
        // at 120 BPM (beat 0.5 s).
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 100.0
        while (t <= 200.0) {
            pts += t to if (t >= 170.0 && t < 178.0) 0.2 else 0.9
            t += 0.5
        }
        val a = TrackAnalysis(
            energyCurve = curve(*pts.toTypedArray()),
            bpm = 120.0,
            beatInterval = 0.5,
        )
        val (cut, dur) = findPlainCutPoint(a, 100.0, 200.0)
        assertTrue("cut $cut inside quiet dip", cut >= 168.0 && cut <= 174.0)
        assertEquals(4.0, dur, 0.001)
    }

    @Test
    fun emptyCurve_fallsBackToContentEndMinusEight() {
        val (cut, dur) = findPlainCutPoint(TrackAnalysis(), 100.0, 200.0)
        assertEquals(192.0, cut, 0.001)
        assertEquals(4.0, dur, 0.001)
    }

    @Test
    fun halfTimeRates_slowerDeckWins() {
        // 90 vs 135 (3:2): B slows to A.
        val (shared, rateA, rateB) = halfTimeRates(90.0, 135.0)
        assertEquals(90.0, shared, 0.001)
        assertEquals(1.0, rateA, 0.001)
        assertEquals(90.0 / 135.0, rateB, 0.001)
        // 128 vs 85 (~2:3): A slows to B.
        val (shared2, rateA2, rateB2) = halfTimeRates(128.0, 85.0)
        assertEquals(85.0, shared2, 0.001)
        assertEquals(1.0, rateB2, 0.001)
        assertTrue(rateA2 < 1.0)
    }
}
