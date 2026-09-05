package com.music.bitchord

import com.music.bitchord.playback.smart.CompatibilityScore
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.StructureDetector
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTier
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.TransitionType
import com.music.bitchord.playback.smart.buildupStart
import com.music.bitchord.playback.smart.ceilingFor
import com.music.bitchord.playback.smart.findPlainCutPoint
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.selectTransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec finetune-final regression net: ceilings, DJ matrix, buildup chain,
 * breath cutter, short-track dissolve, standard exemption, same-file cut.
 * Pure JVM — no Android, no models.
 */
class FinetuneTest {

    private fun score(bpm: Double, key: Double, vocal: Double) =
        CompatibilityScore(bpm = bpm, key = key, energy = 1.0, structure = 1.0, vocal = vocal, overall = 0.9)

    // --- §4.1 per-type ceilings ---

    @Test
    fun `ceiling map matches spec rows`() {
        assertEquals(22.0, ceilingFor(TransitionType.SMOOTH_CROSSFADE), 1e-9)
        assertEquals(28.0, ceilingFor(TransitionType.HARMONIC_BLEND), 1e-9)
        assertEquals(9.0, ceilingFor(TransitionType.FILTER_SWEEP), 1e-9)
        assertEquals(11.0, ceilingFor(TransitionType.ECHO_REVERB_OUT), 1e-9)
        assertEquals(8.0, ceilingFor(TransitionType.LOOP_CUT_DROP), 1e-9)
        assertEquals(0.3, ceilingFor(TransitionType.HARD_CUT), 1e-9)
        assertEquals(20.0, ceilingFor(TransitionType.HALF_TIME_BLEND), 1e-9)
        assertEquals(4.0, ceilingFor(TransitionType.PLAIN_DISSOLVE), 1e-9)
    }

    // --- §7.1 DJ limited matrix ---

    @Test
    fun `DJ unsyncable echoes out`() {
        assertEquals(
            TransitionType.ECHO_REVERB_OUT,
            selectTransitionType(score(0.2, 0.9, 1.0), TransitionTier.DJ_ASSISTED, false, false, false),
        )
    }

    @Test
    fun `DJ key agreement filters`() {
        assertEquals(
            TransitionType.FILTER_SWEEP,
            selectTransitionType(score(0.8, 0.8, 1.0), TransitionTier.DJ_ASSISTED, false, false, false),
        )
    }

    @Test
    fun `DJ never smooths or blends no matter how clean`() {
        val clean = score(0.95, 0.95, 1.0)
        val type = selectTransitionType(clean, TransitionTier.DJ_ASSISTED, true, true, true)
        assertTrue(
            "DJ type $type must not be SMOOTH or HARMONIC",
            type != TransitionType.SMOOTH_CROSSFADE && type != TransitionType.HARMONIC_BLEND,
        )
    }

    @Test
    fun `DJ rest cuts`() {
        assertEquals(
            TransitionType.HARD_CUT,
            selectTransitionType(score(0.8, 0.3, 1.0), TransitionTier.DJ_ASSISTED, false, false, false),
        )
    }

    // --- §6.1 gradient helpers ---

    private fun fineDipRise(): List<EnergySample> {
        // Dip at t=80, steady climb to a peak at t=100 (the drop).
        val out = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= 120.0) {
            val energy = when {
                t < 78.0 -> 1.0
                t < 80.0 -> 0.4
                t <= 100.0 -> 0.4 + (t - 80.0) * 0.05
                else -> 1.4
            }
            out += EnergySample(t, energy)
            t += 1.0
        }
        return out
    }

    @Test
    fun `inflection finds the dip before the drop`() {
        val flip = StructureDetector.gradientInflection(fineDipRise(), 100.0)
        assertTrue("flip $flip should sit near the dip", flip != null && flip in 76.0..84.0)
    }

    @Test
    fun `monotonic climb earns the lean`() {
        assertTrue(StructureDetector.climbMonotonic(fineDipRise(), 80.0, 100.0))
    }

    @Test
    fun `flat fine curve earns nothing`() {
        val flat = (0..120).map { EnergySample(it.toDouble(), 1.0) }
        assertEquals(null, StructureDetector.gradientInflection(flat, 100.0))
        assertFalse(StructureDetector.climbMonotonic(flat, 80.0, 100.0))
    }

    // --- §6.1 chain through buildupStart ---

    private fun chainTrack(): TrackAnalysis {
        val fine = fineDipRise()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 200.0,
            bpm = 128.0,
            beatInterval = 60.0 / 128.0,
            beatConfidence = 0.9,
            downbeats = (0..200 step 4).map { it * 60.0 / 128.0 },
            firstBeat = 0.0,
            energyCurve = fine,
            energyCurveFine = fine,
            structuredDropSec = 100.0,
        )
    }

    @Test
    fun `stored buildup wins first`() {
        val track = chainTrack().copy(structuredBuildupSec = 70.0)
        val entry = buildupStart(track, 100.0)
        assertTrue("entry $entry should snap near 70", entry != null && entry in 60.0..75.0)
    }

    @Test
    fun `gradient inflection anchors the entry`() {
        // No stored buildup: the validated flip near t=80 should win over
        // drop-minus-phrase (100-30=70 would also be near — assert the snap
        // lands on the 16-bar grid at or before the flip).
        val entry = buildupStart(chainTrack(), 100.0)
        assertTrue("entry $entry should be a grid snap at or before 80", entry != null && entry <= 80.0)
    }

    // --- §7.4 breath cutter tier ---

    @Test
    fun `breath point cuts with a 2s dissolve`() {
        val points = (0..200).map { EnergySample(it.toDouble(), 1.0) }
        val track = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 200.0,
            energyCurve = points,
            contentEndTime = 200.0,
            plainCutBreathSec = 170.0,
        )
        val (cut, dissolve) = findPlainCutPoint(track, 150.0, 200.0)
        assertEquals(170.0, cut, 1e-9)
        assertEquals(2.0, dissolve, 1e-9)
    }

    @Test
    fun `breath outside the scan range is ignored`() {
        val points = (0..200).map { EnergySample(it.toDouble(), 1.0) }
        val track = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 200.0,
            energyCurve = points,
            contentEndTime = 200.0,
            plainCutBreathSec = 100.0,
        )
        val (cut, _) = findPlainCutPoint(track, 150.0, 200.0)
        assertTrue("cut $cut must not use the out-of-range breath", cut != 100.0)
    }

    // --- §7.5 / §4.1 end to end ---

    private fun flatTrack(bpm: Double, length: Double): TrackAnalysis {
        val points = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= length) {
            points += EnergySample(t, 1.0)
            t += 1.0
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = length,
            bpm = bpm,
            beatInterval = 60.0 / bpm,
            beatConfidence = 0.9,
            downbeats = (0..200).map { it * 4 * 60.0 / bpm },
            firstBeat = 0.0,
            key = "C major",
            keyConfidence = 0.9,
            contentEndTime = length,
            mixInCandidates = listOf(MixCandidate(20.0, 1.0, "main_drop")),
            mixOutCandidates = listOf(MixCandidate(length - 10.0, 0.9, "energy_cliff")),
            energyCurve = points,
            vocalActivityMask = List(points.size) { 0.1 },
        )
    }

    @Test
    fun `short track dissolves instead of blending`() {
        val plan = planTransition(
            analysis = flatTrack(128.0, 70.0),
            nextAnalysis = flatTrack(128.0, 200.0),
            duration = 70.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionType.PLAIN_DISSOLVE, plan.type)
        assertFalse(plan.standardTransitionUsed)
    }

    @Test
    fun `manual fallback is exempt from ceilings`() {
        val plan = planTransition(
            analysis = flatTrack(128.0, 200.0),
            nextAnalysis = flatTrack(128.0, 200.0),
            duration = 200.0,
            mode = CrossfadeMode.STANDARD,
        )
        assertTrue(plan.standardTransitionUsed)
    }

    @Test
    fun `smart matrix path never sets the exemption`() {
        val plan = planTransition(
            analysis = flatTrack(128.0, 200.0),
            nextAnalysis = flatTrack(128.0, 200.0),
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertFalse(plan.standardTransitionUsed)
    }

    @Test
    fun `same file hard cuts`() {
        val info = { TransitionTrackInfo(id = "same", durationMs = 200_000L) }
        val plan = planTransition(
            analysis = flatTrack(128.0, 200.0),
            nextAnalysis = flatTrack(128.0, 200.0),
            currentTrack = info(),
            nextTrack = info(),
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionType.HARD_CUT, plan.type)
    }

    @Test
    fun `half blend carries its stretch`() {
        val plan = planTransition(
            analysis = flatTrack(90.0, 200.0),
            nextAnalysis = flatTrack(135.0, 180.0),
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionType.HALF_TIME_BLEND, plan.type)
        assertEquals(90.0 / 135.0, plan.halfTimeStretch, 1e-4)
    }
}
