package com.music.bitchord

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.alignMixsetExitToIncomingDrop
import com.music.bitchord.playback.smart.bestPartCue
import com.music.bitchord.playback.smart.buildupStart
import com.music.bitchord.playback.smart.mixsetEntryPoint
import com.music.bitchord.playback.smart.mixsetMixOutAnchor
import com.music.bitchord.playback.smart.phrase16Grid
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.snapToPhrase16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part A (80/30 rules) + Part B (Mixset Mode) guarantees: a normal-mode pair
 * plays the outgoing track's floor and enters the incoming track's opening
 * stretch, while mixset cuts early off the best part with a capped entry.
 * Pure JVM — synthetic curves and grids, no device, no analysis.
 */
class MixsetTest {

    private fun curve(
        length: Double,
        step: Double = 1.0,
        energyAt: (Double) -> Double = { 1.0 },
    ): List<EnergySample> {
        val points = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= length) {
            points += EnergySample(t, energyAt(t))
            t += step
        }
        return points
    }

    private fun calmMask(size: Int, value: Double = 0.1): List<Double> =
        List(size) { value }

    private fun strongPair(
        outLength: Double = 200.0,
        inLength: Double = 180.0,
        outEnergyAt: (Double) -> Double = { t -> if (t < 170.0) 1.0 else 0.0 },
        inDropAt: Double? = null,
        inMixIn: List<MixCandidate> = listOf(MixCandidate(100.0, 1.0, "main_drop")),
    ): Pair<TrackAnalysis, TrackAnalysis> {
        val outCurve = curve(outLength, energyAt = outEnergyAt)
        val out = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = outLength,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = (0..(outLength / 2).toInt()).map { it * 2.0 },
            phraseBoundaries = listOf(32.0, 64.0, 96.0, 128.0, 160.0),
            firstBeat = 0.0,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.0,
            pickupTime = 0.5,
            introEndTime = 12.0,
            contentEndTime = outLength,
            mixInTime = 8.0,
            mixOutCandidates = listOf(MixCandidate(170.0, 0.9, "energy_cliff")),
            energyCurve = outCurve,
            vocalActivityMask = calmMask(outCurve.size),
        )
        val inCurve = curve(inLength, energyAt = { t ->
            if (inDropAt != null && t >= inDropAt - 1.0 && t <= inDropAt + 1.0) 3.0 else 1.0
        })
        val next = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = inLength,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = (0..(inLength / 2).toInt()).map { it * 2.0 },
            phraseBoundaries = listOf(32.0, 64.0, 96.0, 128.0),
            firstBeat = 0.0,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.0,
            pickupTime = 0.5,
            introEndTime = 12.0,
            contentEndTime = inLength,
            mixInTime = 8.0,
            mixInCandidates = inMixIn,
            energyCurve = inCurve,
            vocalActivityMask = calmMask(inCurve.size),
        )
        return out to next
    }

    // -- bestPartCue ----------------------------------------------------------

    @Test
    fun bestPartCue_returnsFirstDrop() {
        val length = 200.0
        // A lone local maximum clearing 1.5x the mean past the intro.
        val points = curve(length, energyAt = { t -> if (t == 60.0) 2.5 else 1.0 })
        val analysis = TrackAnalysis(introEndTime = 10.0, energyCurve = points)
        assertEquals(60.0, bestPartCue(analysis)!!, 1e-6)
    }

    @Test
    fun bestPartCue_fallsBackToMaxEnergy() {
        val length = 200.0
        // Loudest moment stays under the 1.5x drop threshold: no drop, peak wins.
        val points = curve(length, energyAt = { t -> if (t == 100.0) 1.2 else 1.0 })
        val analysis = TrackAnalysis(introEndTime = 10.0, energyCurve = points)
        assertEquals(100.0, bestPartCue(analysis)!!, 1e-6)
    }

    @Test
    fun bestPartCue_fallsBackToMixInThenNull() {
        assertEquals(
            12.5,
            bestPartCue(TrackAnalysis(mixInTime = 12.5))!!,
            1e-6,
        )
        assertNull(bestPartCue(TrackAnalysis()))
    }

    // -- Spec 16-bar grid + snap ------------------------------------------------

    @Test
    fun phrase16Grid_stridesSixteenDownbeats() {
        val analysis = TrackAnalysis(
            downbeats = (0..150).map { it * 2.0 },
            beatInterval = 0.5,
        )
        assertEquals(listOf(0.0, 32.0, 64.0, 96.0), phrase16Grid(analysis).take(4))
    }

    @Test
    fun snapToPhrase16_landsOnPhraseStart() {
        val analysis = TrackAnalysis(
            downbeats = (0..150).map { it * 2.0 },
            beatInterval = 0.5,
        )
        // At-or-before by default: 100 sits inside the 96 phrase.
        assertEquals(96.0, snapToPhrase16(analysis, 100.0), 1e-6)
        // Nearest either side on request: 114 belongs to the 128 phrase.
        assertEquals(128.0, snapToPhrase16(analysis, 114.0, preferEarlier = false), 1e-6)
    }

    @Test
    fun mixsetAnchor_tier2_dropPlusTwoPhrases() {
        val length = 300.0
        // Drop at 96 on a flat bed (no cooldown anywhere): tier 1 misses, so
        // the anchor is Drop 1 + 2 spec phrases (2 x 30 s at 128 BPM),
        // snapped to the nearest 16-bar start: 160.
        val points = curve(length, energyAt = { t -> if (t == 96.0) 3.0 else 1.0 })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            bpm = 128.0,
            beatInterval = 0.46875,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..150).map { it * 2.0 },
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_peak", anchor.type)
        assertEquals(160.0, anchor.time, 1e-6)
    }

    @Test
    fun mixsetAnchor_tier3_escapesAllSingingWindow() {
        val length = 320.0
        // Drop at 60 on a flat bed with a voice over every grid point: the
        // calm-aware fallback can only offer singing, so the blind spec 40%
        // (128) wins over cutting on a vocal.
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = List(points.size) { 0.9 },
            downbeats = (0..150).map { it * 2.0 },
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_peak", anchor.type)
        assertEquals(128.0, anchor.time, 1e-6)
    }

    @Test
    fun alignMixsetExit_pullsBackWithinNudge_only() {
        val out = TrackAnalysis(
            beatInterval = 0.5,
            downbeats = (0..150).map { it * 2.0 },
            energyCurve = curve(300.0),
            vocalActivityMask = calmMask(301),
        )
        fun incomingWithDrop(dropAt: Double): TrackAnalysis {
            val points = curve(240.0, energyAt = { t -> if (t == dropAt) 3.0 else 1.0 })
            return TrackAnalysis(
                introEndTime = 10.0,
                energyCurve = points,
                vocalActivityMask = calmMask(points.size),
            )
        }
        // Drop at 130 while A exits at 134: pull back to the 128 phrase
        // start, 6 s inside the nudge budget.
        assertEquals(
            128.0,
            alignMixsetExitToIncomingDrop(out, incomingWithDrop(130.0), 134.0, mixset = true),
            1e-6,
        )
        // Drop at 100 while A exits at 150: a 22 s jump is not a nudge, so
        // A's comedown stands.
        assertEquals(
            150.0,
            alignMixsetExitToIncomingDrop(out, incomingWithDrop(100.0), 150.0, mixset = true),
            1e-6,
        )
        // Normal mode never aligns.
        assertEquals(
            134.0,
            alignMixsetExitToIncomingDrop(out, incomingWithDrop(130.0), 134.0, mixset = false),
            1e-6,
        )
    }

    // -- mixsetMixOutAnchor ----------------------------------------------------

    @Test
    fun mixsetAnchor_waitsPastTargetForNearestCalm() {
        val length = 300.0
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        // Drop at 60 -> floor 120, target 150, cap 180. The grid holds 150
        // itself, but the anchor waits for 152 past the target instead of
        // stopping on it: peaks get room to finish.
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..150).map { it * 2.0 },
            phraseBoundaries = listOf(150.0),
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_peak", anchor.type)
        assertEquals(152.0, anchor.time, 1e-6)
    }

    @Test
    fun mixsetAnchor_waitsForCalmLanding_afterTarget() {
        val length = 300.0
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        // Singing 148-154, calm at the 156 phrase: 146 is closer to the 150
        // target and calm, but the anchor waits the 6 extra seconds for the
        // post-peak landing instead of cutting the chorus short.
        val mask = points.map { p ->
            if (p.time >= 148.0 && p.time <= 154.0) 0.9 else 0.1
        }
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = mask,
            downbeats = (60..90).map { it * 2.0 },
            phraseBoundaries = listOf(146.0, 156.0),
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_peak", anchor.type)
        assertEquals(156.0, anchor.time, 1e-6)
    }

    @Test
    fun mixsetAnchor_shortTrackFallsBackToRescue() {
        // Entry at 60 pushes the 60 s floor past the 100 s track: no window
        // exists, so the anchor degrades to just ahead of the playhead
        // instead of cutting blindly or crashing.
        val length = 100.0
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..50).map { it * 2.0 },
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_rescue", anchor.type)
        assertTrue(anchor.time in 0.0..length)
    }

    @Test
    fun mixsetAnchor_rescuesWhenPlayheadPassedWindow() {
        val length = 300.0
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..150).map { it * 2.0 },
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 200.0)
        assertEquals("mixset_rescue", anchor.type)
        assertEquals(215.0, anchor.time, 1e-6)
    }

    @Test
    fun mixsetAnchor_avoidsSingingLanding() {
        val length = 300.0
        val points = curve(length, energyAt = { t -> if (t == 60.0) 3.0 else 1.0 })
        // Singing around 150 and 160, calm at 140: the ±15 s grid holds all
        // three, and the anchor must not land on a voice.
        val mask = points.map { p ->
            if (kotlin.math.abs(p.time - 150.0) <= 2.0 || kotlin.math.abs(p.time - 160.0) <= 2.0) 0.9 else 0.1
        }
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = mask,
            downbeats = listOf(140.0, 150.0, 160.0),
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals(140.0, anchor.time, 1e-6)
    }

    // -- Part A: 80/30 through the planner ------------------------------------

    @Test
    fun normalMode_playsEightyPercent_capsIncomingAtThirty() {
        val (out, next) = strongPair()
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        // The mix-in candidate sits at 100 s (55%); the 30% ceiling pulls the
        // entry back to ~54 s + one arrangement overlap.
        assertTrue(
            "transition starts at ${plan.transitionStart}, expected >= 159",
            plan.transitionStart >= 159.0,
        )
        assertTrue(
            "incoming cue at ${plan.incomingCueTime}, expected <= 60",
            plan.incomingCueTime <= 60.0,
        )
        assertTrue(
            "incoming handoff at ${plan.incomingHandoffTime}, expected <= 60",
            plan.incomingHandoffTime <= 60.0,
        )
    }

    // -- Part B: mixset through the planner -----------------------------------

    @Test
    fun mixsetAnchor_landsOnFirstCooldown() {
        val length = 300.0
        // Drop at 60, then a cliff into a settled low stretch from 129:
        // the cooldown opens at the 130 grid point, snapped back to the 128
        // phrase start per spec — cuts land on phrase starts, never mid-phrase.
        val points = curve(length, energyAt = { t ->
            when {
                t == 60.0 -> 3.0
                t >= 129.0 -> 0.3
                else -> 1.0
            }
        })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..150).map { it * 2.0 },
            phraseBoundaries = listOf(130.0),
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals("mixset_peak", anchor.type)
        assertEquals(128.0, anchor.time, 1e-6)
    }

    @Test
    fun mixsetAnchor_quietSingingCooldownStillWins() {
        val length = 300.0
        // Same comedown, but a soft vocal sits on it: energy runs the
        // decision, so the cooldown still wins over any loud calm point.
        val points = curve(length, energyAt = { t ->
            when {
                t == 60.0 -> 3.0
                t >= 129.0 -> 0.3
                else -> 1.0
            }
        })
        val mask = points.map { p ->
            if (p.time >= 130.0 && p.time <= 140.0) 0.5 else 0.1
        }
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = mask,
            downbeats = (0..150).map { it * 2.0 },
            phraseBoundaries = listOf(130.0),
        )
        val anchor = mixsetMixOutAnchor(analysis, length, playbackTime = 0.0)
        assertEquals(128.0, anchor.time, 1e-6)
    }

    @Test
    fun buildupStart_findsFootOfRise() {
        val length = 200.0
        // A steady climb from 40 to the 120 peak: the foot sits where the
        // curve first clears 40% of the peak going back — about a third of
        // the way up, not at the peak and not at the inaudible start.
        val points = curve(length, energyAt = { t ->
            if (t in 40.0..120.0) 0.3 + (t - 40.0) * 2.7 / 80.0 else 0.3
        })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..100).map { it * 2.0 },
        )
        assertEquals(66.0, buildupStart(analysis, 120.0)!!, 1e-6)
        // The entry snaps the foot back to the phrase start per spec.
        assertEquals(64.0, mixsetEntryPoint(analysis)!!, 1e-6)
    }

    @Test
    fun buildupStart_flatLineReturnsNull() {
        val length = 180.0
        // A spike on a flat bed is not a buildup: nothing genuinely rises
        // into it, so the entry falls back to the peak itself downstream.
        val points = curve(length, energyAt = { t ->
            if (t >= 119.0 && t <= 121.0) 3.0 else 1.0
        })
        val analysis = TrackAnalysis(
            introEndTime = 10.0,
            energyCurve = points,
            vocalActivityMask = calmMask(points.size),
            downbeats = (0..90).map { it * 2.0 },
        )
        assertNull(buildupStart(analysis, 120.0))
    }

    @Test
    fun mixsetMode_cutsEarly_offBestPart_withCappedEntry() {
        // Outgoing drop at 60 with a hard stop at 150 -> cooldown landing
        // ~150 (below the 160 floor, by design). Incoming spike at 120 on a
        // flat bed (no real buildup) -> entry falls back to the peak, pulled
        // to ~90 by the 50% ceiling.
        val (out, next) = strongPair(
            outEnergyAt = { t ->
                when {
                    t >= 59.0 && t <= 61.0 -> 3.0
                    t < 150.0 -> 1.0
                    else -> 0.0
                }
            },
            inDropAt = 120.0,
            inMixIn = emptyList(),
        )
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            mixset = true,
        )
        assertTrue(
            "mixset should cut before the 80% floor, started at ${plan.transitionStart}",
            plan.transitionStart < 160.0,
        )
        assertTrue(
            "mixset cut should still be near the peak window, started at ${plan.transitionStart}",
            plan.transitionStart >= 120.0,
        )
        assertTrue(
            "mixset entry at ${plan.incomingHandoffTime}, expected <= 95 (50% + overlap)",
            plan.incomingHandoffTime <= 95.0,
        )
    }
}
