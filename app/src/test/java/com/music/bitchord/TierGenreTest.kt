package com.music.bitchord

import com.music.bitchord.playback.smart.GenreClass
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTier
import com.music.bitchord.playback.smart.assessTransitionTier
import com.music.bitchord.playback.smart.genreClass
import com.music.bitchord.playback.smart.matchHarmonicRatio
import com.music.bitchord.playback.smart.mixsetTargetFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2 §1/§5a tier ratios + §9c genre. Pure JVM — synthetic analyses.
 */
class TierGenreTest {

    private fun track(bpm: Double, conf: Double = 0.9, extra: TrackAnalysis.() -> TrackAnalysis = { this }): TrackAnalysis =
        TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 200.0,
            bpm = bpm,
            beatInterval = 60.0 / bpm,
            beatConfidence = conf,
            downbeats = (0..100).map { it * 4 * 60.0 / bpm },
            firstBeat = 0.0,
            key = "C major",
            keyConfidence = 0.9,
        ).extra()

    // --- §5a ratios ---

    @Test
    fun `unison stays BEATMATCHED with ratio 1`() {
        val verdict = assessTransitionTier(track(128.0), track(128.0))
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier)
        assertEquals(1.0, verdict.matchedRatio, 0.0)
    }

    @Test
    fun `90 vs 135 locks HALF_TIME at 3-2`() {
        assertEquals(1.5, matchHarmonicRatio(90.0, 135.0)!!, 1e-9)
        val verdict = assessTransitionTier(track(90.0), track(135.0))
        assertEquals(TransitionTier.HALF_TIME, verdict.tier)
        assertEquals(1.5, verdict.matchedRatio, 1e-9)
    }

    @Test
    fun `128 vs 85 locks HALF_TIME at 2-3`() {
        val verdict = assessTransitionTier(track(128.0), track(85.0))
        assertEquals(TransitionTier.HALF_TIME, verdict.tier)
        assertEquals(2.0 / 3.0, verdict.matchedRatio, 1e-9)
    }

    @Test
    fun `128 vs 96 locks HALF_TIME at 3-4`() {
        val verdict = assessTransitionTier(track(128.0), track(96.0))
        assertEquals(TransitionTier.HALF_TIME, verdict.tier)
        assertEquals(0.75, verdict.matchedRatio, 1e-9)
    }

    @Test
    fun `128 vs 171 locks HALF_TIME at 4-3`() {
        val verdict = assessTransitionTier(track(128.0), track(171.0))
        assertEquals(TransitionTier.HALF_TIME, verdict.tier)
        assertEquals(4.0 / 3.0, verdict.matchedRatio, 1e-6)
    }

    @Test
    fun `100 vs 140 with no clean ratio degrades to DJ_ASSISTED`() {
        assertNull(matchHarmonicRatio(100.0, 140.0))
        val verdict = assessTransitionTier(track(100.0), track(140.0))
        assertEquals(TransitionTier.DJ_ASSISTED, verdict.tier)
    }

    @Test
    fun `half-time lock with weak grids degrades to DJ_ASSISTED`() {
        val verdict = assessTransitionTier(track(90.0, conf = 0.9), track(135.0, conf = 0.3))
        assertEquals(TransitionTier.DJ_ASSISTED, verdict.tier)
    }

    @Test
    fun `octave pair still BEATMATCHED`() {
        val verdict = assessTransitionTier(track(64.0), track(128.0))
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier)
        assertEquals(2.0, verdict.matchedRatio, 1e-9)
    }

    // --- §9c genre ---

    @Test
    fun `128bpm dense grid is ELECTRONIC`() {
        assertEquals(GenreClass.ELECTRONIC, genreClass(track(128.0)))
    }

    @Test
    fun `90bpm dense grid is HIP_HOP`() {
        assertEquals(GenreClass.HIP_HOP, genreClass(track(90.0)))
    }

    @Test
    fun `110bpm sparse grid is POP`() {
        val sparse = track(110.0) { copy(downbeats = listOf(0.0, 60.0, 120.0)) }
        assertEquals(GenreClass.POP, genreClass(sparse))
    }

    @Test
    fun `weak beat confidence is AMBIENT`() {
        assertEquals(GenreClass.AMBIENT, genreClass(track(128.0, conf = 0.2)))
    }

    @Test
    fun `mixset target shifts by genre`() {
        assertEquals(75.0, mixsetTargetFor(GenreClass.HIP_HOP), 1e-9)
        assertEquals(120.0, mixsetTargetFor(GenreClass.AMBIENT), 1e-9)
        assertEquals(90.0, mixsetTargetFor(GenreClass.ELECTRONIC), 1e-9)
    }
}
