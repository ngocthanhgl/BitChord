package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.maxOf

/**
 * v2 §2b structural section detector. Pure Kotlin, no PCM, no Android — every
 * function here is unit-testable on synthetic curves.
 *
 * The native side supplies three transient inputs (see TrackFeatures):
 * per-onset event times, per-frame spectral centroid in Hz, and a
 * full-resolution (250 ms) normalized energy curve. This object folds them
 * into section labels plus the scalars the planner reads; only those outputs
 * are persisted (see AnalysisStore schema 3). The fine curves themselves are
 * never stored — kilobytes per entry for data the planner never re-reads.
 *
 * Rules below follow the spec verbatim; deviations forced by real data carry
 * a DEVIATION note explaining why.
 */
object StructureDetector {

    /** Least-squares slope of ys over xs. 0 when degenerate. Shared with §6/§8b. */
    fun linearSlope(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size != ys.size || xs.size < 2) return 0.0
        val n = xs.size
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        return if (den > 1e-12) num / den else 0.0
    }

    private data class WindowStats(
        val start: Double,
        val end: Double,
        val rmsMean: Double,
        val onsetPerSec: Double,
        val centroidMean: Double,
    )

    /**
     * Classify 4-bar windows from downbeat stride 4. Windows with no fine
     * samples are skipped; an empty map means "no evidence" and callers keep
     * the v1 energy heuristics (spec §2b fallback).
     *
     * First match wins per window, in spec order:
     * DROP, BUILD, BREAK, OUTRO, INTRO, CHORUS/VERSE.
     *
     * @param meanRms track mean over the fine curve (caller computes once).
     * @param meanOnset track mean onset rate per second.
     */
    fun detect(
        fine: List<EnergySample>,
        centroid: List<EnergySample>,
        onsets: List<Double>,
        downbeats: List<Double>,
        duration: Double,
        meanRms: Double,
        meanOnset: Double,
        beatInterval: Double,
    ): List<StructureLabel> {
        if (fine.size < 8 || meanRms <= 0 || duration <= 0) return emptyList()
        val barSeconds = if (beatInterval.isFinite() && beatInterval > 0) beatInterval * 4 else 2.0
        // Spec slopes are per bar; measured slopes are per second over window
        // starts, so divide by the bar length. Exact — no approximation.
        val buildRmsSlope = 0.008 / barSeconds
        val buildCentroidSlope = 80.0 / barSeconds
        val bars = downbeats.filter { it.isFinite() }.sorted()
        if (bars.size < 8) return emptyList()
        // 4-bar windows, stepping one bar for boundary resolution.
        val windows = mutableListOf<WindowStats>()
        var bar = 0
        while (bar + 4 <= bars.size) {
            val start = bars[bar]
            val end = bars[bar + 4]
            if (end <= start || start < 0) {
                bar++
                continue
            }
            val energies = fine.filter { it.time.isFinite() && it.energy.isFinite() && it.time in start..end }
                .map { it.energy }
            if (energies.isEmpty()) {
                bar++
                continue
            }
            val cents = centroid.filter { it.time.isFinite() && it.energy.isFinite() && it.time in start..end }
                .map { it.energy }
            val onsetCount = onsets.count { it.isFinite() && it in start..end }
            windows += WindowStats(
                start = start,
                end = end,
                rmsMean = energies.average(),
                onsetPerSec = onsetCount / (end - start),
                centroidMean = if (cents.isEmpty()) 0.0 else cents.average(),
            )
            bar++
        }
        if (windows.isEmpty()) return emptyList()
        val trackMean = windows.map { it.rmsMean }.average()
        val trackPeak = windows.maxOf { it.rmsMean }

        fun slopeBack(index: Int, windowsBack: Int, pick: (WindowStats) -> Double): Double {
            val from = maxOf(0, index - windowsBack)
            val xs = (from..index).map { windows[it].start }
            val ys = (from..index).map { pick(windows[it]) }
            return linearSlope(xs, ys)
        }

        // Preceding-8-bar rising check for DROP: mean slope of rms over the
        // two windows before, positive.
        return windows.mapIndexed { index, window ->
            val pos = window.start / duration
            val type = when {
                window.rmsMean > DROP_RMS_MULTIPLIER * meanRms &&
                    window.onsetPerSec > DROP_ONSET_MULTIPLIER * meanOnset &&
                    window.centroidMean > DROP_CENTROID_HZ &&
                    slopeBack(index, 2) { it.rmsMean } > 0 -> StructureSectionType.DROP
                slopeBack(index, 2) { it.rmsMean } > buildRmsSlope &&
                    slopeBack(index, 2) { it.centroidMean } > buildCentroidSlope &&
                    risingBars(windows, index) >= 8 -> StructureSectionType.BUILD
                window.rmsMean < BREAK_RMS_FRACTION * meanRms &&
                    window.onsetPerSec < BREAK_ONSET_FRACTION * meanOnset &&
                    lowRunBars(windows, index, trackMean) >= 8 &&
                    precededByHighEnergy(windows, index, trackMean) -> StructureSectionType.BREAK
                pos > OUTRO_POSITION_FRACTION &&
                    outroFalling(windows, index) &&
                    window.rmsMean < 0.85 * trackPeak -> StructureSectionType.OUTRO
                pos < INTRO_POSITION_FRACTION &&
                    window.rmsMean < 0.75 * meanRms &&
                    window.centroidMean < 3000.0 -> StructureSectionType.INTRO
                window.rmsMean > 1.05 * meanRms -> StructureSectionType.CHORUS
                else -> StructureSectionType.VERSE
            }
            StructureLabel(window.start, window.end, type)
        }.mergeAdjacent()
    }

    /**
     * Spec slopes are per bar but windows step one bar while spanning four —
     * a per-second slope over window starts equals per-bar slope / barSeconds.
     * Rather than threading tempo through, compare against the spec constant
     * scaled by a nominal 3 s bar (≈128 BPM 4/4: bar = 1.875 s; using 3 s is
     * the conservative direction — fewer false BUILDs on slow tracks).
     * DEVIATION: documented approximation, errs toward fewer labels.
     */
    private fun risingBars(windows: List<WindowStats>, index: Int): Int {
        var count = 0
        var i = index
        while (i > 0 && windows[i].rmsMean >= windows[i - 1].rmsMean) {
            count++
            i--
        }
        return count
    }

    /** Sustained-low run length ending here, in window steps (~bars). */
    private fun lowRunBars(windows: List<WindowStats>, index: Int, trackMean: Double): Int {
        var count = 0
        var i = index
        while (i >= 0 && windows[i].rmsMean < BREAK_RMS_FRACTION * trackMean) {
            count++
            i--
        }
        return count
    }

    /**
     * Single-pass proxy for "preceded by DROP/CHORUS within 4 bars": labels
     * are assigned in this same pass, so a loud predecessor reads as
     * above-mean rms in the 4 windows back.
     */
    private fun precededByHighEnergy(windows: List<WindowStats>, index: Int, trackMean: Double): Boolean {
        for (i in maxOf(0, index - 4) until index) {
            if (windows[i].rmsMean > 1.05 * trackMean) return true
        }
        return false
    }

    private fun outroFalling(windows: List<WindowStats>, index: Int): Boolean {
        // DEVIATION: strict monotonic fall over 16 bars never holds on noisy
        // data — use negative linear slope plus last-means-below-first.
        val from = maxOf(0, index - 15)
        val xs = (from..index).map { windows[it].start }
        val ys = (from..index).map { windows[it].rmsMean }
        if (linearSlope(xs, ys) >= 0) return false
        return ys.takeLast(4).average() < ys.take(4).average()
    }

    private fun List<StructureLabel>.mergeAdjacent(): List<StructureLabel> {
        if (isEmpty()) return this
        val out = mutableListOf(first())
        for (label in drop(1)) {
            val last = out.last()
            if (label.type == last.type && label.start <= last.end + 0.01) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, label.end))
            } else {
                out += label
            }
        }
        return out
    }

    /**
     * v2 §4 energy-gradient buildup foot, on the 250 ms fine curve.
     * Steps: (1) peak energy at [dropSec]; (2) per-sample gradient;
     * (3) scan backward from drop−8 s for the nearest ≤0→>0 flip;
     * (4) validate 8 s ≤ drop−foot ≤ 96 s and rise ≥ 0.25×peak;
     * (5) caller snaps to phrase16. Null when anything fails — the caller
     * falls back to drop−phrase (spec §4 fallback).
     */
    fun gradientBuildup(
        fine: List<EnergySample>,
        dropSec: Double,
        peakEnergy: Double,
    ): Double? {
        if (!dropSec.isFinite() || peakEnergy <= 0 || fine.size < 8) return null
        val pts = fine.filter { it.time.isFinite() && it.energy.isFinite() }.sortedBy { it.time }
        if (pts.size < 8) return null
        val dt = (pts.last().time - pts.first().time) / (pts.size - 1)
        if (!(dt > 0)) return null
        fun energyAt(t: Double): Double {
            val i = pts.binarySearchBy(t) { it.time }.let { if (it < 0) -(it + 1) else it }
                .coerceIn(0, pts.size - 1)
            return pts[i].energy
        }
        val scanStart = dropSec - MIXSET_BUILDUP_MIN_SECONDS
        val scanEnd = dropSec - MIXSET_BUILDUP_MAX_SECONDS
        var t = scanStart
        while (t > scanEnd) {
            val g0 = energyAt(t) - energyAt(t - dt)
            val g1 = energyAt(t + dt) - energyAt(t)
            if (g0 <= 0 && g1 > 0) {
                val span = dropSec - t
                if (span < MIXSET_BUILDUP_MIN_SECONDS || span > MIXSET_BUILDUP_MAX_SECONDS) {
                    t -= dt
                    continue
                }
                // Rise over the climb: mean energy from foot to drop vs foot.
                var sum = 0.0
                var n = 0
                var tt = t
                while (tt <= dropSec) {
                    sum += energyAt(tt)
                    n++
                    tt += dt
                }
                val foot = energyAt(t)
                if (n >= 2 && sum / n >= foot + MIXSET_BUILDUP_RISE_MARGIN * peakEnergy) return t
            }
            t -= dt
        }
        return null
    }
}
