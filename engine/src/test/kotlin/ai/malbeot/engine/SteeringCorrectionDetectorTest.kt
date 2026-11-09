package ai.malbeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SteeringCorrectionDetectorTest {
    private val d = SteeringCorrectionDetector()

    /** 4초 동안 핸들 고정 → 0.3초 만에 8° 교정 */
    private fun drift(signal: Boolean, speed: Double = 100.0): List<SteeringSample> {
        val out = mutableListOf<SteeringSample>()
        var t = 0L
        repeat(41) { out += SteeringSample(t, 0.0, signal, speed); t += 100 }
        out += SteeringSample(t, 3.0, signal, speed); t += 100
        out += SteeringSample(t, 6.0, signal, speed); t += 100
        out += SteeringSample(t, 8.0, signal, speed); t += 100
        repeat(10) { out += SteeringSample(t, 8.0, signal, speed); t += 100 }
        return out
    }

    @Test fun detects_correction_after_calm() = assertEquals(1, d.detect(drift(false)).size)
    @Test fun ignores_when_turn_signal_on() = assertEquals(0, d.detect(drift(true)).size)
    @Test fun ignores_low_speed() = assertEquals(0, d.detect(drift(false, 40.0)).size)
}
