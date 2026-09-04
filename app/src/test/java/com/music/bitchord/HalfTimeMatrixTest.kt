package com.music.bitchord

import com.music.bitchord.playback.smart.CompatibilityScore
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTier
import com.music.bitchord.playback.smart.TransitionType
import com.music.bitchord.playback.smart.halfTimeRates
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.selectTransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 §1/§5 HALF_TIME tier routing + shared-tempo math. Pure JVM.
 */
class HalfTimeMatrixTest {

    private fun score(bpm: Double, key: Double, vocal: Double) =
        CompatibilityScore(bpm = bpm, key = key, energy = 1.0, structure = 1.0, vocal = vocal, overall = 0.9)

    @Test
    fun `HALF_TIME arms first regardless of scores`() {
        assertEquals(
            TransitionType.HALF_TIME_BLEND,
            selectTransitionType(score(1.0, 1.0, 1.0), TransitionTier.HALF_TIME, true, true, true),
        )
    }

    @Test
    fun `double-high with drop loops`() {
        assertEquals(
            TransitionType.LOOP_CUT_DROP,
            selectTransitionType(score(1.0, 1.0, 1.0), TransitionTier.BEATMATCHED, true, true, true),
        )
    }

    @Test
    fun `unsyncable echoes out`() {
        assertEquals(
            TransitionType.ECHO_REVERB_OUT,
            selectTransitionType(score(0.2, 1.0, 1.0), TransitionTier.BEATMATCHED, false, false, false),
        )
    }

    @Test
    fun `harmonic blends`() {
        assertEquals(
            TransitionType.HARMONIC_BLEND,
            selectTransitionType(score(0.8, 0.9, 1.0), TransitionTier.BEATMATCHED, false, false, false),
        )
    }

    @Test
    fun `clean pair smooths`() {
        assertEquals(
            TransitionType.SMOOTH_CROSSFADE,
            selectTransitionType(score(0.8, 0.75, 0.7), TransitionTier.BEATMATCHED, false, false, false),
        )
    }

    @Test
    fun `clashing key filters`() {
        assertEquals(
            TransitionType.FILTER_SWEEP,
            selectTransitionType(score(0.8, 0.3, 1.0), TransitionTier.BEATMATCHED, false, false, false),
        )
    }

    @Test
    fun `rest cuts`() {
        assertEquals(
            TransitionType.HARD_CUT,
            selectTransitionType(score(0.6, 0.3, 0.2), TransitionTier.BEATMATCHED, false, false, false),
        )
    }

    // --- shared-tempo math ---

    @Test
    fun `shared grid is the slower tempo`() {
        val (shared, rateA, rateB) = halfTimeRates(90.0, 135.0)
        assertEquals(90.0, shared, 1e-9)
        assertEquals(1.0, rateA, 1e-9)
        assertEquals(90.0 / 135.0, rateB, 1e-9)
    }

    @Test
    fun `faster outgoing slows down instead`() {
        val (shared, rateA, rateB) = halfTimeRates(128.0, 85.0)
        assertEquals(85.0, shared, 1e-9)
        assertEquals(85.0 / 128.0, rateA, 1e-9)
        assertEquals(1.0, rateB, 1e-9)
    }

    @Test
    fun `degenerate bpm fails closed`() {
        assertEquals(Triple(0.0, 1.0, 1.0), halfTimeRates(0.0, 128.0))
    }

    // --- end to end ---

    private fun flatPair(): Pair<TrackAnalysis, TrackAnalysis> {
        fun curve(length: Double): List<EnergySample> {
            val out = mutableListOf<EnergySample>()
            var t = 0.0
            while (t <= length) {
                out += EnergySample(t, 1.0)
                t += 1.0
            }
            return out
        }
        fun track(bpm: Double, length: Double): TrackAnalysis {
            val points = curve(length)
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                duration = length,
                bpm = bpm,
                beatInterval = 60.0 / bpm,
                beatConfidence = 0.9,
                downbeats = (0..100).map { it * 4 * 60.0 / bpm },
                firstBeat = 0.0,
                key = "C major",
                keyConfidence = 0.9,
                contentEndTime = length,
                mixInCandidates = listOf(MixCandidate(100.0, 1.0, "main_drop")),
                mixOutCandidates = listOf(MixCandidate(length - 30.0, 0.9, "energy_cliff")),
                energyCurve = points,
                vocalActivityMask = List(points.size) { 0.1 },
            )
        }
        return track(90.0, 200.0) to track(135.0, 180.0)
    }

    @Test
    fun `90 vs 135 plans a HALF_TIME_BLEND on the shared grid`() {
        val (out, next) = flatPair()
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionType.HALF_TIME_BLEND, plan.type)
        assertEquals(1.5, plan.matchedRatio, 1e-9)
        assertEquals(1.0, plan.outgoingPlaybackRate, 1e-9)
        assertEquals(90.0 / 135.0, plan.incomingPlaybackRate, 1e-4)
        assertEquals(90.0, plan.outgoingBpm, 1e-9)
        assertTrue("fade ${plan.fadeSeconds} should ride the 32 s rail", plan.fadeSeconds <= 32.0 + 1e-9)
    }
}
