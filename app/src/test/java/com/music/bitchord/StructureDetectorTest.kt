package com.music.bitchord

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.StructureDetector
import com.music.bitchord.playback.smart.StructureSectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 §2b detector + §4 gradient. Pure JVM — synthetic 250 ms curves.
 */
class StructureDetectorTest {

    private val beatInterval = 0.46875 // 128 BPM
    private val bar = beatInterval * 4 // 1.875 s

    private fun downs(duration: Double): List<Double> {
        val out = mutableListOf<Double>()
        var t = 0.0
        while (t <= duration) {
            out += t
            t += bar
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

    private fun centroid(duration: Double, hzAt: (Double) -> Double): List<EnergySample> =
        fine(duration, hzAt)

    @Test
    fun `linearSlope measures rise over run`() {
        assertEquals(2.0, StructureDetector.linearSlope(listOf(0.0, 1.0, 2.0), listOf(0.0, 2.0, 4.0)), 1e-9)
        assertEquals(0.0, StructureDetector.linearSlope(listOf(0.0), listOf(1.0)), 1e-9)
    }

    @Test
    fun `empty input degrades to no labels`() {
        assertTrue(
            StructureDetector.detect(
                fine = emptyList(), centroid = emptyList(), onsets = emptyList(),
                downbeats = emptyList(), duration = 200.0,
                meanRms = 1.0, meanOnset = 1.0, beatInterval = beatInterval,
            ).isEmpty(),
        )
    }

    @Test
    fun `loud bright dense window after a rise is DROP`() {
        val duration = 200.0
        val curve = fine(duration) { t ->
            when {
                t < 45.0 -> 0.9
                t < 58.0 -> 0.9 + (t - 45.0) / 13.0 * 0.4 // ramp into the drop
                t < 66.0 -> 1.6 // the drop
                else -> 1.0
            }
        }
        val cents = centroid(duration) { t -> if (t in 50.0..70.0) 4200.0 else 2000.0 }
        val onsets = (0..800).map { it * 0.25 }.filter { it in 50.0..70.0 } +
            (0..40).map { it * 5.0 }
        val map = StructureDetector.detect(
            fine = curve, centroid = cents, onsets = onsets,
            downbeats = downs(duration), duration = duration,
            meanRms = 1.0, meanOnset = 1.0, beatInterval = beatInterval,
        )
        assertTrue(map.any { it.type == StructureSectionType.DROP })
    }

    @Test
    fun `sustained low passage after loud part is BREAK`() {
        val duration = 200.0
        val curve = fine(duration) { t ->
            when {
                t < 90.0 -> 1.2
                t < 130.0 -> 0.4 // 40 s breakdown
                else -> 1.1
            }
        }
        val cents = centroid(duration) { 2200.0 }
        val onsets = (0..200).map { it * 1.0 }.filter { it < 90.0 || it >= 130.0 }
        val map = StructureDetector.detect(
            fine = curve, centroid = cents, onsets = onsets,
            downbeats = downs(duration), duration = duration,
            meanRms = 1.0, meanOnset = 1.0, beatInterval = beatInterval,
        )
        assertTrue(map.any { it.type == StructureSectionType.BREAK && it.start >= 90.0 && it.start < 130.0 })
    }

    @Test
    fun `falling tail past 72 percent is OUTRO`() {
        val duration = 200.0
        val curve = fine(duration) { t ->
            if (t < 150.0) 1.1 else 1.1 - (t - 150.0) / 50.0 * 0.8 // 1.1 -> 0.3
        }
        val cents = centroid(duration) { 2200.0 }
        val map = StructureDetector.detect(
            fine = curve, centroid = cents, onsets = (0..100).map { it * 2.0 },
            downbeats = downs(duration), duration = duration,
            meanRms = 1.0, meanOnset = 1.0, beatInterval = beatInterval,
        )
        assertTrue(map.any { it.type == StructureSectionType.OUTRO })
    }

    @Test
    fun `quiet dark opening is INTRO`() {
        val duration = 200.0
        val curve = fine(duration) { t -> if (t < 20.0) 0.5 else 1.1 }
        val cents = centroid(duration) { t -> if (t < 20.0) 2000.0 else 3200.0 }
        val map = StructureDetector.detect(
            fine = curve, centroid = cents, onsets = (0..100).map { it * 2.0 },
            downbeats = downs(duration), duration = duration,
            meanRms = 1.0, meanOnset = 1.0, beatInterval = beatInterval,
        )
        assertTrue(map.any { it.type == StructureSectionType.INTRO && it.start < 20.0 })
    }

    // --- §4 gradient ---

    @Test
    fun `gradient finds the dip foot on a compressed track`() {
        // Everything sits near the peak (0.9 vs 1.2): the old peak-fraction
        // foot fails, but the gradient flip at the dip bottom is unambiguous.
        val curve = fine(120.0) { t ->
            when {
                t < 70.0 -> 0.9
                t < 80.0 -> 0.9 - (t - 70.0) / 10.0 * 0.5 // 0.9 -> 0.4
                t < 95.0 -> 0.4 + (t - 80.0) / 15.0 * 0.6 // 0.4 -> 1.0
                t < 102.0 -> 1.2 // the drop
                else -> 1.0
            }
        }
        val foot = StructureDetector.gradientBuildup(curve, dropSec = 100.0, peakEnergy = 1.2)
        assertNotNull(foot)
        assertTrue("foot=$foot", foot!! in 75.0..85.0)
    }

    @Test
    fun `gradient returns null on a flat line`() {
        val curve = fine(120.0) { 0.9 }
        assertNull(StructureDetector.gradientBuildup(curve, dropSec = 100.0, peakEnergy = 0.9))
    }

    @Test
    fun `gradient returns null when the rise is too small`() {
        val curve = fine(120.0) { t ->
            when {
                t < 80.0 -> 1.0
                t < 82.0 -> 0.85
                t < 100.0 -> 0.85 + (t - 82.0) / 18.0 * 0.15
                else -> 1.0
            }
        }
        // Rise 0.15 < 0.25 x peak: a wobble, not a buildup.
        assertNull(StructureDetector.gradientBuildup(curve, dropSec = 100.0, peakEnergy = 1.0))
    }
}
