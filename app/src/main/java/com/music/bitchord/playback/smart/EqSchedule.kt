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
    /** DJ hard swap: the LOW handover completes in 2 beats on the fired downbeat. */
    const val SWAP_BARS = 0.5

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
            // Finetune-overlap: mids start receding at 0.15 (~3.4 s into a
            // 22.5 s bed), highs follow at 0.75 — the listener tracks each
            // band separately instead of hearing one fade.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.15f, EqGains(1f, 0.82f, 1f)),
                    Key(0.30f, EqGains(1f, 0.38f, 1f)),
                    Key(0.45f, EqGains(1f, 0.28f, 1f)),
                    Key(0.60f, EqGains(1f, 0.20f, 1f)),
                    Key(0.75f, EqGains(1f, 0.12f, 0.82f)),
                    Key(0.90f, EqGains(1f, 0.10f, 0.55f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.45f, EqGains.UNITY),
                    Key(0.62f, EqGains(1f, 0.78f, 1f)),
                    Key(0.78f, EqGains(1f, 0.50f, 0.85f)),
                    Key(0.92f, EqGains(1f, 0.25f, 0.60f)),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.HARMONIC_BLEND -> {
            // Finetune-overlap: perfect key match coexists long (unity to
            // 0.52 voiceless); ducked mids taper from 0.18 over a ~26 s bed.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.18f, EqGains(1f, 0.80f, 1f)),
                    Key(0.32f, EqGains(1f, 0.55f, 1f)),
                    Key(0.46f, EqGains(1f, 0.38f, 1f)),
                    Key(0.60f, EqGains(1f, 0.25f, 1f)),
                    Key(0.74f, EqGains(1f, 0.15f, 0.88f)),
                    Key(0.88f, EqGains(1f, 0.10f, 0.65f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.52f, EqGains.UNITY),
                    Key(0.68f, EqGains(1f, 0.80f, 1f)),
                    Key(0.82f, EqGains(1f, 0.52f, 0.88f)),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.FILTER_SWEEP -> listOf(
            // Finetune-overlap: A exits highs-first (0.18) then mids, so the
            // clash is masked before B's mids emerge — never full spectrum
            // on both decks at once.
            Key(0f, EqGains.UNITY),
            Key(0.18f, EqGains(1f, 1f, 0.82f)),
            Key(0.30f, EqGains(1f, 1f, 0.58f)),
            Key(0.42f, EqGains(1f, 0.72f, 0.32f)),
            Key(0.56f, EqGains(1f, 0.42f, 0.14f)),
            Key(0.70f, EqGains(1f, 0.18f, 0.04f)),
            Key(0.84f, EqGains(1f, 0.05f, 0f)),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.ECHO_REVERB_OUT -> listOf(
            // Finetune-overlap: bass out at 0.30 (echo bass is muddy), then a
            // long mid taper so A dissolves into pure reverb tail by 0.88.
            Key(0f, EqGains.UNITY),
            Key(0.22f, EqGains(1f, 1f, 1f)),
            Key(0.30f, EqGains(0f, 1f, 1f)),
            Key(0.46f, EqGains(0f, 0.72f, 1f)),
            Key(0.60f, EqGains(0f, 0.42f, 0.85f)),
            Key(0.74f, EqGains(0f, 0.18f, 0.62f)),
            Key(0.88f, EqGains(0f, 0.06f, 0.35f)),
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
            // Finetune-overlap: mids touch at 0.14 (swap lands at 0.20) and
            // taper across the bed; voiceless pairs coexist to 0.42.
            if (duck) {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.14f, EqGains(1f, 0.75f, 1f)),
                    Key(0.26f, EqGains(1f, 0.50f, 1f)),
                    Key(0.40f, EqGains(1f, 0.35f, 1f)),
                    Key(0.55f, EqGains(1f, 0.22f, 1f)),
                    Key(0.68f, EqGains(1f, 0.15f, 0.85f)),
                    Key(0.82f, EqGains(1f, 0.10f, 0.58f)),
                    Key(1f, EqGains.SILENT),
                )
            } else {
                listOf(
                    Key(0f, EqGains.UNITY),
                    Key(0.42f, EqGains.UNITY),
                    Key(0.60f, EqGains(1f, 0.86f, 0.80f)),
                    Key(0.78f, EqGains(1f, 0.58f, 0.55f)),
                    Key(1f, EqGains.SILENT),
                )
            }
        }
        TransitionType.PLAIN_DISSOLVE -> listOf(
            // Finetune-overlap: bass out early as before, plus a new mid cut
            // at 0.72 so the dissolve keeps receding instead of stalling.
            Key(0f, EqGains.UNITY),
            Key(0.14f, EqGains(1f, 1f, 1f)),
            Key(0.24f, EqGains(0f, 1f, 1f)),
            Key(0.50f, EqGains(0f, 1f, 1f)),
            Key(0.72f, EqGains(0f, 0.60f, 0.90f)),
            Key(1f, EqGains.SILENT),
        )
        TransitionType.HARD_CUT -> listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
    }

    // ---- Incoming (Track B) -------------------------------------------------

    private fun incomingKeys(type: TransitionType, delay: Boolean): List<Key> = when (type) {
        TransitionType.SMOOTH_CROSSFADE -> {
            // Finetune-overlap: B's mids emerge gradually (0.28/0.40) and
            // reach full at 0.52 — after the bass swap at 0.30/0.35. The
            // voiceless entry keeps a tiny soft start under the swap.
            val mid = if (delay) {
                listOf(
                    Key(0f, EqGains(1f, 0f, 1f)),
                    Key(0.18f, EqGains(1f, 0f, 1f)),
                    Key(0.28f, EqGains(1f, 0.28f, 1f)),
                    Key(0.40f, EqGains(1f, 0.62f, 1f)),
                    Key(0.52f, EqGains.UNITY),
                )
            } else {
                listOf(Key(0f, EqGains(1f, 0.86f, 1f)), Key(0.12f, EqGains.UNITY))
            }
            mid + Key(1f, EqGains.UNITY)
        }
        TransitionType.HARMONIC_BLEND ->
            // Keys match: mids present from entry, only bass segregated.
            listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
        TransitionType.FILTER_SWEEP -> listOf(
            // Finetune-overlap: B rises out of thin air — highs first, mids
            // filling in only after the bass swap at 0.30, full by 0.70.
            Key(0f, EqGains(1f, 0f, 0.40f)),
            Key(0.12f, EqGains(1f, 0f, 0.80f)),
            Key(0.22f, EqGains(1f, 0f, 1f)),
            Key(0.32f, EqGains(1f, 0.20f, 1f)),
            Key(0.46f, EqGains(1f, 0.52f, 1f)),
            Key(0.60f, EqGains(1f, 0.78f, 1f)),
            Key(0.70f, EqGains.UNITY),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.ECHO_REVERB_OUT -> listOf(
            // Finetune-overlap: B enters under the reverb tail at 0.38 and
            // opens smoother (0.48/0.58), full by 0.68.
            Key(0f, EqGains(1f, 0f, 0.80f)),
            Key(0.38f, EqGains(0f, 0f, 0.82f)),
            Key(0.48f, EqGains(0f, 0.42f, 1f)),
            Key(0.58f, EqGains(0.65f, 0.85f, 1f)),
            Key(0.68f, EqGains.UNITY),
            Key(1f, EqGains.UNITY),
        )
        TransitionType.LOOP_CUT_DROP ->
            // B silent until the drop, then full mix — the payoff.
            listOf(Key(0f, EqGains.UNITY), Key(1f, EqGains.UNITY))
        TransitionType.HALF_TIME_BLEND -> listOf(
            // Bass killed; mids a touch softer on the approach, open by 0.10.
            Key(0f, EqGains(1f, 0.88f, 1f)),
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
