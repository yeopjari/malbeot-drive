package ai.malbeot.engine

/**
 * 조향 급교정 판정 (계획 문서 5.2 정의, 수치는 초기값).
 * ① 3초 이상 조향 변화율 < 0.5°/s (손이 멈춤)
 * ② 이어서 1초 안에 6° 이상 변화, 변화율 ≥ 20°/s
 * ③ 앞뒤 5초 방향지시등 꺼짐, 차속 ≥ 60km/h
 */
data class SteeringSample(val tMs: Long, val angleDeg: Double, val turnSignalOn: Boolean, val speedKmh: Double)

class SteeringCorrectionDetector(
    private val calmRateDegPerS: Double = 0.5,
    private val calmMinMs: Long = 3000,
    private val jumpDeg: Double = 6.0,
    private val jumpRateDegPerS: Double = 20.0,
    private val jumpWindowMs: Long = 1000,
    private val signalGuardMs: Long = 5000,
    private val minSpeedKmh: Double = 60.0,
) {
    /** 시간순 샘플에서 급교정 발생 시각 목록을 돌려준다. */
    fun detect(samples: List<SteeringSample>): List<Long> {
        val events = mutableListOf<Long>()
        if (samples.size < 3) return events
        var calmStart: Long? = null
        var i = 1
        while (i < samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val dt = (b.tMs - a.tMs).coerceAtLeast(1) / 1000.0
            val rate = kotlin.math.abs(b.angleDeg - a.angleDeg) / dt
            if (rate < calmRateDegPerS) {
                if (calmStart == null) calmStart = a.tMs
                i++; continue
            }
            val start = calmStart
            calmStart = null
            if (start != null && a.tMs - start >= calmMinMs) {
                // 1초 창 안의 최대 변화 확인
                val base = a.angleDeg
                var j = i; var maxDelta = 0.0; var endT = b.tMs
                while (j < samples.size && samples[j].tMs - a.tMs <= jumpWindowMs) {
                    val d = kotlin.math.abs(samples[j].angleDeg - base)
                    if (d > maxDelta) { maxDelta = d; endT = samples[j].tMs }
                    j++
                }
                val jumpRate = maxDelta / ((endT - a.tMs).coerceAtLeast(1) / 1000.0)
                val guardOk = samples.none { kotlin.math.abs(it.tMs - a.tMs) <= signalGuardMs && it.turnSignalOn }
                if (maxDelta >= jumpDeg && jumpRate >= jumpRateDegPerS && guardOk && a.speedKmh >= minSpeedKmh) {
                    events += a.tMs
                    i = j; continue
                }
            }
            i++
        }
        return events
    }
}
