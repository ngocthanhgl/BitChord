/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), merging its
 * TransitionPlanner.kt and WsolaPlanner.kt into one file.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import com.music.bitchord.data.TrackLog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

private const val PLANNER_TAG = "BitChordTransitionPlanner"

/**
 * Turns stored analysis into a concrete transition plan for one pair of
 * tracks.
 *
 * Nothing here touches PCM; the planner decides *where* a transition happens
 * and *how* ambitious it is. [CrossfadeController] is what executes a plan: it
 * reads the timing fields ([TransitionPlan.transitionStart], [TransitionPlan.fadeSeconds]),
 * cues the incoming track to [TransitionPlan.incomingCueTime] instead of 0,
 * stretches it by [TransitionPlan.incomingPlaybackRate] to align tempo, and
 * renders [TransitionPlan.transitionStyle] as filtering across the blend —
 * a closing low-pass over the outgoing track for [TransitionStyle.DJ_FILTER],
 * a low-end handover at [TransitionPlan.bassSwapFraction] for
 * [TransitionStyle.DJ_BLEND]. The gain curve underneath is equal-power in every
 * case; see [com.music.bitchord.playback.TransitionFilterProcessor].
 */

/** Which crossfade behaviour the listener asked for. */
enum class CrossfadeMode { STANDARD, SMART }

/**
 * The minimal facts about a queue item the planner needs, independent of
 * Media3's `MediaItem` — kept separate so this file stays pure and testable
 * without constructing one.
 */
data class TransitionTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
)

/**
 * Four bars. Overlaps are counted in beats because that is what the ear
 * hears; the seconds values are rails for tempi where four bars would be
 * absurd, not the primary control. Eight to sixteen beats is the range the
 * automatic-DJ literature reports for stable dance material, and less for
 * dense pop.
 */
private const val AUTO_TRANSITION_MAX_BEATS = 16.0
private const val AUTO_MIN_SECONDS = 4.0
private const val AUTO_FAST_TRACK_MIN_SECONDS = 6.0
private const val AUTO_FALLBACK_SECONDS = 8.0

/** Below this a track would spend too much of itself transitioning to be worth planning. */
private const val MIN_SMART_DURATION_SECONDS = 45.0

/**
 * Narrowest overlap the 80%-play floor may squeeze a transition down to. When
 * the anchor sits so close above the floor that even this does not fit, the
 * rail stays off and the anchor's own placement stands.
 */
private const val MIN_TRANSITION_OVERLAP_SECONDS = 4.0

/**
 * Blueprint §5.7 per-archetype duration budgets, in beats. The old single
 * ceiling (16 beats / 12 s) would strangle the blueprint's long blends — a
 * 32-bar harmonic blend alone is 128 beats — so each archetype gets its own
 * budget and the rails below stay as the safety net.
 */
private fun maxBeatsFor(type: TransitionType): Double = when (type) {
    TransitionType.SMOOTH_CROSSFADE -> 128.0
    TransitionType.HARMONIC_BLEND -> 256.0
    TransitionType.FILTER_SWEEP -> 64.0
    TransitionType.ECHO_REVERB_OUT -> 48.0
    TransitionType.LOOP_CUT_DROP -> 24.0
    TransitionType.HARD_CUT -> 1.0
    // v2 §5: HALF_TIME_BLEND runs 16–32 bars on the shared grid.
    TransitionType.HALF_TIME_BLEND -> 128.0
    // v2 §9a: a dissolve is a 2–4 s cut, never a bed.
    TransitionType.PLAIN_DISSOLVE -> 16.0
}

/**
 * Finetune-overlap §Fix 1: DJ Mode per-type overlap ceilings in beats. The
 * old flat 16-beat cap (MIXSET_MAX_BEATS, deleted) strangled every blend to
 * ~7.5 s @128 BPM while the EQ tables are designed for 18–30 s beds.
 */
private fun djModeMaxBeats(type: TransitionType): Double = when (type) {
    TransitionType.SMOOTH_CROSSFADE -> 48.0
    TransitionType.HARMONIC_BLEND -> 64.0
    TransitionType.FILTER_SWEEP -> 32.0
    TransitionType.ECHO_REVERB_OUT -> 24.0
    TransitionType.LOOP_CUT_DROP -> 16.0
    TransitionType.HARD_CUT -> 1.0
    TransitionType.HALF_TIME_BLEND -> 48.0
    TransitionType.PLAIN_DISSOLVE -> 12.0
}

/** Hard safety net no transition may exceed, however generous its budget. */
private const val ABSOLUTE_MAX_TRANSITION_SECONDS = 90.0

/** Leave enough incoming material after a calibrated handoff to avoid landing in its outro. */
private const val MIN_INCOMING_CLEARANCE_SECONDS = 5.0

private val KEY_INDEX = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
    "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
    "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

/** Anything matching this is spoken or already a performance; mixing it is never wanted. */
private val BLOCKED_TEXT = Regex(
    """\b(podcast|episode|audiobook|live|concert|performance)\b""",
    RegexOption.IGNORE_CASE,
)

/** How the renderer should execute a planned transition. */
enum class TransitionStyle {
    /** A constant-power fade, unfiltered. The only style the bottom tier permits. */
    EQUAL_POWER,

    /** Album siblings played through: a near-instant handoff, not a mix. */
    GAPLESS,

    /** Beat-aligned blend with a bass swap, for matching or near-matching tempi. */
    DJ_BLEND,

    /** Filtered handoff for tempi too far apart to blend flat. */
    DJ_FILTER,

    /** Blueprint §5.7: the pair cannot sync, so the outgoing track decays
     * behind echo/reverb while the incoming one fades in dry. */
    ECHO_REVERB_OUT,

    /** Blueprint §5.7: both tracks at full energy — loop the outgoing tail,
     * freeze it, cut, and land the incoming track on its drop. */
    LOOP_CUT_DROP,

    /** Blueprint §5.7: no blend at all — a click-free cut exactly on a downbeat. */
    HARD_CUT,

    /** Spec v2 §9a: mismatch dissolve — a linear 2–4 s fade across a silence
     * gap or low-energy seam, carried by reverb rather than by beat sync.
     * Filters stay open; the gains and the reverb sends do the work. */
    PLAIN_DISSOLVE,
}

/**
 * Blueprint §4: the six DJ transition archetypes. The planner decides the
 * archetype ([TransitionType]); [TransitionStyle] is how the renderer voices
 * it. The two stay separate so one archetype can change rendering without
 * re-planning the pair.
 */
enum class TransitionType {
    SMOOTH_CROSSFADE,
    HARMONIC_BLEND,
    FILTER_SWEEP,
    ECHO_REVERB_OUT,
    LOOP_CUT_DROP,
    HARD_CUT,
    /** v2 §1: beat-synced blend across a harmonic tempo ratio, both decks on a shared BPM. */
    HALF_TIME_BLEND,
    /** v2 §9a: unsyncable pair cut at silence/a break point with a short linear dissolve. */
    PLAIN_DISSOLVE,
}

/** Blueprint §4 volume automation shapes. */
enum class VolumeCurve {
    /** The existing equal-power sin/cos ride. */
    S_CURVE,

    /** Fast early decay of the outgoing track; the echo tail covers the hole. */
    LOGARITHMIC,

    /** Volumes step at the cut point; the 0.1s window itself stays click-free. */
    INSTANT,

    /**
     * v2 §9a: straight-line dissolve for sync-less cuts. Equal-power over a
     * silence gap sums to a loudness bump in the middle; linear doesn't.
     */
    LINEAR,
}

/** Blueprint §4 EQ automation shapes. */
enum class EQCurve {
    EQ_SWAP,
    BASS_SWAP,
    TREBLE_SWAP,
    NONE,
}

/**
 * The planned transition for one pair of tracks, in outgoing-track timeline
 * seconds.
 *
 * A plan is produced on every tick; [shouldStart] is what says the playhead
 * has actually reached it. [markerVisible] is separate because a future UI
 * may want to draw the upcoming transition before it begins. When [blocked]
 * is true nothing should happen at all and [reason] says why.
 */
data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    /** Where in the incoming track playback should be cued to when the transition opens. */
    val incomingCueTime: Double = 0.0,
    /** Where the incoming track's arrangement lands, on its own timeline. */
    val incomingHandoffTime: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,
    /**
     * How strongly the two tracks are expected to be singing over each other
     * through this overlap, 0..1; see [vocalOverlapAmount].
     *
     * Separate from [filterSweep] because they answer to different things.
     * [filterSweep] is a property of the *style* — a filter ride is what an
     * unmatched pair gets instead of a beat-matched blend — and a blend
     * deliberately asks for none of it. This is a property of the *material*, and
     * it applies whatever the style: two tempo-matched vocals sitting on the same
     * grid is the case a blend handles worst, precisely because nothing about the
     * arrangement is going to separate them.
     *
     * Zero whenever either track lacks a vocal mask, which leaves every style
     * rendering exactly as it did before this existed.
     */
    val vocalOverlap: Double = 0.0,
    /** Blueprint §5.6: which archetype this plan implements. */
    val type: TransitionType = TransitionType.SMOOTH_CROSSFADE,
    /** Blueprint §6: the five sub-scores and weighted overall behind [type]. */
    val score: CompatibilityScore = CompatibilityScore(),
    /** Blueprint §5.7 ECHO_REVERB_OUT: peak echo/reverb wet 0..1 on the outgoing track. */
    val echoAmount: Double = 0.0,
    /** Blueprint §5.7 LOOP_CUT_DROP: how many bars of the outgoing tail loop before the freeze. */
    val loopBars: Int = 0,
    /** Blueprint §5.7 LOOP_CUT_DROP: where the incoming track lands, on its own timeline. */
    val dropCueTime: Double = 0.0,
    /** Blueprint §5.2: semitone shift of the incoming track (±[MAX_KEY_SHIFT_SEMITONES]), 0 = none. */
    val keyShiftSemitones: Int = 0,
    /** Blueprint §4 volume automation shape for this plan. */
    val volumeCurve: VolumeCurve = VolumeCurve.S_CURVE,
    /** Blueprint §4 EQ automation shape for this plan. */
    val eqCurve: EQCurve = EQCurve.NONE,
    /**
     * The tempi the overlap is built on, which are **not** the analyses' raw
     * BPMs: the incoming one has been folded into the outgoing one's octave.
     * Zero when the plan is not beat-matched.
     */
    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,
    /** Why the policy landed where it did, when it declined to be more ambitious. */
    val policyReasons: List<String> = emptyList(),
    /** v2 §5a: harmonic tempo ratio locking the pair (1.0 = unison). */
    val matchedRatio: Double = 1.0,
    /** v2 §7d: outgoing deck speed for HALF_TIME (1.0 otherwise). */
    val outgoingPlaybackRate: Double = 1.0,
    /** v2 §9: peak reverb wet on the outgoing track (0 = dry). */
    val reverbAmount: Double = 0.0,
    /** v2 §9b: transition-relative second to freeze the reverb tail, null = no freeze. */
    val reverbFreezeAtSec: Double? = null,
    /** v2 §9b: seconds after transitionStart before the incoming track starts. */
    val incomingStartDelaySec: Double = 0.0,
    /** v2 §9b: seconds after transitionStart the outgoing track holds full level. */
    val outgoingHoldSec: Double = 0.0,
    /**
     * v2 §7d: downbeat emphasis offsets in transition-elapsed seconds, on the
     * ADJUSTED (stretched) grid — the executor pulses the low-pass there.
     * Empty when no grid survives the stretch.
     */
    val halfTimeEmphasis: List<Double> = emptyList(),
    /**
     * v2 §7a/T6: overlap length in seconds, as the plan sized it. The render
     * rides read it back (mid-kill gate, reverb envelope windows) because
     * Render carries no span of its own.
     */
    val overlapSeconds: Double = 0.0,
    /**
     * Spec finetune §7: the incoming deck's stretch in a HALF_TIME blend
     * (rateB from [halfTimeRates], 1.0 = unison). Carried for tests and logs:
     * the executor already voices it via incomingPlaybackRate.
     */
    val halfTimeStretch: Double = 1.0,
    /**
     * Spec finetune §4.1: true when this plan is a manual-fade fallback, not
     * a matrix decision — the per-type ceilings never apply to it. Tests use
     * it to exempt the standard path from the ceiling map.
     */
    val standardTransitionUsed: Boolean = false,
    /**
     * Full-audit P1 M3: set by the vocal choke ([applyMixsetFireFloor]) when
     * it flips S_CURVE→LOGARITHMIC on a vocal-heavy blend. The EQ tables were
     * timed against the S-curve's slow middle; under LOG the gain drops
     * before the mid-duck arrives unless the duck-voiced key set is selected.
     * The renderer ORs this into duckAMids/delayBMids.
     */
    val forceDuckKeys: Boolean = false,
    /**
     * Full-audit P1 M4: echo repeat period in beats (0.5 = half-beat dub,
     * 1.0 = one-bar repeats), null = the renderer's default rule. heavyClash
     * and echoOut share a style but not a period; deriving it from
     * outgoingBpm alone rendered both as half-beat dubs.
     */
    val echoPeriodBeats: Double? = null,
) {
    /** Convenience for the engine, which schedules in milliseconds. */
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}

private fun blocked(reason: String, transitionStart: Double = 0.0, transitionEnd: Double = 0.0) =
    TransitionPlan(
        blocked = true,
        reason = reason,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
    )

/**
 * Blueprint §5.6 decision matrix, verbatim order: loop the double-high pair
 * with a drop ahead, echo out the unsyncable one, blend the harmonic one,
 * smooth the clean one, filter the clashing one, cut the rest.
 *
 * v2 §5c: HALF_TIME pairs arm first (they beat-sync by construction), and the
 * matrix runs for BEATMATCHED + HALF_TIME — PLAIN is dissolved upstream.
 * Spec finetune §7.1: DJ_ASSISTED runs a LIMITED matrix — no stretch may run,
 * so never SMOOTH or HARMONIC: echo out the unsyncable, filter-mask the
 * phase drift when the key agrees, cut the rest. [highEnergyB] is read at
 * the incoming buildup start, not at the proxy entry.
 */
fun selectTransitionType(
    score: CompatibilityScore,
    tier: TransitionTier,
    highEnergyA: Boolean,
    highEnergyB: Boolean,
    hasDropInB: Boolean,
    // Phase B3: best ranked mix-in/out rankScore (NEG_INF = fallback cue, no
    // ranked evidence). Weak ends downgrade one step toward a wash; strong
    // ends never upgrade (evidence argues caution, not daring).
    introQuality: Double = 0.0,
    outroQuality: Double = 0.0,
    // Phase B4: energy direction of the pair (see energyTrajectoryFor).
    trajectory: EnergyTrajectory = EnergyTrajectory.FLAT,
): TransitionType {
    // Phase B3/B4: one-step wash downgrade for a FILTER verdict whose ends
    // are weak (buried/vetoed rank or unranked fallback) or whose risers
    // fight each other.
    fun washDowngrade(): TransitionType =
        if (introQuality < 0 || outroQuality < 0 ||
            trajectory == EnergyTrajectory.A_UP_B_UP
        ) {
            TransitionType.ECHO_REVERB_OUT
        } else {
            TransitionType.FILTER_SWEEP
        }
    if (tier == TransitionTier.DJ_ASSISTED) {
        return when {
            score.bpm < 0.60 -> TransitionType.ECHO_REVERB_OUT
            score.key >= 0.70 -> TransitionType.FILTER_SWEEP
            else -> TransitionType.HARD_CUT
        }
    }
    // Phase B2: the harmonic verdict keeps its blend; the near-harmonic band
    // below it keeps SMOOTH for singing pairs (thresholds unchanged) and
    // earns the filter for clean ones — a slight key rub under a sweep reads
    // as tension, under a cut as a mistake. That cut was the perverse hole.
    if (score.bpm >= 0.70 && score.key >= 0.85) return TransitionType.HARMONIC_BLEND
    if (score.bpm >= 0.70 && score.key >= 0.70) {
        return if (score.vocal >= 0.60) TransitionType.SMOOTH_CROSSFADE else washDowngrade()
    }
    return when {
        tier == TransitionTier.HALF_TIME -> TransitionType.HALF_TIME_BLEND
        highEnergyA && highEnergyB && hasDropInB -> TransitionType.LOOP_CUT_DROP
        score.bpm < 0.50 -> TransitionType.ECHO_REVERB_OUT
        // Phase B1: the old dead band (0.50–0.70) fell through to HARD_CUT.
        // A supported key earns the closing filter; an unsupported one washes.
        score.bpm < 0.70 && score.key >= 0.70 -> washDowngrade()
        score.bpm < 0.70 -> TransitionType.ECHO_REVERB_OUT
        score.key < 0.70 -> TransitionType.FILTER_SWEEP
        else -> TransitionType.HARD_CUT
    }
}

/**
 * v2 §7d: shared tempo and per-deck speeds for a HALF_TIME pair. The shared
 * grid is the GEOMETRIC mean (Review v2.1 B7) — both decks split the log
 * distance, each stretching by √ratio, instead of pegging at the slower
 * tempo and making one deck do all the work. [matchedRatio] is oriented
 * outgoing→incoming (see [matchHarmonicRatio]), so
 * `bpmA·√ratio == √(bpmA·bpmB)` up to stretch deviation; it defaults to 1.0
 * (no half-time relation) which reduces to the slower-side choice.
 * Returns (sharedBpm, rateA, rateB). Pure — tested directly.
 */
fun halfTimeRates(bpmA: Double, bpmB: Double, matchedRatio: Double = 1.0): Triple<Double, Double, Double> {
    if (bpmA <= 0 || bpmB <= 0) return Triple(0.0, 1.0, 1.0)
    val shared = bpmA * sqrt(matchedRatio.coerceAtLeast(1e-9))
    if (!(shared > 0)) return Triple(0.0, 1.0, 1.0)
    return Triple(shared, shared / bpmA, shared / bpmB)
}

/**
 * Spec v2 §9a: the silence-gap cutter, pure and unit-testable. Scans
 * [scanFrom]..[contentEnd] for (1) a silence gap (RMS below
 * [SILENCE_RMS_THRESHOLD] for at least [SILENCE_MIN_DURATION_SECONDS]) —
 * returns its start with a 2 s dissolve; (2) a structure-map BREAK label —
 * its start, 2 s; (3) the minimum-energy 4-bar window — its start, 4 s;
 * (4) otherwise contentEnd − 8 s with a 4 s dissolve.
 *
 * No grid snapping: a dissolve lands where the music stops, not where the
 * grid says a bar should start.
 *
 * @return the cut point and the dissolve duration, both in seconds.
 */
fun findPlainCutPoint(analysis: TrackAnalysis, scanFrom: Double, contentEnd: Double): Pair<Double, Double> {
    val curve = analysis.energyCurve.filter { it.time.isFinite() && it.energy.isFinite() && it.energy >= 0 }
    if (curve.isEmpty() || contentEnd <= scanFrom) {
        return (contentEnd - 8.0) to 4.0
    }
    // (1) Silence gap: first run of sub-threshold samples spanning the minimum.
    var runStart: Double? = null
    for (point in curve) {
        if (point.time < scanFrom || point.time > contentEnd) continue
        if (point.energy < SILENCE_RMS_THRESHOLD) {
            if (runStart == null) runStart = point.time
            if (point.time - runStart >= SILENCE_MIN_DURATION_SECONDS) {
                return runStart to 2.0
            }
        } else {
            runStart = null
        }
    }
    // (1b) Spec finetune §7.4: the track's own breath — longest onset gap in
    // the tail, computed once in detectStructure. A gap without true silence
    // still breathes; the 1-beat lookahead is baked into the stored value.
    analysis.plainCutBreathSec
        ?.takeIf { it.isFinite() && it in scanFrom..contentEnd }
        ?.let { return it to 2.0 }
    // (2) Structure-map BREAK.
    analysis.structureMap
        .filter { it.type == StructureSectionType.BREAK && it.start.isFinite() }
        .map { it.start }
        .filter { it in scanFrom..contentEnd }
        .minOrNull()
        ?.let { return it to 2.0 }
    // (3) Minimum-energy 4-bar window.
    val beatSeconds = analysis.beatInterval.orZero()
        .takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
    val window = beatSeconds * 16
    var bestStart: Double? = null
    var bestMean = Double.POSITIVE_INFINITY
    var t = scanFrom
    while (t + window <= contentEnd) {
        val inWindow = curve.filter { it.time >= t && it.time < t + window }
        if (inWindow.isNotEmpty()) {
            val mean = inWindow.sumOf { it.energy } / inWindow.size
            if (mean < bestMean) {
                bestMean = mean
                bestStart = t
            }
        }
        t += window / 2
    }
    bestStart?.let { return it to 4.0 }
    // (4) Last resort.
    return (contentEnd - 8.0) to 4.0
}

/**
 * Spec v2 §9a PLAIN_DISSOLVE (plan side; the LINEAR gains and reverb rides
 * voice in T5/C6). For pairs the evidence cannot sync: cut at the gap,
 * not on the grid.
 */
internal fun plainDissolvePlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixset: Boolean,
    reasons: List<String>,
    score: CompatibilityScore = CompatibilityScore(),
    candidateShift: Int? = null,
): TransitionPlan {
    val contentEnd = analysis.contentEndTime.orZero().takeIf { it > 0 } ?: length
    val audibleStart = audibleStartOf(analysis)
    val scanFrom = max(audibleStart + 0.5 * length, contentEnd - 45.0)
    val (cutSec, dissolveSec) = findPlainCutPoint(analysis, scanFrom, contentEnd)
    val transitionStart = max(0.0, cutSec - dissolveSec)
    // Spec: the incoming track's entry is its audible start — in Mixset Mode
    // the buildup point, which for an unsyncable pair is the same thing:
    // wherever this track first makes sound.
    val entry = if (mixset) {
        mixsetEntryPoint(nextAnalysis) ?: incomingAudibleStart(nextAnalysis)
    } else {
        incomingAudibleStart(nextAnalysis)
    }
    val keyShift = if (analysis.key.isNotBlank() && nextAnalysis.key.isNotBlank() &&
        analysis.keyConfidence.orZero() >= TRUSTED_PITCH_CONFIDENCE &&
        nextAnalysis.keyConfidence.orZero() >= TRUSTED_PITCH_CONFIDENCE &&
        !pitchVetoesShift(nextAnalysis.vocalPitchMedianHz, nextAnalysis.key)
    ) {
        candidateShift ?: semitonesToShift(analysis.key, nextAnalysis.key)
    } else {
        0
    }
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = cutSec,
        fadeSeconds = cutSec - transitionStart,
        transitionStyle = TransitionStyle.PLAIN_DISSOLVE,
        incomingCueTime = entry,
        incomingHandoffTime = entry,
        handoffStartSeconds = transitionStart,
        handoffDuration = dissolveSec,
        type = TransitionType.PLAIN_DISSOLVE,
        score = score,
        overlapSeconds = cutSec - transitionStart,
        reverbAmount = PLAIN_DISSOLVE_REVERB_WET,
        keyShiftSemitones = keyShift,
        volumeCurve = VolumeCurve.LINEAR,
        policyReasons = reasons,
        reason = if (started) "smart-plain-dissolve" else "before-plain-dissolve",
    )
}

/**
 * v2 §5/§7d HALF_TIME_BLEND planner. Both decks stretch to the shared BPM
 * (see [halfTimeRates]); the overlap runs 24 bars full / 8 bars short on the
 * 32 s safety rail, bass swaps normally, pitch shifts when the key pair sits
 * in the shift window, and downbeat emphasis offsets ride along for the
 * executor's low-pass pulse (§11.2: recomputed from the ADJUSTED grid, never
 * the raw one, so stretched downbeats land on time).
 */
private fun halfTimeBlendPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixAnchor: Double,
    entry: Double,
    score: CompatibilityScore,
    policy: TransitionPolicyVerdict,
    short: Boolean,
    mixset: Boolean,
): TransitionPlan {
    val bpmA = analysis.bpm.orZero()
    val bpmB = nextAnalysis.bpm.orZero()
    val (shared, rateA, rateB) = halfTimeRates(bpmA, bpmB, policy.matchedRatio)
    if (shared <= 0) {
        return hardCutPlan(
            analysis, nextAnalysis, length, nextLength,
            playbackTime, mixAnchor, score, policy.reasons, mixset,
        )
    }
    val bars = if (short) 8 else 24
    val sharedBeat = 60.0 / shared
    val halfCeiling = if (mixset) djModeCeilingFor(TransitionType.HALF_TIME_BLEND) else ceilingFor(TransitionType.HALF_TIME_BLEND)
    val fadeSec = minOf(bars * 4 * sharedBeat, halfCeiling)
        .coerceAtLeast(MIN_TRANSITION_OVERLAP_SECONDS)
    val transitionStart = max(0.0, mixAnchor - fadeSec)
    val keyShift = if (analysis.key.isNotBlank() && nextAnalysis.key.isNotBlank() &&
        keyScore(analysis.key, nextAnalysis.key) in 0.45..0.75 &&
        !(nextAnalysis.pitchConfidence >= TRUSTED_PITCH_CONFIDENCE &&
            pitchVetoesShift(nextAnalysis.vocalPitchMedianHz, nextAnalysis.key))
    ) {
        policy.candidateShiftSemitones
    } else {
        0
    }
    // Emphasis offsets in transition-elapsed seconds: B's phrase starts
    // mapped through its deck rate (B-timeline advances rateB×real time).
    val emphasis = phrase16Grid(nextAnalysis)
        .filter { it.isFinite() && it >= entry && it <= entry + fadeSec * rateB + sharedBeat }
        .map { (it - entry) / rateB }
        .filter { it >= 0 }
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixAnchor,
        fadeSeconds = mixAnchor - transitionStart,
        transitionStyle = TransitionStyle.DJ_BLEND,
        incomingCueTime = entry,
        incomingHandoffTime = entry,
        incomingPlaybackRate = (rateB * 10000).roundToInt() / 10000.0,
        outgoingPlaybackRate = (rateA * 10000).roundToInt() / 10000.0,
        handoffStartSeconds = transitionStart + fadeSec * 0.25,
        handoffDuration = fadeSec * 0.5,
        transitionBeats = (fadeSec / sharedBeat).roundToInt(),
        bassSwap = true,
        type = TransitionType.HALF_TIME_BLEND,
        score = score,
        overlapSeconds = fadeSec,
        keyShiftSemitones = keyShift,
        volumeCurve = VolumeCurve.S_CURVE,
        eqCurve = EQCurve.BASS_SWAP,
        outgoingBpm = shared,
        incomingBpm = shared,
        matchedRatio = policy.matchedRatio,
        halfTimeStretch = (rateB * 10000).roundToInt() / 10000.0,
        halfTimeEmphasis = emphasis,
        policyReasons = policy.reasons,
        reason = if (started) "smart-half-time-blend" else "before-half-time-blend",
    )
}

/**
 * v2 §9b heavy-clash forced echo-out (plan side; the envelope voices in T5).
 * A holds full level 3 s, fades 3–6 s; B starts at +4 s and ramps 4 s; reverb
 * rises to 0.80 with a freeze at +3 s. 8 s window ending at the anchor: the
 * last 2 s of A's tail ring out as echo/reverb rather than content.
 */
private fun heavyClashPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixAnchor: Double,
    entry: Double,
    score: CompatibilityScore,
    reasons: List<String>,
    mixset: Boolean,
): TransitionPlan {
    val fadeSec = 8.0
    val transitionStart = max(0.0, mixAnchor - fadeSec)
    val started = playbackTime >= transitionStart
    // Vocal gate: the clash that summoned this plan grades its own wash. A
    // full double-chorus collision gets the floor (×0.35) — maximum words
    // need minimum wash — while a mild brush keeps most of it. The renderer
    // releases the rest as B enters, so this gate sets the peak, not the tail.
    val clash = plannedVocalOverlap(analysis, nextAnalysis, transitionStart, mixAnchor, entry, 1.0)
        .coerceIn(0.0, 1.0)
    // Full-audit P0.4: unknown is not clean. The graded sense below reads
    // clash=0 as "clean" and grants maximum wash — the loudest echo/reverb
    // tail under B's entry exactly when nothing is known about the clash
    // windows. Unknown gets the floor; measured collisions grade as before.
    val outKnown = vocalActivityBetween(analysis, transitionStart, mixAnchor) != null
    val inKnown = vocalActivityBetween(nextAnalysis, entry, entry + fadeSec) != null
    val clashGate = if (!outKnown || !inKnown) 0.35 else (1.0 - clash).coerceIn(0.35, 1.0)
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixAnchor,
        fadeSeconds = mixAnchor - transitionStart,
        transitionStyle = TransitionStyle.ECHO_REVERB_OUT,
        incomingCueTime = entry,
        incomingHandoffTime = entry,
        handoffStartSeconds = transitionStart + 4.5,
        handoffDuration = 4.0,
        type = TransitionType.ECHO_REVERB_OUT,
        score = score,
        overlapSeconds = fadeSec,
        echoAmount = HEAVY_CLASH_ECHO_AMOUNT * clashGate,
        reverbAmount = HEAVY_CLASH_REVERB_WET * clashGate,
        // Full-audit P1 M4: the dub throw repeats every HALF beat.
        echoPeriodBeats = 0.5,
        reverbFreezeAtSec = HEAVY_CLASH_FREEZE_OFFSET_SEC,
        incomingStartDelaySec = 4.5,
        outgoingHoldSec = 3.0,
        // LOGARITHMIC, not the S-curve: this plan only exists for vocal
        // clashes, and the log's fast early drop clears A's voice before B
        // arrives. (The central choke would do it anyway; stating it here
        // keeps the plan self-describing.)
        volumeCurve = VolumeCurve.LOGARITHMIC,
        policyReasons = reasons,
        reason = if (started) "smart-heavy-clash-echo" else "before-heavy-clash",
    )
}

/**
 * Blueprint §5.7 HARD_CUT: no blend — a click-free 0.1 s handoff exactly on
 * the outgoing track's nearest downbeat. The renderer voices this with open
 * filters and a stepped volume curve.
 */
private fun hardCutPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixAnchor: Double,
    score: CompatibilityScore,
    policyReasons: List<String>,
    mixset: Boolean = false,
): TransitionPlan {
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
    val playFloorSeconds = if (mixset) 0.0 else 0.8 * length
    val cutAt = (nearestTimedValue(analysis.downbeats, mixAnchor, tolerance = beatSeconds * 2)
        ?.coerceIn(0.0, length) ?: mixAnchor.coerceIn(0.0, length))
        .coerceAtLeast(min(playFloorSeconds, length))
    val cue = if (mixset) {
        mixsetEntryCue(nextAnalysis, nextLength)
    } else {
        vocalAwareCutCue(nextAnalysis, nextLength)
    }
    val started = playbackTime >= cutAt
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = cutAt,
        transitionEnd = cutAt + 0.1,
        fadeSeconds = 0.1,
        transitionStyle = TransitionStyle.HARD_CUT,
        type = TransitionType.HARD_CUT,
        score = score,
        incomingCueTime = cue,
        incomingHandoffTime = cue,
        incomingPlaybackRate = 1.0,
        volumeCurve = VolumeCurve.INSTANT,
        eqCurve = EQCurve.NONE,
        policyReasons = policyReasons,
        reason = if (started) "smart-hard-cut" else "before-hard-cut-window",
    )
}

/**
 * Blueprint §5.7 ECHO_REVERB_OUT: an 8–12 bar decay on the outgoing grid
 * while the incoming track fades in running natural — no time-stretch, the
 * tempi are too far apart to sync. Wet scales with the tempo distance.
 */
private fun echoOutPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixAnchor: Double,
    score: CompatibilityScore,
    policyReasons: List<String>,
    mixset: Boolean = false,
): TransitionPlan {
    val bpmOut = analysis.bpm.orZero()
    val beatSeconds = if (bpmOut > 0) 60 / bpmOut else 0.5
    // Full-audit P1: the plan complies with its own ceiling — it sized
    // 32 beats (15 s @128) against an 11 s ceiling and won by never reading
    // it. The renderer sizes wet ramps off the same ceiling; a plan longer
    // than its ceiling desyncs them.
    val typeCeiling = if (mixset) djModeCeilingFor(TransitionType.ECHO_REVERB_OUT)
        else ceilingFor(TransitionType.ECHO_REVERB_OUT)
    val fade = min(32.0 * beatSeconds, min(mixAnchor * 0.6, ABSOLUTE_MAX_TRANSITION_SECONDS))
        .coerceAtMost(typeCeiling)
        .coerceAtLeast(1.0)
    val targetStart = max(0.0, mixAnchor - fade)
    // The track plays its floor: never start the wash before it (normal mode).
    val playFloorSeconds = if (mixset) 0.0 else 0.8 * length
    val transitionStart = if (!mixset && playFloorSeconds < mixAnchor - 1.0) {
        max(
            alignedTransitionStart(
                analysis, targetStart, mixAnchor - 0.05,
                preferEarlier = true, minimum = targetStart,
            ),
            playFloorSeconds,
        )
    } else {
        alignedTransitionStart(
            analysis, targetStart, mixAnchor - 0.05,
            preferEarlier = true, minimum = targetStart,
        )
    }
    val cue = if (mixset) {
        mixsetEntryCue(nextAnalysis, nextLength)
    } else {
        vocalAwareCutCue(nextAnalysis, nextLength)
    }
    val maxHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val handoff = if (nextLength > 0 && maxHandoff >= cue) min(cue, maxHandoff) else cue
    val started = playbackTime >= transitionStart
    // Voiced under the echo DSP cap: the plan never asks for wet the send clamps.
    val echoAmount = ((0.50 - score.bpm) / 0.50).coerceIn(0.3, 0.50)
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixAnchor,
        fadeSeconds = (mixAnchor - transitionStart).coerceAtLeast(0.1),
        transitionStyle = TransitionStyle.ECHO_REVERB_OUT,
        type = TransitionType.ECHO_REVERB_OUT,
        score = score,
        echoAmount = echoAmount,
        // Full-audit P1 M4: one-bar repeats on the outgoing grid — not the
        // half-beat dub the renderer's default rule would voice.
        echoPeriodBeats = 1.0,
        incomingCueTime = cue,
        incomingHandoffTime = handoff,
        incomingPlaybackRate = 1.0,
        transitionBeats = 32,
        bassSwap = false,
        filterSweep = 0.0,
        volumeCurve = VolumeCurve.LOGARITHMIC,
        eqCurve = EQCurve.EQ_SWAP,
        vocalOverlap = plannedVocalOverlap(analysis, nextAnalysis, transitionStart, mixAnchor, cue, 1.0),
        outgoingBpm = bpmOut,
        incomingBpm = 0.0,
        policyReasons = policyReasons,
        reason = if (started) "smart-echo-out" else "before-echo-out-window",
    )
}

/**
 * Blueprint §5.7 LOOP_CUT_DROP: the outgoing track loops its last 4 bars,
 * freezes for 2, then cuts; the incoming track starts early enough to ARRIVE
 * at its drop exactly at the cut and takes over at full volume. Volumes stay
 * stepped ([VolumeCurve.INSTANT]) — the renderer holds the outgoing at full
 * and the incoming at zero until the cut lands.
 */
private fun loopCutPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    playbackTime: Double,
    mixAnchor: Double,
    dropTime: Double,
    score: CompatibilityScore,
    policyReasons: List<String>,
    mixset: Boolean = false,
): TransitionPlan {
    val bpmOut = analysis.bpm.orZero()
    val beatOut = if (bpmOut > 0) 60 / bpmOut else 0.5
    // Full-audit P1: same ceiling compliance as echoOutPlan — 24 beats
    // (11.25 s @128) against an 8 s ceiling.
    val loopCeiling = if (mixset) djModeCeilingFor(TransitionType.LOOP_CUT_DROP)
        else ceilingFor(TransitionType.LOOP_CUT_DROP)
    val windowSec = min(6 * 4 * beatOut, min(mixAnchor * 0.6, ABSOLUTE_MAX_TRANSITION_SECONDS))
        .coerceAtMost(loopCeiling)
        .coerceAtLeast(1.0)
    val playFloorSeconds = if (mixset) 0.0 else 0.8 * length
    val rawStart = max(0.0, mixAnchor - windowSec)
    val transitionStart = if (!mixset && playFloorSeconds < mixAnchor - 1.0) {
        max(
            alignedTransitionStart(
                analysis, rawStart, mixAnchor - 0.05,
                preferEarlier = true, minimum = rawStart,
            ),
            playFloorSeconds,
        )
    } else {
        alignedTransitionStart(
            analysis, rawStart, mixAnchor - 0.05,
            preferEarlier = true, minimum = rawStart,
        )
    }
    val bpmIn = nextAnalysis.bpm.orZero()
    val ratio = if (bpmOut > 0 && bpmIn > 0) normalizedTempoRatio(bpmOut, bpmIn) else 1.0
    val rate = if (ratio in 0.9..1.1) 1.0 / ratio else 1.0
    val dropSnap = nearestTimedValue(nextAnalysis.downbeats, dropTime, tolerance = beatOut * 4)
        ?: dropTime
    val buildInSec = (mixAnchor - transitionStart) * rate
    // Capping only ever moves the start earlier (a longer quiet build into the
    // same drop), never later, so the drop arrival this plan promises holds.
    val cue = capIncomingEntry(max(0.0, dropSnap - buildInSec), nextAnalysis, nextLength, mixset)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixAnchor,
        fadeSeconds = (mixAnchor - transitionStart).coerceAtLeast(0.1),
        transitionStyle = TransitionStyle.LOOP_CUT_DROP,
        type = TransitionType.LOOP_CUT_DROP,
        score = score,
        loopBars = 4,
        dropCueTime = dropSnap,
        incomingCueTime = cue,
        incomingHandoffTime = dropSnap,
        incomingPlaybackRate = rate,
        transitionBeats = 24,
        bassSwap = false,
        filterSweep = 0.0,
        volumeCurve = VolumeCurve.INSTANT,
        eqCurve = EQCurve.NONE,
        vocalOverlap = plannedVocalOverlap(analysis, nextAnalysis, transitionStart, mixAnchor, cue, rate),
        outgoingBpm = bpmOut,
        incomingBpm = if (rate != 1.0) bpmIn / rate else 0.0,
        policyReasons = policyReasons,
        reason = if (started) "smart-loop-cut" else "before-loop-cut-window",
    )
}

private fun trackDurationSeconds(track: TransitionTrackInfo?): Double =
    if (track == null || track.durationMs <= 0) 0.0 else track.durationMs / 1000.0

private fun itemText(track: TransitionTrackInfo?): String =
    if (track == null) "" else listOf(track.title, track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * Gapless is for an album being played through, not for any two songs that
 * happen to share an album. A playlist, a manual queue or a shuffle that
 * lands two album siblings back to back is a mix, and gets mixed; the caller
 * decides which of those it is via `albumSequential` and says so explicitly.
 */
private fun sameAlbum(left: TransitionTrackInfo?, right: TransitionTrackInfo?): Boolean {
    if (left == null || right == null) return false
    if (left.albumId.isNotBlank() && left.albumId == right.albumId) return true
    return left.album.isNotBlank() && left.album == right.album && left.artist == right.artist
}

/** Folds [nextBpm] into the same octave as [currentBpm] and returns the ratio between them. */
private fun normalizedTempoRatio(currentBpm: Double, nextBpm: Double): Double {
    if (currentBpm <= 0 || nextBpm <= 0) return 1.0
    var ratio = nextBpm / currentBpm
    while (ratio > 1.5) ratio /= 2
    while (ratio < 0.67) ratio *= 2
    return ratio
}

private fun splitKey(key: String): Pair<Int?, String?> {
    val parts = key.trim().split(' ')
    return KEY_INDEX[canonicalKeyRoot(parts.firstOrNull())] to parts.getOrNull(1)
}

private fun keyDistance(left: String, right: String): Int? {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return null
    val pitchDistance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    return pitchDistance + if (leftMode != null && rightMode != null && leftMode != rightMode) 1 else 0
}

private fun harmonicallyCompatible(left: String, right: String): Boolean {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return false
    val distance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    if (leftMode != null && rightMode != null && leftMode != rightMode) return distance <= 1
    // A fifth is as close as a second here: it is the move every DJ makes.
    return distance <= 2 || distance == 5
}

/** A key the analyzer was not confident about is no key at all. */
private fun trustedKey(analysis: TrackAnalysis): String =
    if (analysis.key.isBlank() || analysis.keyConfidence < 0.25) "" else analysis.key

private fun nearestTimedValue(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && abs(it - target) <= tolerance }
    .minByOrNull { abs(it - target) }

private fun timedValueNearOrBefore(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && it <= target && target - it <= tolerance }
    .maxOrNull()

/**
 * Snaps a transition start onto the outgoing track's grid: a 16-bar grid
 * point first (Review v2.1 B4 — the spec phrase; the native 8-bar phrases
 * do not always resolve to stable 16-bar forms, so [phrase16Grid] is tried
 * before them), then an 8-bar phrase boundary, a downbeat otherwise, and
 * the raw target when neither is.
 */
private fun alignedTransitionStart(
    analysis: TrackAnalysis,
    target: Double,
    end: Double,
    preferEarlier: Boolean,
    minimum: Double,
): Double {
    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val phrase16Tolerance = max(1.5, interval * 8)
    val phraseTolerance = max(1.0, interval * 4)
    val downbeatTolerance = max(0.75, interval * 2)
    val grid16 = phrase16Grid(analysis)
    val phrase16 = if (preferEarlier) {
        timedValueNearOrBefore(grid16, target, phrase16Tolerance, minimum)
    } else {
        nearestTimedValue(grid16, target, phrase16Tolerance, minimum)
    }
    val phrase = if (preferEarlier) {
        timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    } else {
        nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    }
    val downbeat = if (preferEarlier) {
        timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
    } else {
        nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum)
    }
    return clamp(phrase16 ?: phrase ?: downbeat ?: target, minimum, end)
}

/**
 * Where the incoming track's arrangement arrives: the point the outgoing
 * track should be gone by.
 */
internal fun incomingCuePoint(analysis: TrackAnalysis): Double {
    rankMixInCandidates(analysis).firstOrNull()?.let { return it.time }

    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val downbeats = analysis.downbeats

    val analyzedMixIn = analysis.mixInTime
    if (analyzedMixIn.isFinite() && analyzedMixIn > 0) {
        return nearestTimedValue(downbeats, analyzedMixIn, max(0.5, interval * 2)) ?: analyzedMixIn
    }

    val pickup = max(
        0.0,
        analysis.introEndTime.orZero().takeIf { it != 0.0 }
            ?: (analysis.audibleStartTime ?: analysis.pickupTime).orZero().takeIf { it != 0.0 }
            ?: analysis.firstBeat.orZero(),
    )
    val duration = analysis.duration.orZero().takeIf { it != 0.0 } ?: 300.0
    if (pickup > 0 && pickup < duration - 10) {
        downbeats.firstOrNull { it >= pickup }?.let { return it }
    }
    val phrases = analysis.phraseBoundaries
    if (phrases.size > 1 && phrases[1] > 4) return phrases[1]
    if (downbeats.size >= 8) return downbeats[min(8, downbeats.size - 1)].orZero()
    return pickup
}

/** Where the incoming track first makes sound, so the fade is not cued into its lead-in silence. */
private fun incomingStartPoint(analysis: TrackAnalysis): Double {
    val claimed = listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat)
        .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0
    // Phase A2: the native gate can claim a late start; pull back to the
    // first sustained sound without ever moving past the claimed onset.
    return refinedStartPoint(analysis, claimed)
}

/**
 * Full-audit P0.4: vocal-aware cue for the cut paths (ECHO/HARD). Those cued
 * at audible start — the first sound even when it is a vocal onset — and the
 * echo tail / instant chop smeared the two voices together. Route through
 * the ranked mix-in list (vocal-penalised) like the beat-matched path; when
 * the cue window still sings (or has no mask coverage while the scalar says
 * vocal-heavy), offset past the opening phrase. Bounded by the entry cap.
 */
private fun vocalAwareCutCue(nextAnalysis: TrackAnalysis, nextLength: Double): Double {
    val cue = incomingCuePoint(nextAnalysis)
    val beat = nextAnalysis.beatInterval.takeIf { it > 0 } ?: 0.5
    val windowVocal = vocalActivityBetween(nextAnalysis, cue, cue + beat * 16)
    val sings = windowVocal?.let { it >= VOCAL_ACTIVE_THRESHOLD }
        ?: (nextAnalysis.vocalProbability >= 0.62)
    val offset = if (sings) beat * 16 else 0.0
    return capIncomingEntry(cue + offset, nextAnalysis, nextLength, mixsetActive = false)
}

/**
 * Incoming entries stay in the opening stretch: 30% of the incoming track
 * normally, 50% in Mixset Mode — with a floor at the audible start so a long
 * intro is never cued into silence. Applied where musical targets are chosen
 * (before overlap math derives consistency from them), never to an already
 * derived cue/handoff pair.
 */
private fun capIncomingEntry(
    cue: Double,
    nextAnalysis: TrackAnalysis,
    nextLength: Double,
    mixsetActive: Boolean,
): Double {
    if (!cue.isFinite() || nextLength <= 0) return cue
    // DJ Mode freeform: no part-pick ceiling — the cue may land anywhere
    // mid-track the analysis justifies. Only the audible-start floor stays,
    // so the handoff never aims at silence before the music begins.
    val audible = listOfNotNull(nextAnalysis.audibleStartTime, nextAnalysis.pickupTime)
        .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0
    if (mixsetActive) return max(cue, audible + 2.0)
    // Finetune v1 §3.4: 30% at 5 min = 90 s, past the first chorus — 28%
    // still clears a 64-bar intro at 120 BPM.
    return min(cue, max(0.28 * nextLength, audible + 2.0)).coerceAtLeast(0.0)
}

/**
 * DJ Mode entry: the foot of the buildup, the drop, or the peak — wherever
 * the analysis justifies, mid-track included. Only the audible-start floor
 * applies. The handoff aims here, not at the peak: the peak arrives on its
 * own time after the takeover, which is what lets a long buildup breathe.
 */
private fun mixsetEntryCue(nextAnalysis: TrackAnalysis, nextLength: Double): Double {
    val best = mixsetEntryPoint(nextAnalysis) ?: incomingStartPoint(nextAnalysis)
    // Phase A4: never cue inside a spoken bed — route past its end.
    val span = spokenInterludeSpan(nextAnalysis)
    val routed = if (span != null && best in span) span.endInclusive else best
    return capIncomingEntry(routed, nextAnalysis, nextLength, mixsetActive = true)
}

// ---------------------------------------------------------------------------
// WSOLA-style beat-matched phrase-switch plan (ported from WsolaPlanner.kt)
// ---------------------------------------------------------------------------

// The fade is bounded in beats because overlap length is musical: bounding it
// in seconds makes a faster track get a longer mix, which is backwards. Four
// bars is the ceiling and one bar the floor, the latter for tracks whose
// intro cannot cover more.
private const val MIN_FADE_BEATS = 4
private const val MAX_FADE_BEATS = 16

// A ceiling on the whole overlap regardless of how long the incoming intro is.
private const val MAX_OVERLAP_SECONDS = 16.0

/**
 * Moving both decks by the same musical amount preserves the beat grid and
 * overlap length while putting the incoming arrangement inside the blend
 * instead of making it the finish line. Applied only to a content-end exit on
 * the outgoing side; a real structural/energy exit has already supplied the
 * earlier anchor.
 */
internal const val ARRANGEMENT_OVERLAP_BEATS = 8

/**
 * One continuous equal-power fade across the whole overlap. 0.5/0.5 is the
 * plain symmetric crossfade, which is exactly the sin/cos pair
 * [com.music.bitchord.playback.CrossfadeController] rides — so at these values
 * the renderer already honours them, and anything else would need a two-segment
 * gain curve it does not have.
 */
const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5

/** The prior for where the low end hands over, on a pairing with no useful structural change. */
private const val DEFAULT_BASS_SWAP_FRACTION = 0.7

/** Analysis may move the swap later than the prior, but never so late the outgoing low end survives almost to silence. */
private const val MAX_BASS_SWAP_FRACTION = 0.80

/** A normalized low-band step smaller than this is too weak to move the swap away from its prior. */
private const val MIN_BASS_STRUCTURE_SCORE = 0.25

/** Capped in absolute seconds too, so a long overlap does not scale the hold up with it. */
private const val BASS_SWAP_MAX_SECONDS = 5.5

/**
 * How far the outgoing track's low-pass sweep travels by the end of the
 * overlap, as a fraction of a full ride. 1.0 is the whole way down to
 * [com.music.bitchord.playback.CrossfadeController.FILTER_FLOOR_HZ].
 */
const val FILTER_SWEEP = 1.0

/** The outgoing track must have this much audio before the overlap and the incoming this much after it. */
private const val MIN_CLEARANCE_SECONDS = 5.0

private fun averageLowEnergy(curve: List<EnergySample>, from: Double, until: Double): Double? {
    if (until <= from) return null
    var index = curve.binarySearchBy(from) { it.time }.let { if (it >= 0) it else -it - 1 }
    var sum = 0.0
    var count = 0
    while (index < curve.size && curve[index].time < until) {
        val point = curve[index++]
        if (point.time.isFinite() && point.energy.isFinite() && point.energy >= 0) {
            sum += point.energy
            count++
        }
    }
    return if (count > 0) sum / count else null
}

private fun lowEnergyReference(curve: List<EnergySample>): Double? {
    val energies = curve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
    if (energies.isEmpty()) return null
    val upperDecile = energies[(energies.lastIndex * 0.9).toInt()]
    val reference = max(upperDecile, (energies.lastOrNull() ?: 0.0) * 0.25)
    return reference.takeIf { it > 1e-9 }
}

private fun lowEnergyResolution(curve: List<EnergySample>): Double {
    val gaps = curve.zipWithNext { left, right -> right.time - left.time }
        .filter { it.isFinite() && it > 0 }
        .sorted()
    return gaps.getOrNull(gaps.size / 2) ?: 0.0
}

/** Change in low-band energy across one beat either side of [at], normalized per track. */
private fun lowEnergyChange(
    curve: List<EnergySample>,
    reference: Double?,
    at: Double,
    windowSeconds: Double,
): Double? {
    if (curve.isEmpty() || reference == null || windowSeconds <= 0) return null
    val before = averageLowEnergy(curve, at - windowSeconds, at) ?: return null
    val after = averageLowEnergy(curve, at, at + windowSeconds) ?: return null
    return (after / reference).coerceIn(0.0, 1.5) -
        (before / reference).coerceIn(0.0, 1.5)
}

/** Chooses one shared-grid beat for the low-end handoff. */
private fun bassSwapFractionFor(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    incomingCueTime: Double,
    outgoingBeatSeconds: Double,
    incomingBeatSeconds: Double,
    overlapSeconds: Double,
    overlapBeats: Int,
): Double {
    if (overlapSeconds <= 0) return DEFAULT_BASS_SWAP_FRACTION

    val latestFraction = min(MAX_BASS_SWAP_FRACTION, BASS_SWAP_MAX_SECONDS / overlapSeconds)
        .coerceIn(0.0, 1.0)
    val prior = min(DEFAULT_BASS_SWAP_FRACTION, latestFraction)
    if (overlapBeats < 2) return prior

    val earliestFraction = min(HANDOFF_FRACTION, latestFraction)
    val earliestBeat = ceil(earliestFraction * overlapBeats - 1e-9).toInt()
        .coerceIn(1, overlapBeats - 1)
    val latestBeat = floor(latestFraction * overlapBeats + 1e-9).toInt()
        .coerceIn(earliestBeat, overlapBeats - 1)
    val candidates = (earliestBeat..latestBeat).toList()
    val fallbackBeat = candidates.minWithOrNull(
        compareBy<Int> { abs(it.toDouble() / overlapBeats - prior) }
            .thenBy { if (it % 4 == 0) 0 else 1 },
    ) ?: return prior

    data class BassCandidate(val beat: Int, val score: Double)

    val outgoingReference = lowEnergyReference(analysis.lowEnergyCurve)
    val incomingReference = lowEnergyReference(nextAnalysis.lowEnergyCurve)
    val outgoingWindow = max(
        outgoingBeatSeconds,
        lowEnergyResolution(analysis.lowEnergyCurve) * 1.1,
    )
    val incomingWindow = max(
        incomingBeatSeconds,
        lowEnergyResolution(nextAnalysis.lowEnergyCurve) * 1.1,
    )
    val strongest = candidates.mapNotNull { beat ->
        val outgoingAt = transitionStart + beat * outgoingBeatSeconds
        val incomingAt = incomingCueTime + beat * incomingBeatSeconds
        val incomingChange = lowEnergyChange(
            nextAnalysis.lowEnergyCurve,
            incomingReference,
            incomingAt,
            incomingWindow,
        )
        val outgoingChange = lowEnergyChange(
            analysis.lowEnergyCurve,
            outgoingReference,
            outgoingAt,
            outgoingWindow,
        )
        if (incomingChange == null && outgoingChange == null) return@mapNotNull null
        BassCandidate(beat, (incomingChange ?: 0.0) - (outgoingChange ?: 0.0))
    }.maxWithOrNull(
        compareBy<BassCandidate> { it.score }
            .thenBy { if (it.beat % 4 == 0) 1 else 0 }
            .thenBy { -abs(it.beat.toDouble() / overlapBeats - prior) },
    )

    val chosenBeat = strongest?.takeIf { it.score >= MIN_BASS_STRUCTURE_SCORE }?.beat
        ?: fallbackBeat
    return chosenBeat.toDouble() / overlapBeats
}

/**
 * How vocal the planned overlap is on both sides at once, measured over the
 * windows the plan actually blends.
 *
 * The two windows are not the same length in wall-clock terms whenever the
 * incoming track is being stretched: [incomingPlaybackRate] above 1 means it
 * covers proportionally more of its own timeline in the same number of seconds,
 * so the incoming window is scaled by it rather than copied from the outgoing
 * one. Getting that wrong would measure a window the listener never hears.
 *
 * Answers zero for a degenerate span and for any track without a mask, so every
 * caller can set this unconditionally.
 */
private fun plannedVocalOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    transitionEnd: Double,
    incomingCueTime: Double,
    incomingPlaybackRate: Double,
): Double {
    val outgoingSpan = transitionEnd - transitionStart
    if (outgoingSpan <= 0.0 || !outgoingSpan.isFinite()) return 0.0
    val rate = incomingPlaybackRate.takeIf { it.isFinite() && it > 0 } ?: 1.0
    return simultaneousVocalFraction(
        outgoing = analysis,
        incoming = nextAnalysis,
        outStart = transitionStart,
        outEnd = transitionEnd,
        inStart = incomingCueTime,
        rate = rate,
    ) ?: 0.0
}

private fun nearestAtOrBefore(values: List<Double>, target: Double): Double? =
    values.filter { it.isFinite() && it >= 0 && it <= target }.maxOrNull()

/**
 * The outcome of planning one beat-matched transition.
 *
 * [Refused] is a routing decision, not an error: the caller falls back to the
 * adaptive overlap below, which degrades further on its own.
 */
sealed interface WsolaPlanResult {
    data class Refused(val reason: String) : WsolaPlanResult

    /** All times are seconds on each track's own media timeline. */
    data class Planned(
        val tier: TransitionTier,
        val beatConfidence: Double,
        val mixOutType: String,
        val vocalClash: Boolean,
        val transitionStart: Double,
        val transitionEnd: Double,
        val overlapSeconds: Double,
        val beats: Int,
        val fadeBeats: Int,
        val handoffFraction: Double,
        val bedPosition: Double,
        val bassSwapFraction: Double,
        val filterSweep: Double,
        val outgoingBpm: Double,
        val incomingBpm: Double,
        val stretchRatio: Double,
        val incomingCueTime: Double,
        val incomingDropTime: Double,
        val incomingHandoffTime: Double,
        val incomingResumeTime: Double,
    ) : WsolaPlanResult
}

/** Where the incoming track takes over: the best-ranked mix-in candidate, snapped to a downbeat. */
fun incomingMixInPoint(analysis: TrackAnalysis): Double? {
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val tolerance = max(0.5, beatSeconds * 2)
    val target = listOfNotNull(rankMixInCandidates(analysis).firstOrNull()?.time, analysis.mixInTime)
        .firstOrNull { it.isFinite() && it > 0 }
        ?: return null
    return nearestValue(analysis.downbeats, target, tolerance) ?: target
}

/** Where the incoming track first makes sound. */
fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)

/** Plans one beat-matched transition between [analysis] and [nextAnalysis]. */
fun planWsolaTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    duration: Double = 0.0,
    nextDuration: Double = 0.0,
    mixset: Boolean = false,
    mixAnchorOverride: Double? = null,
): WsolaPlanResult {
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier != TransitionTier.BEATMATCHED) {
        return WsolaPlanResult.Refused(policy.reasons.firstOrNull() ?: "policy")
    }

    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = alignTempoOctave(outgoingBpm, nextAnalysis.bpm.orZero())
    val stretchRatio = outgoingBpm / incomingBpm

    val outgoingLength = max(duration.orZero(), analysis.duration.orZero())
    val incomingLength = max(nextDuration.orZero(), nextAnalysis.duration.orZero())
    if (outgoingLength <= 0 || incomingLength <= 0) return WsolaPlanResult.Refused("missing-duration")

    val incomingBeatSeconds = 60 / incomingBpm
    val outgoingBeatSeconds = 60 / outgoingBpm

    val rawDropTime = if (mixset) {
        mixsetEntryPoint(nextAnalysis) ?: incomingMixInPoint(nextAnalysis)
    } else {
        incomingMixInPoint(nextAnalysis)
    }
    val incomingDropTime = rawDropTime
        ?.takeIf { it.isFinite() && it >= 0 }
        ?.let { capIncomingEntry(it, nextAnalysis, incomingLength, mixset) }
    if (incomingDropTime == null || !incomingDropTime.isFinite() || incomingDropTime < 0) {
        return WsolaPlanResult.Refused("incoming-mix-in")
    }

    val contentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: outgoingLength
    // In Mixset Mode the caller hands down the peak anchor it already
    // resolved — but the override is advisory, not a veto. A fresh resolution
    // that lands earlier (a comedown the upstream pass could not see) wins:
    // cementing a stale override is what dragged exits onto peaks. The
    // override still wins whenever it is the earlier point, so designed early
    // cuts are preserved.
    val resolvedAnchor = resolveMixOutAnchor(analysis, contentEnd = contentEnd, duration = outgoingLength)
    val mixOutAnchor = if (mixset && mixAnchorOverride != null && mixAnchorOverride.isFinite()) {
        val coerced = mixAnchorOverride.coerceIn(0.0, outgoingLength)
        if (resolvedAnchor.time < coerced) resolvedAnchor else MixOutAnchor(
            time = coerced,
            type = "mixset_peak",
            discardedMusicSeconds = max(0.0, outgoingLength - coerced),
        )
    } else {
        resolvedAnchor
    }
    val unshiftedOverlapEnd = min(outgoingLength, mixOutAnchor.time)
    val outgoingArrangementOverlap =
        if (mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * outgoingBeatSeconds, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val overlapEndTarget = max(MIN_CLEARANCE_SECONDS, unshiftedOverlapEnd - outgoingArrangementOverlap)

    val audibleStart = incomingAudibleStart(nextAnalysis)
    val availableFadeBeats = max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds
    // Satisfaction round §1: DJ Mode is long beds, not 7 s bridges. Lift the
    // phrase-switch ceiling when the pair asked for mixset — the intro length
    // still bounds it via availableFadeBeats below, and the clash shrink loop
    // keeps vocals safe.
    val overlapCeilingSeconds = if (mixset) 32.0 else MAX_OVERLAP_SECONDS
    val maxFadeBeats = if (mixset) 48 else MAX_FADE_BEATS
    val cappedByOverlap = floor(floor(overlapCeilingSeconds / incomingBeatSeconds) / 4).toInt() * 4
    if (cappedByOverlap < MIN_FADE_BEATS) return WsolaPlanResult.Refused("overlap-too-long")
    var fadeBeats = minOf(
        maxFadeBeats,
        cappedByOverlap,
        floor(availableFadeBeats / 4).toInt() * 4,
    )
    if (fadeBeats < MIN_FADE_BEATS) fadeBeats = MIN_FADE_BEATS

    fun clashOver(beats: Int): Boolean {
        val outStart = overlapEndTarget - beats * outgoingBeatSeconds
        val inStart = max(audibleStart, incomingDropTime - beats * incomingBeatSeconds)
        val outVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget)
        val inVocal = vocalActivityBetween(nextAnalysis, inStart, incomingDropTime)

        // Instant-by-instant first, because it is the question actually being
        // asked. The mean-based test below only fires when *both* windows average
        // vocal across their whole length, which a real clash routinely does not:
        // an incoming track that starts singing a few seconds into the overlap
        // averages clear and still puts its opening line under the outgoing
        // vocal. This catches that, and it is what shrinks the overlap until the
        // two voices stop landing together.
        val simultaneous = simultaneousVocalFraction(
            outgoing = analysis,
            incoming = nextAnalysis,
            outStart = outStart,
            outEnd = overlapEndTarget,
            inStart = inStart,
            rate = if (outgoingBeatSeconds > 0) incomingBeatSeconds / outgoingBeatSeconds else 1.0,
        )
        if (simultaneous != null && simultaneous > VOCAL_CLASH_TOLERANCE) return true

        if (isVocalClash(outVocal, inVocal)) return true

        if (beats > 8 && outVocal != null && outVocal >= VOCAL_ACTIVE_THRESHOLD) {
            val deepVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget - 8 * outgoingBeatSeconds)
            if (deepVocal != null && deepVocal >= VOCAL_ACTIVE_THRESHOLD) {
                return true
            }
        }
        return false
    }
    var fadeVocalClash = clashOver(fadeBeats)
    while (fadeVocalClash && fadeBeats > MIN_FADE_BEATS) {
        fadeBeats -= 4
        fadeVocalClash = clashOver(fadeBeats)
    }

    val coverableBeats = floor(max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds).toInt()
    val overlapBeats = min(fadeBeats, coverableBeats)
    if (overlapBeats < 1) return WsolaPlanResult.Refused("incoming-no-intro")

    val outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds
    val overlapSeconds = overlapBeats * incomingBeatSeconds

    val requestedIncomingHandoff =
        incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * incomingBeatSeconds
    val maxIncomingHandoff = incomingLength - MIN_CLEARANCE_SECONDS
    if (maxIncomingHandoff < incomingDropTime) return WsolaPlanResult.Refused("incoming-too-short")
    val incomingHandoffTime = min(requestedIncomingHandoff, maxIncomingHandoff)
    val incomingCueTime = incomingHandoffTime - overlapSeconds
    if (incomingCueTime < audibleStart - 0.05) return WsolaPlanResult.Refused("incoming-no-runway")

    val startTarget = overlapEndTarget - outgoingOverlapSeconds
    val transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?: startTarget
    if (transitionStart < MIN_CLEARANCE_SECONDS) return WsolaPlanResult.Refused("outgoing-too-short")
    val transitionEnd = transitionStart + outgoingOverlapSeconds
    if (transitionEnd > outgoingLength + 0.05) return WsolaPlanResult.Refused("outgoing-overlap-overruns")

    val incomingResumeTime = incomingCueTime + overlapSeconds
    if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) {
        return WsolaPlanResult.Refused("incoming-too-short")
    }

    return WsolaPlanResult.Planned(
        tier = policy.tier,
        beatConfidence = policy.beatConfidence,
        mixOutType = mixOutAnchor.type,
        vocalClash = fadeVocalClash,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        overlapSeconds = overlapSeconds,
        beats = overlapBeats,
        fadeBeats = overlapBeats,
        handoffFraction = HANDOFF_FRACTION,
        bedPosition = BED_POSITION,
        bassSwapFraction = bassSwapFractionFor(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            incomingCueTime = incomingCueTime,
            outgoingBeatSeconds = outgoingBeatSeconds,
            incomingBeatSeconds = incomingBeatSeconds,
            overlapSeconds = overlapSeconds,
            overlapBeats = overlapBeats,
        ),
        filterSweep = FILTER_SWEEP,
        outgoingBpm = outgoingBpm,
        incomingBpm = incomingBpm,
        stretchRatio = stretchRatio,
        incomingCueTime = incomingCueTime,
        incomingDropTime = incomingDropTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingResumeTime = incomingResumeTime,
    )
}

/**
 * The most ambitious move available: run the incoming track's instrumental
 * intro underneath the outgoing one and close on its drop. A refusal is a
 * routing decision, not an error: the caller falls back to the adaptive
 * overlap below, which degrades further on its own.
 */
private fun phraseSwitch(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    mixset: Boolean = false,
    mixAnchor: Double = 0.0,
): TransitionPlan? {
    if (!harmonicallyCompatible(trustedKey(analysis), trustedKey(nextAnalysis))) return null

    val planned = planWsolaTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        duration = length,
        nextDuration = nextLength,
        mixset = mixset,
        mixAnchorOverride = mixAnchor.takeIf { mixset },
    ) as? WsolaPlanResult.Planned ?: return null

    val overlap = planned.transitionEnd - planned.transitionStart
    return TransitionPlan(
        markerVisible = true,
        transitionStart = planned.transitionStart,
        transitionEnd = planned.transitionEnd,
        fadeSeconds = overlap,
        handoffStartSeconds = 0.0,
        handoffDuration = overlap,
        incomingCueTime = planned.incomingCueTime,
        incomingHandoffTime = planned.incomingHandoffTime,
        incomingPlaybackRate = (planned.stretchRatio * 10000).roundToInt() / 10000.0,
        pickupSeconds = incomingAudibleStart(nextAnalysis),
        transitionBeats = planned.beats,
        bassSwap = true,
        handoffFraction = planned.handoffFraction,
        bedPosition = planned.bedPosition,
        bassSwapFraction = planned.bassSwapFraction,
        // Deliberately not `planned.filterSweep`. A phrase switch is the one
        // case where both decks are genuinely on the same grid, and the move
        // there is to hand the low end over on a beat, not to hide the outgoing
        // track behind a filter — filtering a blend this well aligned would
        // throw away the reason it was worth aligning. The renderer reads a
        // nonzero sweep as "ride the filter instead", so this says zero.
        filterSweep = 0.0,
        // The separation this style *does* need, and the one it cannot get from
        // alignment. Two tracks on a shared grid are the worst case for
        // overlapping voices precisely because nothing about the arrangement
        // pulls them apart — they sit in the same bar, in the same range, for the
        // whole blend. The renderer uses this to deepen the entry high-pass and
        // the exit low-pass without turning the blend into a filter ride.
        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = planned.transitionStart,
            transitionEnd = planned.transitionEnd,
            incomingCueTime = planned.incomingCueTime,
            incomingPlaybackRate = planned.stretchRatio,
        ),
        outgoingBpm = planned.outgoingBpm,
        incomingBpm = planned.incomingBpm,
        transitionStyle = TransitionStyle.DJ_BLEND,
    )
}

private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

/** How long a mix should run when the tracks are related but not phrase-switchable. */
/**
 * Finetune v1 §4.1 P1: per-type overlap ceilings. 12 s is a radio crossfade,
 * not a DJ blend — smooth/harmonic pairs get real blend room while surgical
 * types (filter/loop/dissolve) stay decisive.
 */
fun ceilingFor(type: TransitionType): Double = when (type) {
    TransitionType.SMOOTH_CROSSFADE -> 22.0
    TransitionType.HARMONIC_BLEND -> 28.0
    TransitionType.FILTER_SWEEP -> 9.0
    TransitionType.ECHO_REVERB_OUT -> 11.0
    TransitionType.LOOP_CUT_DROP -> 8.0
    TransitionType.HARD_CUT -> 0.3
    TransitionType.HALF_TIME_BLEND -> 20.0
    TransitionType.PLAIN_DISSOLVE -> 4.0
}

/**
 * Finetune-overlap: DJ Mode per-type ceilings in seconds. The normal-mode
 * [ceilingFor] would clip the long DJ beds from the inside (a 30 s harmonic
 * blend against a 28 s ceiling, a 15 s sweep against 9 s), so DJ Mode
 * carries its own — each just fits its beat target at 128 BPM, still under
 * the 90 s absolute net.
 */
private fun djModeCeilingFor(type: TransitionType): Double = when (type) {
    TransitionType.SMOOTH_CROSSFADE -> 24.0
    TransitionType.HARMONIC_BLEND -> 32.0
    TransitionType.FILTER_SWEEP -> 16.0
    TransitionType.ECHO_REVERB_OUT -> 12.5
    TransitionType.LOOP_CUT_DROP -> 8.0
    TransitionType.HARD_CUT -> 0.3
    TransitionType.HALF_TIME_BLEND -> 24.0
    TransitionType.PLAIN_DISSOLVE -> 6.0
}

/** Rail fallback: DJ Mode reads its own seconds ceiling, normal mode the classic one. */
private fun djRailCeiling(type: TransitionType, mixset: Boolean): Double =
    if (mixset) djModeCeilingFor(type) else ceilingFor(type)

/**
 * Finetune-overlap §Fix 4: when the EQ is already managing a vocal clash,
 * the overlap is safe to run longer — the mid-duck, not silence, separates
 * the voices. Capped by [djModeMaxBeats] at the call site.
 */
private fun eqOverlapBonusBeats(duckAMids: Boolean, delayBMids: Boolean, type: TransitionType): Int {
    if (type == TransitionType.LOOP_CUT_DROP ||
        type == TransitionType.HARD_CUT ||
        type == TransitionType.PLAIN_DISSOLVE
    ) return 0
    return when {
        duckAMids && delayBMids -> 8
        duckAMids || delayBMids -> 4
        else -> 0
    }
}

private fun adaptiveOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionPoint: Double,
    entryPoint: Double,
    type: TransitionType = TransitionType.SMOOTH_CROSSFADE,
    mixset: Boolean = false,
): Overlap {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    if (currentBpm <= 0 || nextBpm <= 0) {
        return Overlap(AUTO_FALLBACK_SECONDS, 0, 1.0)
    }

    val ratio = normalizedTempoRatio(currentBpm, nextBpm)
    val distance = keyDistance(trustedKey(analysis), trustedKey(nextAnalysis))
    val vocalConflict = analysis.vocalProbability >= 0.62 && nextAnalysis.vocalProbability >= 0.62
    // Finetune-overlap §Fix 2: in DJ Mode the flat 10/20/12 base is replaced
    // by pair-quality tiers — a perfect pair deserves a full bed, a poor one
    // stays short. Normal mode keeps the old bases untouched.
    val baseBeats = if (mixset) {
        val tempoDeviation = abs(1 - ratio)
        when {
            tempoDeviation < 0.02 && (distance == null || distance <= 1) -> 56
            tempoDeviation < 0.04 && (distance == null || distance <= 3) -> 44
            tempoDeviation < 0.06 -> 40
            else -> 28
        }
    } else {
        // Finetune v1 §4.2: more room to mask mismatch (20), a viable minimum
        // (12 beats = 5.6 s @128 BPM), and headroom to duck vocals (10).
        when {
            vocalConflict -> 10
            abs(1 - ratio) > 0.07 || (distance != null && distance > 4) -> 20
            else -> 12
        }
    }
    // v2 §6: scale by arrangement energy direction — an outgoing track that
    // falls while the incoming one rises is the ideal long blend; two risers
    // fighting each other get tightened. Slopes over 16 bars each side.
    val energyFactor = overlapEnergyFactor(analysis, nextAnalysis, transitionPoint, entryPoint)
    val beatSeconds = 60 / currentBpm
    val djBeatCeiling = djModeMaxBeats(type).toInt()
    val bonusBeats = if (mixset) {
        // Satisfaction round §3: the bonus buys ducked seconds, so it is
        // gated on the same ARM-time masks the renderer will arm — A-zone
        // (first 70% of the estimated overlap back from the anchor) and
        // B-entry (first 16 beats from the entry). The old whole-track
        // scalars granted +8 beats to tracks that sing everywhere except
        // inside the overlap, where no ducking would ever fire.
        val estOverlap = (baseBeats * energyFactor).roundToInt() * beatSeconds
        val duckA = vocalActivityBetween(
            analysis, transitionPoint - estOverlap, transitionPoint - estOverlap * 0.30,
        )?.let { it > 0.50 } ?: false
        val entryWindow = nextAnalysis.beatInterval.takeIf { it > 0 }?.times(16) ?: 8.0
        val delayB = vocalActivityBetween(
            nextAnalysis, entryPoint, entryPoint + entryWindow,
            // Full-audit P0.4: same scale as the ARM gate — neutral (0.5)
            // windows must not earn ducked seconds.
        )?.let { it >= VOCAL_ACTIVE_THRESHOLD } ?: false
        eqOverlapBonusBeats(duckAMids = duckA, delayBMids = delayB, type = type)
    } else {
        0
    }
    val transitionBeats = ((baseBeats * energyFactor).roundToInt() + bonusBeats)
        .coerceIn(if (mixset) 16 else 4, if (mixset) max(16, djBeatCeiling) else 32)
    // Finetune v1 §4.2: minimum up 1 s across the board.
    val minimumOverlap = if (currentBpm >= 140) 7.0 else 5.0

    return Overlap(
        overlap = clamp(
            transitionBeats * beatSeconds,
            minimumOverlap,
            if (mixset) djModeCeilingFor(type) else ceilingFor(type),
        ),
        transitionBeats = transitionBeats,
        incomingPlaybackRate = if (ratio in 0.9..1.1) {
            (clamp(1 / ratio, 0.9, 1.1) * 10000).roundToInt() / 10000.0
        } else {
            1.0
        },
    )
}

/** v2 §6 energy-direction factor. Pure — shared with tests via [overlapEnergyFactorFor]. */
private const val OVERLAP_SLOPE_EPSILON = 0.002

private fun windowSlope(curve: List<EnergySample>, from: Double, to: Double): Double {
    if (to <= from) return 0.0
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (point in curve) {
        if (!point.time.isFinite() || !point.energy.isFinite()) continue
        if (point.time < from || point.time > to) continue
        xs += point.time
        ys += point.energy
    }
    return StructureDetector.linearSlope(xs, ys)
}

private fun overlapEnergyFactor(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionPoint: Double,
    entryPoint: Double,
): Double {
    val intervalA = analysis.beatInterval.orZero()
        .takeIf { it > 0 } ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
    val intervalB = nextAnalysis.beatInterval.orZero()
        .takeIf { it > 0 } ?: if (nextAnalysis.bpm.orZero() > 0) 60 / nextAnalysis.bpm else 0.5
    val slopeA = windowSlope(analysis.energyCurve, transitionPoint - 64 * intervalA, transitionPoint)
    val slopeB = windowSlope(nextAnalysis.energyCurve, entryPoint, entryPoint + 64 * intervalB)
    return overlapEnergyFactorFor(slopeA, slopeB)
}

/** v2 §6 table: A↓B↑ stretches, both↑ tightens, everything else holds. */
fun overlapEnergyFactorFor(slopeA: Double, slopeB: Double): Double =
    when (energyTrajectoryFor(slopeA, slopeB)) {
        EnergyTrajectory.A_DOWN_B_UP -> OVERLAP_ENERGY_STRETCH_FACTOR
        EnergyTrajectory.A_UP_B_UP -> OVERLAP_ENERGY_TIGHTEN_FACTOR
        else -> 1.0
    }

/**
 * Phase B4: energy direction of the pair, classified from the same two
 * slopes and dead-band as the sizing factor — zero new measurement. The
 * selector consumes it so fighting risers wash out while comedown→buildup
 * arcs earn the long blend.
 */
enum class EnergyTrajectory {
    A_DOWN_B_UP, A_UP_B_UP, A_UP_B_DOWN, A_DOWN_B_DOWN,
    A_FLAT_B_UP, A_FLAT_B_DOWN, A_UP_B_FLAT, A_DOWN_B_FLAT, FLAT,
}

fun energyTrajectoryFor(slopeA: Double, slopeB: Double): EnergyTrajectory {
    val a = if (slopeA < -OVERLAP_SLOPE_EPSILON) -1 else if (slopeA > OVERLAP_SLOPE_EPSILON) 1 else 0
    val b = if (slopeB < -OVERLAP_SLOPE_EPSILON) -1 else if (slopeB > OVERLAP_SLOPE_EPSILON) 1 else 0
    return when {
        a < 0 && b > 0 -> EnergyTrajectory.A_DOWN_B_UP
        a > 0 && b > 0 -> EnergyTrajectory.A_UP_B_UP
        a > 0 && b < 0 -> EnergyTrajectory.A_UP_B_DOWN
        a < 0 && b < 0 -> EnergyTrajectory.A_DOWN_B_DOWN
        a == 0 && b > 0 -> EnergyTrajectory.A_FLAT_B_UP
        a == 0 && b < 0 -> EnergyTrajectory.A_FLAT_B_DOWN
        a > 0 && b == 0 -> EnergyTrajectory.A_UP_B_FLAT
        a < 0 && b == 0 -> EnergyTrajectory.A_DOWN_B_FLAT
        else -> EnergyTrajectory.FLAT
    }
}

private fun standardTransition(
    length: Double,
    playbackTime: Double,
    fadeSeconds: Double,
    minFadeSeconds: Double,
    reason: String = "standard",
): TransitionPlan {
    val fade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    val transitionStart = max(0.0, length - fade)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = length,
        fadeSeconds = fade,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        standardTransitionUsed = true,
        reason = if (started) reason else "before-$reason-window",
    )
}

/** A stale analysis paired with the wrong track is worse than no analysis at all. */
private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}

/**
 * Mixset fire floor, retired: DJ Mode is freeform, so no blend may-start
 * floor applies — the exit anchor already carries the sole duration rule
 * (entry + one phrase anti-flap). Kept as a no-op (not deleted) because
 * MixsetTest pins its arithmetic directly; every call site still routes
 * through it.
 */
internal fun applyMixsetFireFloor(plan: TransitionPlan, length: Double, mixset: Boolean): TransitionPlan {
    // Vocal-heavy blends ride LOGARITHMIC: on matched grids the S-curve's
    // slow middle stacks two voices at near-full level, while the log's fast
    // early drop of the outgoing track clears the band B is entering.
    // Central choke — every smart plan passes through here. Full-audit P1
    // M3: the flip is paired with the duck-voiced EQ key set (see
    // forceDuckKeys) and logged, so the curve and the EQ never disagree
    // about how vocal this blend is.
    if (plan.vocalOverlap > 0.5 && plan.volumeCurve == VolumeCurve.S_CURVE) {
        return plan.copy(
            volumeCurve = VolumeCurve.LOGARITHMIC,
            forceDuckKeys = true,
            policyReasons = plan.policyReasons + "vocal-choke-log",
        )
    }
    return plan
}

/**
 * Plans the transition out of [currentTrack] and into [nextTrack].
 *
 * Called on every playback tick; the returned plan describes the transition
 * whether or not it has started yet.
 *
 * @param albumSequential true only when this is an album genuinely being
 *   played through in order, which is the sole case that earns a gapless
 *   handoff instead of a mix.
 * @param currentTime the outgoing track's playhead, in seconds.
 * @param mixset Mixset Mode: the outgoing window becomes ~90 s past the
 *   track's best part and the 80%-play floor below does not apply.
 */
/**
 * Minimum audible blend every non-blocked plan must carry. Plans shorter
 * than this (HARD_CUT 0.1 s, misflagged GAPLESS, LOOP INSTANT-hold, span
 * collapses) are substituted with a silence-seeking dissolve so no track
 * ever hard-cuts like a manual Next press.
 */
const val MIN_GUARANTEED_BLEND_SECONDS = 4.0

fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    mixset: Boolean = false,
): TransitionPlan {
    val plan = planTransitionInner(
        analysis, nextAnalysis, currentTrack, nextTrack, currentTime,
        duration, fadeSeconds, minFadeSeconds, mode, albumSequential, mixset,
    )
    if (plan.blocked) return plan
    if (plan.fadeSeconds >= MIN_GUARANTEED_BLEND_SECONDS) return plan
    // A deliberate silence-seeking dissolve is already a mix, even at 2 s.
    if (plan.type == TransitionType.PLAIN_DISSOLVE) return plan
    // Full-audit P1 M5: the LOOP INSTANT-hold is deliberate tension, not a
    // glitch — substituting a 4 s dissolve for it contradicts the loop's own
    // author. The loop runs its window as planned.
    if (plan.type == TransitionType.LOOP_CUT_DROP) return plan
    // The queue literally repeats one file: dissolving a track into itself
    // is a glitch, not a mix.
    if (currentTrack != null && nextTrack != null && currentTrack.id == nextTrack.id) return plan
    // A genuine gapless album handoff keeps its 0.12 s cut.
    if (plan.transitionStyle == TransitionStyle.GAPLESS) return plan
    val len = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val nextLen = max(0.0, trackDurationSeconds(nextTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    val sub = plainDissolvePlan(
        analysis, nextAnalysis, len, nextLen, playbackTime, mixset,
        plan.policyReasons.ifEmpty { listOf("instant-to-dissolve-floor") },
    )
    if (sub.fadeSeconds >= MIN_GUARANTEED_BLEND_SECONDS) return sub
    // The dissolve itself sits on a 2 s gap: stretch the window back so the
    // substituted blend still honours the floor instead of reintroducing a
    // short cut through the back door.
    val extendedStart = max(0.0, sub.transitionEnd - MIN_GUARANTEED_BLEND_SECONDS)
    val extendedFade = sub.transitionEnd - extendedStart
    return sub.copy(
        shouldStart = playbackTime >= extendedStart,
        transitionStart = extendedStart,
        fadeSeconds = extendedFade,
        handoffStartSeconds = extendedStart,
        handoffDuration = extendedFade,
        overlapSeconds = extendedFade,
        reason = "instant-to-dissolve-floor",
    )
}

private fun planTransitionInner(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    mixset: Boolean = false,
): TransitionPlan {
    val length = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return blocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        return standardTransition(length, playbackTime, standardFade, minFadeSeconds)
    }

    if (length < MIN_SMART_DURATION_SECONDS) {
        return blocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    // Spec finetune §7.8: tracks under 90 s never carry a beat-blend — there
    // is no room for a phrase to develop, so dissolve at the cut point. The
    // policy reasons are not assessed yet; the plan carries its own reason.
    val nextLenShort = max(0.0, trackDurationSeconds(nextTrack))
    if (length < 90.0) {
        return applyMixsetFireFloor(
            plainDissolvePlan(analysis, nextAnalysis, length, nextLenShort, playbackTime, mixset, emptyList()),
            length, mixset,
        )
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    // The outgoing track plays at least 80% in normal mode: candidates live
    // in [0.8*length, length-15s] with a max(length-45s, 0.8*length)
    // fallback. Tracks too short to survive either keep the old behavior
    // exactly. Mixset Mode replaces the floor with its own early anchor, so
    // the floor below is zero when it is on.
    // v2 §2c/§3: the detector's OUTRO can pull the floor earlier (see
    // effectivePlayFloor) — the comedown has begun, don't burn low tail.
    val playFloorSeconds = if (mixset) 0.0 else effectivePlayFloor(analysis, length)
    val candidateWindow = if (mixset) {
        null
    } else {
        (max(0.0, playFloorSeconds)..(length - 15.0))
            .takeIf { it.start < it.endInclusive }
    }
    val mixOutAnchor = if (mixset) {
        mixsetMixOutAnchor(analysis, length, playbackTime)
    } else {
        resolveMixOutAnchor(
            analysis,
            contentEnd = finalMixAnchor,
            duration = length,
            allowedWindow = candidateWindow,
            fallbackTime = max(length - 45.0, playFloorSeconds).takeIf { length >= 60.0 },
        )
    }
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (BLOCKED_TEXT.containsMatchIn("${itemText(currentTrack)} ${itemText(nextTrack)}")) {
        return blocked("blocked-speech-or-live")
    }

    if (!analysisReadyForTrack(analysis, currentTrack) ||
        !analysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        return standardTransition(
            length,
            playbackTime,
            standardFade,
            minFadeSeconds,
            "smart-analysis-fallback",
        )
    }

    // Full-audit P0.1: both-sides-analyzed hard gate. A smart/DJ blend without
    // vocal masks on BOTH sides is blind — every vocal mitigation defaults to
    // off (duck/delay false, separation open, proactive open) while the scalar
    // path can still grant a long overlap. No evidence, no long blend: route
    // to a short silence-seeking dissolve until the masks land. The two sides
    // need different evidence: the outgoing side contributes its tail (mix-out,
    // clash windows), so only a whole-track mask satisfies it; the incoming
    // side only ever reads its entry window, so a provisional head mask
    // (P0.2) is exactly the evidence it needs. Same-file repeats are exempt
    // (no second voice enters, nothing to clash).
    val sameFileRepeat = currentTrack != null && nextTrack != null &&
        currentTrack.id.isNotBlank() && currentTrack.id == nextTrack.id
    val outgoingMasked = analysis.vocalActivityMask.isNotEmpty() && !analysis.provisionalHead
    val incomingMasked = nextAnalysis.vocalActivityMask.isNotEmpty()
    if (!sameFileRepeat && (!outgoingMasked || !incomingMasked)) {
        return applyMixsetFireFloor(
            plainDissolvePlan(
                analysis, nextAnalysis, length,
                max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack)),
                playbackTime, mixset,
                listOf("vocal-mask-gate"),
            ),
            length, mixset,
        )
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    // A mixset anchor is interior by design — the playhead reaching it is the
    // transition arriving, not a missed window to abandon for the track end.
    val rawMixAnchor =
        if (!mixset && playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }
    // Spec: the incoming drop lands after the outgoing track is gone.
    val mixAnchor = alignMixsetExitToIncomingDrop(analysis, nextAnalysis, rawMixAnchor, mixset)

    val nextLength = max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack))

    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        // v2 §9a: unsyncable pairs dissolve at silence/a break point instead
        // of fading blindly over whatever happens to sit at the tail. The
        // dissolve finds its own cut, so the tail anchor is irrelevant — and
        // the score is the neutral default: nothing here was synchronised.
        return applyMixsetFireFloor(
            plainDissolvePlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixset, policy.reasons,
                candidateShift = policy.candidateShiftSemitones,
            ),
            length, mixset,
        )
    }

    // Blueprint §5.6: score the pair once on proxy points, then route to the
    // archetype's implementation. phraseSwitch below is the HARMONIC_BLEND
    // engine and the adaptive tail is SMOOTH_CROSSFADE/FILTER_SWEEP; the other
    // three archetypes branch to their own planners here and never reach them.
    val proxyEntry = capIncomingEntry(incomingCuePoint(nextAnalysis), nextAnalysis, nextLength, mixset)
    val proxyScore = scoreCompatibility(analysis, nextAnalysis, mixAnchor, proxyEntry)
    if (proxyScore.overall < SCORE_ACCEPTABLE) {
        // v2 §9: a weak pair never blends — the heavy clash gets a forced
        // echo-out, a weak HALF_TIME lock a short 8-bar blend (§9 over §5c:
        // keep beat-sync, only shorten the overlap).
        TrackLog.d(
            PLANNER_TAG,
            "Low score ${"%.2f".format(proxyScore.overall)} " +
                "genres=${genreClass(analysis)}/${genreClass(nextAnalysis)} " +
                "tier=${policy.tier} ratio=${policy.matchedRatio}",
        )
        if (policy.tier == TransitionTier.HALF_TIME) {
            return applyMixsetFireFloor(
                halfTimeBlendPlan(
                    analysis, nextAnalysis, length, nextLength,
                    playbackTime, mixAnchor, proxyEntry, proxyScore, policy, short = true, mixset = mixset,
                ),
                length, mixset,
            )
        }
        return applyMixsetFireFloor(
            heavyClashPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyEntry, proxyScore, policy.reasons, mixset,
            ),
            length, mixset,
        )
    }
    val dropInB = firstDropSec(nextAnalysis)
    val highEnergyA = isHighEnergyAt(analysis, mixAnchor)
    // v2 §5c: B's energy is read at its buildup start — a drop-bound track
    // entering on a quiet foot is not "high energy" even if its proxy cue is.
    val buildupB = buildupStart(nextAnalysis, dropInB ?: proxyEntry) ?: proxyEntry
    val highEnergyB = isHighEnergyAt(nextAnalysis, buildupB)
    val beatOrHalf = policy.tier == TransitionTier.BEATMATCHED || policy.tier == TransitionTier.HALF_TIME
    // LOOP needs a real drop, not a lone spike: firstDropSec fires on any
    // 1.5×-mean peak past the intro (a loud fill, a mastered chorus entry),
    // and an 8 s B-gated hole on a phantom drop is the "nothing, then the
    // next track at full volume" complaint. A drop with a findable buildup
    // foot is a structure; without one the pair stays a blend-matrix
    // candidate, never a cut.
    // Full-audit P0.3: a drop with a findable buildup foot is a structure —
    // without one the pair stays a blend-matrix candidate, never a cut. The
    // old test (any buildupStart, including fabricated drop−phrase16/drop−8s
    // arithmetic) read phantom drops as real and unlocked LOOP_CUT_DROP /
    // phrase-switch routing that assumes a genuine arrival.
    val realDropInB = dropInB != null && dropInB.isFinite() && isDropTrusted(nextAnalysis)
    // Phase B3: best ranked intro/outro evidence for the selector. NEG_INF =
    // a fallback cue with no ranked evidence (caution, not daring). The outro
    // reuses the same end/window as the chosen anchor so the scalar describes
    // the exit actually taken. Both rank fns are pure sorts of small analyzer
    // lists — one extra pass each, no struct changes.
    val bestIntroRank = rankMixInCandidates(nextAnalysis).firstOrNull()?.rankScore
        ?: Double.NEGATIVE_INFINITY
    val bestOutroRank = rankMixOutCandidates(analysis, finalMixAnchor, length, candidateWindow)
        .firstOrNull()?.rankScore ?: Double.NEGATIVE_INFINITY
    // Phase B4: trajectory from the same 64-beat slopes the overlap factor
    // measures (see overlapEnergyFactor) — zero new measurement.
    val intervalA = analysis.beatInterval.orZero()
        .takeIf { it > 0 } ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
    val intervalB = nextAnalysis.beatInterval.orZero()
        .takeIf { it > 0 } ?: if (nextAnalysis.bpm.orZero() > 0) 60 / nextAnalysis.bpm else 0.5
    val trajectory = energyTrajectoryFor(
        windowSlope(analysis.energyCurve, mixAnchor - 64 * intervalA, mixAnchor),
        windowSlope(nextAnalysis.energyCurve, proxyEntry, proxyEntry + 64 * intervalB),
    )
    val selectedType = if (beatOrHalf || policy.tier == TransitionTier.DJ_ASSISTED) {
        selectTransitionType(
            proxyScore, policy.tier, highEnergyA, highEnergyB, realDropInB,
            introQuality = bestIntroRank, outroQuality = bestOutroRank, trajectory = trajectory,
        )
    } else {
        // Unreachable today (PLAIN returns upstream), kept as the closed
        // default so a future tier degrades to a blend, never to a crash.
        TransitionType.SMOOTH_CROSSFADE
    }
    // Spec finetune §7.3 anti-monotony: only a literally identical file earns
    // the hard cut (a skip proxy — the planner cannot advance the queue, so
    // the 0.1 s cut is the closest it gets). Merely similar-sounding tracks
    // keep their matrix result: a key-matched smooth blend is a mashup.
    if (currentTrack != null && nextTrack != null && currentTrack.id == nextTrack.id) {
        return applyMixsetFireFloor(
            hardCutPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyScore, policy.reasons, mixset,
            ),
            length, mixset,
        )
    }
    if (selectedType == TransitionType.ECHO_REVERB_OUT) {
        return applyMixsetFireFloor(
            echoOutPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyScore, policy.reasons, mixset,
            ),
            length, mixset,
        )
    }
    if (selectedType == TransitionType.LOOP_CUT_DROP && dropInB != null) {
        // The 4-bar vamp plus 2-bar freeze needs room: at least 8 bars of
        // tail below the anchor. Without it the loop is a fiction, and an
        // honest cut beats a muddy short blend on a double-high pair.
        val beatOutA = analysis.beatInterval.orZero().takeIf { it > 0 }
            ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
        if (mixAnchor >= 32 * beatOutA) {
            return applyMixsetFireFloor(
                loopCutPlan(
                    analysis, nextAnalysis, length, nextLength,
                    playbackTime, mixAnchor, dropInB, proxyScore, policy.reasons, mixset,
                ),
                length, mixset,
            )
        }
        return applyMixsetFireFloor(
            hardCutPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyScore, policy.reasons, mixset,
            ),
            length, mixset,
        )
    }
    if (selectedType == TransitionType.HARD_CUT) {
        return applyMixsetFireFloor(
            hardCutPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyScore, policy.reasons, mixset,
            ),
            length, mixset,
        )
    }
    if (selectedType == TransitionType.HALF_TIME_BLEND) {
        return applyMixsetFireFloor(
            halfTimeBlendPlan(
                analysis, nextAnalysis, length, nextLength,
                playbackTime, mixAnchor, proxyEntry, proxyScore, policy, short = false, mixset = mixset,
            ),
            length, mixset,
        )
    }

    phraseSwitch(analysis, nextAnalysis, length, nextLength, mixset, mixAnchor)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return applyMixsetFireFloor(
                plan.copy(
                    shouldStart = started,
                    type = TransitionType.HARMONIC_BLEND,
                    score = scoreCompatibility(analysis, nextAnalysis, plan.transitionStart, plan.incomingCueTime),
                    eqCurve = EQCurve.BASS_SWAP,
                    policyReasons = policy.reasons,
                    reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
                ),
                length, mixset,
            )
        }

    val (overlap, transitionBeats, adaptiveRate) =
        adaptiveOverlap(analysis, nextAnalysis, mixAnchor, proxyEntry, selectedType, mixset)
    // Spec finetune §7.1: DJ_ASSISTED never stretches — the filter masks the
    // drift instead. The adaptive tail still sizes the overlap, but the deck
    // rate stays unity.
    val incomingPlaybackRate =
        if (policy.tier == TransitionTier.DJ_ASSISTED) 1.0 else adaptiveRate
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    val sameBeatBlend = currentBpm > 0 && nextBpm > 0 &&
        abs(1 - normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
        (analysis.beatConfidence.orZero() >= 0.2 || nextAnalysis.beatConfidence.orZero() >= 0.2)
    val outgoingArrangementOverlap =
        if (sameBeatBlend && mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * 60 / currentBpm, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val mixEnd = max(0.0, mixAnchor - outgoingArrangementOverlap)
    // The track plays its floor: in normal mode the overlap may not reach
    // back past 80% of the track. DJ Mode cuts between peaks with long beds,
    // so its ceiling is the per-type table below on top of the usual rails.
    val typeBeats = min(maxBeatsFor(selectedType), if (mixset) djModeMaxBeats(selectedType) else Double.POSITIVE_INFINITY)
    val floorRail = mixEnd - playFloorSeconds
    // Review v2.1 B6: the overlap must also leave the incoming track room
    // for its own entry — gate on its clearance (length minus entry), not
    // just its length. incomingStartPoint is overlap-independent, so it can
    // be read before the rails that consume it.
    val earlyIncomingCue = incomingStartPoint(nextAnalysis).coerceAtLeast(0.0)
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (typeBeats * 60) / handoffBpm else djRailCeiling(selectedType, mixset),
        ABSOLUTE_MAX_TRANSITION_SECONDS,
        mixEnd * 0.6,
        if (nextLength > 0) max(0.0, nextLength - earlyIncomingCue) * 0.60 else djRailCeiling(selectedType, mixset),
        if (!mixset && floorRail >= MIN_TRANSITION_OVERLAP_SECONDS) floorRail else Double.POSITIVE_INFINITY,
    )
    val handoffBeats = if (sameBeatBlend) 8 else 4
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    // In Mixset Mode the handoff aims at the buildup foot, not the peak or
    // the intro arrangement — the 50% ceiling still applies.
    val incomingDropTime = if (mixset) {
        capIncomingEntry(
            mixsetEntryPoint(nextAnalysis) ?: incomingCuePoint(nextAnalysis),
            nextAnalysis, nextLength, mixsetActive = true,
        )
    } else {
        capIncomingEntry(incomingCuePoint(nextAnalysis), nextAnalysis, nextLength, mixsetActive = false)
    }
    val alignedIncomingBpm = alignTempoOctave(currentBpm, nextBpm)
    val requestedIncomingHandoff =
        if (sameBeatBlend && alignedIncomingBpm > 0) {
            incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * 60 / alignedIncomingBpm
        } else {
            incomingDropTime
        }
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = incomingStartPoint(nextAnalysis)
    val analyzedIncomingHandoff = nextAnalysis.mixInTime
    val hasIncomingPreroll = analyzedIncomingHandoff.isFinite() &&
        analyzedIncomingHandoff > rawIncomingCueTime + 0.5
    val incomingCueTime = if (hasIncomingPreroll) rawIncomingCueTime else incomingHandoffTime
    val introPreroll = max(
        0.0,
        (if (hasIncomingPreroll) incomingHandoffTime - incomingCueTime else 0.0) /
            max(0.8, incomingPlaybackRate),
    )

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val totalOverlap = clamp(introDropTime, min(12.0, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val desiredOverlap = max(overlap, introPreroll + handoffSeconds * 0.42)
        val actualOverlap = clamp(desiredOverlap, min(handoffSeconds, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = desiredOverlap > overlap + 0.5,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime = if (hasIncomingPreroll) {
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
        } else {
            incomingCueTime
        }
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    val finalScore = scoreCompatibility(analysis, nextAnalysis, transitionStart, finalIncomingCueTime)
    // Blueprint §5.7 FILTER_SWEEP DSP rule: shift the incoming track toward
    // the outgoing key when a small shift suffices, mask with the sweep when
    // it does not (semitonesToShift answers 0 in both the done and the
    // hopeless cases, which is exactly when no shift applies).
    val keyShift = if (!sameBeatBlend &&
        analysis.key.isNotBlank() && nextAnalysis.key.isNotBlank() &&
        keyScore(analysis.key, nextAnalysis.key) in 0.45..0.75 &&
        // A trusted F0 that contradicts the incoming key cancels the shift:
        // retuning toward a misdetected key lands in a worse one.
        !(nextAnalysis.pitchConfidence >= TRUSTED_PITCH_CONFIDENCE &&
            pitchVetoesShift(nextAnalysis.vocalPitchMedianHz, nextAnalysis.key))
    ) {
        policy.candidateShiftSemitones
    } else {
        0
    }
    val started = playbackTime >= transitionStart
    return applyMixsetFireFloor(
        TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = incomingPlaybackRate,
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = sameBeatBlend || hasBassContent,
        transitionStyle = if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,
        // Full-audit P1 M2: the type follows the matrix decision, not the
        // grid accident. Re-deriving SMOOTH-vs-FILTER from sameBeatBlend here
        // rendered HARMONIC-matrix pairs under the FILTER EQ schedule (and
        // vice versa) — the "EQ loses to the filter" feeling. The style above
        // still reflects grid reality (matched grid blends open, drift hides
        // behind the sweep); the type now selects the EQ voicing the matrix
        // actually chose. Only SMOOTH/HARMONIC/FILTER reach here (ECHO/LOOP/
        // HARD/HALF returned upstream), so selectedType is always one of them.
        type = selectedType,
        score = finalScore,
        keyShiftSemitones = keyShift,
        volumeCurve = VolumeCurve.S_CURVE,
        eqCurve = if (sameBeatBlend) EQCurve.BASS_SWAP else EQCurve.EQ_SWAP,
        // The two styles are alternatives, not a scale: a matched pair hands the
        // low end over on a beat and otherwise stays open, while an unmatched
        // pair has no shared grid to hand anything over on and instead pulls the
        // outgoing track behind a closing low-pass. Left at zero on the blend
        // branch so the renderer doesn't do both at once.
        filterSweep = if (sameBeatBlend) 0.0 else FILTER_SWEEP,
        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            transitionEnd = mixEnd,
            incomingCueTime = finalIncomingCueTime,
            incomingPlaybackRate = incomingPlaybackRate,
        ),
        policyReasons = policy.reasons,
        reason = if (started) "smart-duration" else "before-smart-duration",
        ),
        length, mixset,
    )
}
