package com.music.bitchord

import com.music.bitchord.playback.smart.PitchTracker
import com.music.bitchord.playback.smart.camelotOf
import com.music.bitchord.playback.smart.keyRootIndex
import com.music.bitchord.playback.smart.keyScore
import com.music.bitchord.playback.smart.pitchVetoesShift
import com.music.bitchord.playback.smart.semitonesToShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * P4 guarantee layer (c): the pitch math proves itself on synthetic signals
 * under plain JUnit — no device, no model file. YIN and the CREPE decode are
 * the only parts under test; the ONNX session itself is verified by CI
 * packaging + the fallback that makes its absence survivable.
 */
class PitchTrackerTest {

    private fun sine(freqHz: Double, seconds: Double, rate: Int = 16000): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (0.5 * sin(2 * PI * freqHz * i / rate)).toFloat() }
    }

    @Test
    fun yin_tracksA440_within5Hz() {
        val curve = PitchTracker.yinTrack(sine(440.0, 2.0))
        assertNotNull(curve)
        assertEquals(440.0, curve!!.voicedMedianHz(), 5.0)
        assertTrue(curve.voicedMeanConfidence() > 0.5)
    }

    @Test
    fun yin_tracksA220_within5Hz() {
        val curve = PitchTracker.yinTrack(sine(220.0, 2.0))
        assertNotNull(curve)
        assertEquals(220.0, curve!!.voicedMedianHz(), 5.0)
    }

    @Test
    fun yin_callsSilenceUnvoiced() {
        val curve = PitchTracker.yinTrack(FloatArray(16000))
        assertNotNull(curve)
        assertEquals(0.0, curve!!.voicedMedianHz(), 0.0)
        assertEquals(0.0, curve.voicedMeanConfidence(), 0.0)
    }

    @Test
    fun yin_rejectsTooShortInput() {
        assertEquals(null, PitchTracker.yinTrack(FloatArray(100)))
    }

    @Test
    fun decode_oneHotBin_recoversItsFrequency() {
        val activation = FloatArray(PitchTracker.BINS) { -10f }
        activation[100] = 10f
        val (freq, conf) = PitchTracker.decodeActivation(activation)
        val expected = PitchTracker.binsToFrequency(100)
        assertEquals(expected, freq, expected * 0.02)
        assertTrue(conf > 0.5)
    }

    @Test
    fun decode_flatActivation_isUnvoiced() {
        val (freq, _) = PitchTracker.decodeActivation(FloatArray(PitchTracker.BINS))
        assertEquals(0.0, freq, 0.0)
    }

    @Test
    fun decode_wrongSize_isUnvoiced() {
        val (freq, conf) = PitchTracker.decodeActivation(FloatArray(10))
        assertEquals(0.0, freq, 0.0)
        assertEquals(0.0, conf, 0.0)
    }

    @Test
    fun semitoneGap_octaveIs12_unmeasuredIsNull() {
        assertEquals(0.0, PitchTracker.pitchSemitoneGap(440.0, 440.0)!!, 1e-9)
        assertEquals(12.0, PitchTracker.pitchSemitoneGap(440.0, 880.0)!!, 1e-9)
        assertEquals(null, PitchTracker.pitchSemitoneGap(0.0, 440.0))
        assertEquals(null, PitchTracker.pitchSemitoneGap(440.0, 0.0))
    }

    @Test
    fun camelot_parsesAndScores() {
        assertEquals(8 to true, camelotOf("A minor"))
        assertEquals(1.0, keyScore("A minor", "A minor"), 1e-9)
        assertEquals(0.85, keyScore("C major", "A minor"), 1e-9)
        assertEquals(9, keyRootIndex("A minor"))
        assertEquals(4, keyRootIndex("E minor"))
    }

    @Test
    fun shift_reachesAdjacentInsideCap() {
        assertEquals(0, semitonesToShift("C major", "C major"))
        // D major comes DOWN two semitones onto C.
        assertEquals(-2, semitonesToShift("C major", "D major"))
        // F# up one is G, a fifth off C: the tritone is salvageable.
        assertEquals(1, semitonesToShift("C major", "F# major"))
        // An unparseable key shifts nowhere.
        assertEquals(0, semitonesToShift("C major", "???"))
    }

    @Test
    fun veto_onlyFiresOnRealContradiction() {
        // 440 Hz IS A: no veto against A major, none without measurement.
        assertFalse(pitchVetoesShift(440.0, "A major"))
        assertFalse(pitchVetoesShift(0.0, "D major"))
        assertFalse(pitchVetoesShift(440.0, "???"))
        // 440 Hz against D major is 5 semitones off: the key is misdetected.
        assertTrue(pitchVetoesShift(440.0, "D major"))
        // 440 Hz against C major is exactly 3 off: within tolerance.
        assertFalse(pitchVetoesShift(440.0, "C major"))
    }
}
