package ai.malbeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** 엑셀 S1시뮬레이션 시트와 같은 값이 나오는지 확인 */
class RiskEngineTest {
    private val e = RiskEngine()

    private fun input(t: Double, h: Int, m: Double, s: Int, v: Double, spd: Double, d: DialogResult, na: Int) =
        RiskInput(t, h, m, s, v, spd, d, na)

    @Test fun s1_departure_is_normal() {
        val r = e.evaluate(input(0.0, 22, 0.2, 0, 1.0, 95.0, DialogResult.NONE, 0))
        assertEquals(8.0, r.score); assertEquals(RiskLevel.NORMAL, r.level)
    }

    @Test fun s1_100min_is_caution() {
        val r = e.evaluate(input(100.0, 23, 0.9, 2, 1.3, 98.0, DialogResult.OK, 0))
        assertEquals(39.7, r.score); assertEquals(RiskLevel.CAUTION, r.level)
    }

    @Test fun s1_two_hours_floor_50() {
        val r = e.evaluate(input(120.0, 0, 0.9, 2, 1.4, 97.0, DialogResult.OK, 0))
        assertEquals(44.8, r.raw); assertEquals(50.0, r.score); assertEquals(RiskLevel.ALERT, r.level)
    }

    @Test fun s1_no_answer_twice_is_danger() {
        val r = e.evaluate(input(150.0, 0, 0.9, 3, 1.5, 95.0, DialogResult.NO_ANSWER, 2))
        assertEquals(78.8, r.score); assertEquals(RiskLevel.DANGER, r.level)
    }

    @Test fun congestion_masks_speed_variation() {
        val r = e.evaluate(input(30.0, 10, 0.0, 0, 3.0, 25.0, DialogResult.NONE, 0))
        assertEquals(0.0, r.parts["sV"]); assertEquals(RiskLevel.NORMAL, r.level)
    }
}
