package com.music.bitchord

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.StructureDetector
import com.music.bitchord.playback.smart.StructureLabel
import com.music.bitchord.playback.smart.StructureSectionType
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.adjustedMixsetEntry
import com.music.bitchord.playback.smart.validateBuildupStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exit/entry bug-fix spec: scored drop selection (Fix 1+6), buildup
 * validation (Fix 3), vocal-gated entry (Fix 4). Pure JVM, synthetic curves.
 */
class ExitEntrySpecTest {

    private val beatInterval = 0.46875 // 128 BPM
    private val bar = beatInterval * 4

    private fun downs(duration: Double, step: Double = bar): List<Double> {
        val out = mutableListOf<Double>()
        var t = 0.0
        while (t <= duration) {
            out += t
            t += step
        }
        return out
    }

    private fun fine(duration: Double, energyAt: (Double) -> Double): List<EnergySample> {
        val out = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= duration) {
            out += EnergySample(t, energyAt(t))
            t += 0.25
        }
        return out
    }

    private fun scoreDrop(
        duration: Double,
        energyAt: (Double) -> Double,
        hzAt: (Double) -> Double,
        onsets: List<Double>,
        labels: List<StructureLabel>,
        bpm: Double = 128.0,
        conf: Double = 0.8,
    ): Double? {
        val curve = fine(duration, energyAt)
        val cents = fine(duration, hzAt)
        val mean = curve.map { it.energy }.average()
        return StructureDetector.selectFirstDrop(
            fine = curve,
            centroid = cents,
            onsets = onsets,
            downbeats = downs(duration),
            duration = duration,
            meanRms = mean,
            meanOnset = onsets.size / duration,
            beatInterval = beatInterval,
            structureMap = labels,
            bpm = bpm,
            beatConfidence = conf,
        )
    }

    @Test
    fun `scorer picks the real drop over an energetic intro`() {
        // V1 shape: hot intro 0-50, calm, BUILD 100-140, drop at 144 (40%).
        val duration = 360.0
        val energyAt = { t: Double ->
            when {
                t < 50.0 -> 1.5
                t < 100.0 -> 0.9
                t < 140.0 -> 0.9 + (t - 100.0) / 40.0 * 0.4
                t < 180.0 -> 1.7
                else -> 1.0
            }
        }
        val hzAt = { t: Double ->
            when {
                t < 50.0 -> 1800.0
                t in 100.0..180.0 -> 4200.0
                else -> 2000.0
            }
        }
        val onsets = (0..720).map { it * 0.25 }.filter { it in 100.0..180.0 } +
            (0..40).map { it * 5.0 }
        val labels = listOf(StructureLabel(100.0, 140.0, StructureSectionType.BUILD))
        val drop = scoreDrop(duration, energyAt, hzAt, onsets, labels)
        assertNotNull(drop)
        assertTrue("drop $drop must be the real one near 144, not the intro", drop!! > 60.0 && drop < 200.0)
    }

    @Test
    fun `techno with dark centroid still detects a drop`() {
        // V5 shape: 138 BPM, centroid 1800 in the drop, moderate rms rise.
        val technoBeat = 60.0 / 138.0
        val duration = 420.0
        val energyAt = { t: Double ->
            when {
                t < 150.0 -> 1.0
                t < 230.0 -> 1.0 + (t - 150.0) / 80.0 * 0.35
                t < 300.0 -> 1.35
                else -> 1.0
            }
        }
        val hzAt = { t: Double -> if (t in 150.0..300.0) 1800.0 else 1600.0 }
        val onsets = (0..1680).map { it * 0.25 }.filter { it in 150.0..300.0 } +
            (0..60).map { it * 5.0 }
        val curve = fine(duration, energyAt)
        val cents = fine(duration, hzAt)
        val mean = curve.map { it.energy }.average()
        val step = technoBeat * 4
        val downs = mutableListOf<Double>()
        var t = 0.0
        while (t <= duration) {
            downs += t
            t += step
        }
        val drop = StructureDetector.selectFirstDrop(
            fine = curve, centroid = cents, onsets = onsets, downbeats = downs,
            duration = duration, meanRms = mean, meanOnset = onsets.size / duration,
            beatInterval = technoBeat,
            structureMap = listOf(StructureLabel(150.0, 230.0, StructureSectionType.BUILD)),
            bpm = 138.0, beatConfidence = 0.8,
        )
        assertNotNull("techno drop must be found with relaxed centroid", drop)
        assertTrue("drop $drop must sit in the layered section", drop!! > 140.0 && drop < 310.0)
    }

    @Test
    fun `untrusted grid returns null for max-energy fallback`() {
        val drop = scoreDrop(
            duration = 200.0,
            energyAt = { 1.2 },
            hzAt = { 3000.0 },
            onsets = (0..400).map { it * 0.5 },
            labels = emptyList(),
            conf = 0.2,
        )
        assertNull(drop)
    }

    @Test
    fun `flat bed reports no phantom drop`() {
        // Strict-neighbor contract (MixsetTest): constant energy → null, even
        // though the fallback scans [0.20L, 0.65L].
        val drop = scoreDrop(
            duration = 200.0,
            energyAt = { 1.0 },
            hzAt = { 2000.0 },
            onsets = emptyList(),
            labels = emptyList(),
        )
        assertNull(drop)
    }

    @Test
    fun `validateBuildupStart enforces the 8s and 90s corridor`() {
        assertEquals(50.0, validateBuildupStart(50.0, 100.0, 30.0)!!, 1e-9)
        // Too close: back off one phrase.
        assertEquals(70.0, validateBuildupStart(95.0, 100.0, 30.0)!!, 1e-9)
        // Too far: cap at one phrase.
        assertEquals(70.0, validateBuildupStart(0.0, 100.0, 30.0)!!, 1e-9)
        // Back-off off the track start: null so the caller falls to the peak.
        assertNull(validateBuildupStart(5.0, 10.0, 30.0))
        // Null raw stays null; null phrase keeps raw.
        assertNull(validateBuildupStart(null, 100.0, 30.0))
        assertEquals(50.0, validateBuildupStart(50.0, 100.0, null)!!, 1e-9)
    }

    @Test
    fun `vocal-heavy entry advances to the next clean beat`() {
        // V3 shape: buildup at 60, voice over 60-63 plus 66-67 (mean 0.5
        // over the 4-bar check window), clean bar at 63 inside the advance.
        val mask = List(800) { i ->
            val t = i * 0.25
            if ((t >= 60.0 && t < 63.0) || (t >= 66.0 && t < 67.0)) 1.0 else 0.0
        }
        val analysis = TrackAnalysis(beatInterval = 0.5, vocalActivityMask = mask)
        assertEquals(63.0, adjustedMixsetEntry(60.0, analysis, 200.0), 1e-9)
    }

    @Test
    fun `clean entry is untouched and all-vocal keeps the original`() {
        val clean = TrackAnalysis(beatInterval = 0.5, vocalActivityMask = List(800) { 0.0 })
        assertEquals(60.0, adjustedMixsetEntry(60.0, clean, 200.0), 1e-9)
        // V4 guard: never overshoot into the drop when nothing is clean.
        val sung = TrackAnalysis(beatInterval = 0.5, vocalActivityMask = List(800) { 1.0 })
        assertEquals(60.0, adjustedMixsetEntry(60.0, sung, 200.0), 1e-9)
    }
}
