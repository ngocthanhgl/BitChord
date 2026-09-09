package com.music.bitchord.playback.smart

import kotlin.math.max

/**
 * The per-transition-type 3-band EQ schedules from the DJ-EQ spec, as pure
 * functions of blend progress. No audio, no threads, no ExoPlayer — the
 * numbers below are the spec's schedule tables with linear interpolation
 * between rows, so a unit test can pin every row without rendering a sample.
 *
 * Conventions (spec §EQ schedules):
 * - progress 0.0 = start of overlap, 1.0 = end. Gain 1.0 = unity, 0.0 = kill.
 * - Each deck's gains are keyframes; [at] interpolates linearly between them.
 * - LOW on a swap type (SMOOTH, HARMONIC, FILTER_SWEEP, HALF_TIME_BLEND) is a
 *   placeholder: the controller's bass-swap state machine owns the LOW band
 *   there ([BASS_SWAP_PROGRESS], fired on a downbeat), because only it knows
 *   when the swap actually fired. Table-driven types (ECHO, LOOP, DISSOLVE)
 *   carry their real LOW keyframes below.
 * - `duckAMids` / `delayBMids` are the spec's ARM-time vocal flags. When false
 *   the `V?` / `V?B` rows are skipped and mids stay at unity until the close.
 */
object EqSchedule {

    data class EqGains(val low: Float, val mid: Float, val high: Float) {
        companion object {
            val UNITY = EqGains(1f, 1f, 1f)
            val SILENT = EqGains(0f, 0f, 0f)
        }
    }

    private data class Key(val progress: Float, val gains: EqGains)

    /**
     * Bass-swap arm progress per transition type (spec §Bass swap timing).
     * Null = no downbeat swap; the LOW band comes from the tables instead.
     */
    val BASS_SWAP_PROGRESS: Map<TransitionType, Float> = mapOf(
        TransitionType.SMOOTH_CROSSFADE to 0.35f,
        TransitionType.HARMONIC_BLEND to 0.30f,
        TransitionType.FILTER_SWEEP to 0.30f,
        TransitionType.HALF_TIME_BLEND to 0.20f,
    )

    /**
     * Swap duration in bars. The spec voices 2 bars for every downbeat swap;
     * the table-driven types don't swap at all.
     */
    const val SWAP_BARS = 2.0

    fun outgoingGains(type: TransitionType, progress: Float, duckAMids: Boolean): EqGains =
        at(outgoingKeys(type, duckAMids), progress)

    fun incomingGains(type: TransitionType, progress: Float, delayBMids: Boolean): EqGains =
        at(incomingKeys(type, delayBMids), progress)

    private fun at(keys: List<Key>, progress: Float): EqGains {
        val p = progress.coerceIn(0f, 1f)
        if (p <= keys.first().progress) return keys.first().gains
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (p <= b.progress) {
                val span = b.progress - a.progress
                val t = if (span <= 0f) 1f else ((p - a.progress) / span).coerceIn(0f, 1f)
                return EqGains(
                    low = lerp(a.gains.low, b.gains.low, t),
                    mid = lerp(a.gains.mid, b.gains.mid, t),
                    high = lerp(a.gains.high, b.gains.high, t),
                )
            }
        }
        return keys.last().gains
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ---- Outgoing (Track A) -------------------------------------------------

    private fun outgoingKeys(type: TransitionType, duck: Boolean): List<Key> = when (type) {
        TransitionType.SMOOTH_CROSSFADE -> {
            // Vocal duck reaches 0.30 by 0.70 (Rule 2); presence cuts from 0.80.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.55f, EqGains.UNITY),
                    Key(0.70f, EqGains(1f, 0.30f, 1f)),
                    Key(0.80f, EqGains(1f, 0.30f, 1f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.80f, EqGains.UNITY),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.HARMONIC_BLEND -> {
            // Gentler duck than SMOOTH: mids coexist briefly, keys match.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.60f, EqGains.UNITY),
                    Key(0.75f, EqGains(1f, 0.50f, 1f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.85f, EqGains.UNITY),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.FILTER_SWEEP -> listOf(
            // A holds full mix while B opens top-down; mid-kill masks the clash.
            Key(0f, EqGains.UNITY),
            Key(0.45f, EqGains(1f, 1f, 1f)),
            Key(0.55f, EqGains(1f, 1f, 0.60f)),
            Key(0.70f, EqGains(1f, 0.40f, 0.20f)),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.ECHO_REVERB_OUT -> listOf(
            // Bass removed early (reverb bass is muddy), then mids duck out.
            Key(0f, EqGains.UNITY),
            Key(0.25f, EqGains(1f, 1f, 1f)),
            Key(0.35f, EqGains(0f, 1f, 1f)),
            Key(0.40f, EqGains(0f, 1f, 1f)),
            Key(0.55f, EqGains(0f, 0.60f, 1f)),
            Key(0.70f, EqGains(0f, 0.25f, 0.70f)),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.LOOP_CUT_DROP -> listOf(
            // Full energy to hold tension; all bands cut together before the drop.
            Key(0f, EqGains.UNITY),
            Key(0.70f, EqGains(1f, 1f, 1f)),
            Key(0.85f, EqGains(1f, 0.70f, 1f)),
            Key(0.90f, EqGains.SILENT),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.HALF_TIME_BLEND -> {
            // Rhythmic anchor hands off early (swap at 0.20); presence follows.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.40f, EqGains.UNITY),
                    Key(0.60f, EqGains(1f, 0.50f, 1f)),
                    Key(0.80f, EqGains(1f, 0.20f, 0.70f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.60f, EqGains.UNITY),
                    Key(0.80f, EqGains(1f, 1f, 0.70f)),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.PLAIN_DISSOLVE -> listOf(
            // No grid to rely on: bass separated immediately, mids fade late.
            Key(0f, EqGains.UNITY),
            Key(0.10f, EqGains(1f, 1f, 1f)),
            Key(0.20f, EqGains(0f, 1f, 1f)),
            Key(0.50f, EqGains(0f, 1f, 1f)),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.HARD_CUT -> listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
    }

    // ---- Incoming (Track B) -------------------------------------------------

    private fun incomingKeys(type: TransitionType, delay: Boolean): List<Key> = when (type) {
        TransitionType.SMOOTH_CROSSFADE -> {
            // Bass killed on entry; mids delayed only when B enters singing.
            val mid = if (delay) {
                listOf(Key(0f, EqGains(1f, 0f, 1f)), Key(0.15f, EqGains(1f, 0f, 1f)), Key(0.40f, EqGains.UNITY))
            } else {
                listOf(Key(0f, EqGains.UNITY))
            }
            mid + Key(1f, EqGains.UNITY)
        }
        TransitionType.HARMONIC_BLEND ->
            // Keys match: mids present from entry, only bass segregated.
            listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
        TransitionType.FILTER_SWEEP -> listOf(
            // Top-down entry: high air first, mids only after A's highs cut.
            Key(0f, EqGains(1f, 0f, 0.60f)),
            Key(0.10f, EqGains(1f, 0f, 1f)),
            Key(0.20f, EqGains(1f, 0f, 1f)),
            Key(0.30f, EqGains(1f, 0.40f, 1f)),
            Key(0.40f, EqGains(1f, 0.80f, 1f)),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.ECHO_REVERB_OUT -> listOf(
            // B enters in highs under the wash, opens fully as the wash decays.
            Key(0f, EqGains(1f, 0f, 0.80f)),
            Key(0.35f, EqGains(0f, 0f, 0.80f)),
            Key(0.45f, EqGains(0f, 0.50f, 1f)),
            Key(0.55f, EqGains(0.80f, 1f, 1f)),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.LOOP_CUT_DROP ->
            // B silent until the drop, then full mix — the payoff.
            listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
        TransitionType.HALF_TIME_BLEND -> listOf(
            // Bass killed; mids slightly soft for the approach, open by 0.10.
            Key(0f, EqGains(1f, 0.90f, 1f)),
            Key(0.10f, EqGains.UNITY),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.PLAIN_DISSOLVE -> listOf(
            // No bass until A's is gone, then in after the midpoint.
            Key(0f, EqGains(1f, 1f, 1f)),
            Key(0.50f, EqGains(0f, 1f, 1f)),
            Key(0.60f, EqGains.UNITY),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.HARD_CUT -> listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
    }

    /** Largest single-tick target step this schedule can produce, for the Rule 3 check. */
    fun maxTickStep(type: TransitionType, spanSec: Float, tickSec: Float = 0.030f): Float {
        var worst = 0f
        val steps = max(1, (spanSec / tickSec).toInt())
        var prevOut = outgoingGains(type, 0f, true)
        var prevIn = incomingGains(type, 0f, true)
        for (i in 1..steps) {
            val p = i.toFloat() / steps
            val o = outgoingGains(type, p, true)
            val n = incomingGains(type, p, true)
            worst = max(worst, stepOf(prevOut, o))
            worst = max(worst, stepOf(prevIn, n))
            prevOut = o
            prevIn = n
        }
        return worst
    }

    private fun stepOf(a: EqGains, b: EqGains): Float =
        max(max(kotlin.math.abs(a.low - b.low), kotlin.math.abs(a.mid - b.mid)), kotlin.math.abs(a.high - b.high))
}
