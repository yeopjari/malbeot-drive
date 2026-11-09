package ai.malbeot.engine

/**
 * 말벗 휴식 권장도(위험 점수) 엔진.
 * 계획 문서 5장과 엑셀 계산기(공모전_기획확정_말벗위험점수계산기_20261109_19.xlsx)와 같은 식.
 *
 *   원점수 = 25·sT + 15·sC + 10·sM + 20·sS + 10·sV + 20·sD
 *   바닥   = max(연속주행 ≥ 120분 → 50, 연속 무응답 ≥ 2회 → 70)
 *   R      = min(100, max(원점수, 바닥))
 */
data class RiskParams(
    val wT: Double = 25.0, val wC: Double = 15.0, val wM: Double = 10.0,
    val wS: Double = 20.0, val wV: Double = 10.0, val wD: Double = 20.0,
    val tStartMin: Double = 60.0, val tFullMin: Double = 180.0,
    val corrFull: Double = 3.0,
    val vBase: Double = 1.0, val vSpan: Double = 1.0, val vMinSpeedKmh: Double = 60.0,
    val floorDriveMin: Double = 120.0, val floorDriveScore: Double = 50.0,
    val floorNoAnswerCount: Int = 2, val floorNoAnswerScore: Double = 70.0,
    val cautionAt: Double = 30.0, val alertAt: Double = 50.0, val dangerAt: Double = 70.0,
)

/** 한 시점의 입력 특징값. FeatureExtractor가 차량 신호에서 만든다. */
data class RiskInput(
    val continuousDriveMin: Double,   // 휴식 후 연속 주행(분)
    val hourOfDay: Int,               // 0~23
    val monotonyRatio: Double,        // 최근 15분 고속 단조 비율 0~1
    val sharpCorrections5m: Int,      // 최근 5분 조향 급교정 건수
    val speedVarRatio: Double,        // σ현재 / σ기준
    val avgSpeedKmh: Double,
    val dialogResult: DialogResult,   // 최근 대화 결과
    val consecutiveNoAnswer: Int,
)

enum class DialogResult(val s: Double) { NONE(0.0), OK(0.0), DELAYED_OR_WRONG(0.5), NO_ANSWER(1.0) }

enum class RiskLevel { NORMAL, CAUTION, ALERT, DANGER }

data class RiskResult(val raw: Double, val floor: Double, val score: Double, val level: RiskLevel, val parts: Map<String, Double>)

class RiskEngine(private val p: RiskParams = RiskParams()) {

    fun circadian(hour: Int): Double = when (hour) {
        in 2..5 -> 1.0
        in 14..15 -> 0.6
        in 22..23, 0, 1 -> 0.4
        else -> 0.0
    }

    fun evaluate(i: RiskInput): RiskResult {
        val sT = clamp01((i.continuousDriveMin - p.tStartMin) / (p.tFullMin - p.tStartMin))
        val sC = circadian(i.hourOfDay)
        val sM = clamp01(i.monotonyRatio)
        val sS = minOf(1.0, i.sharpCorrections5m / p.corrFull)
        val sV = if (i.avgSpeedKmh < p.vMinSpeedKmh) 0.0 else clamp01((i.speedVarRatio - p.vBase) / p.vSpan)
        val sD = i.dialogResult.s
        val raw = round1(p.wT * sT + p.wC * sC + p.wM * sM + p.wS * sS + p.wV * sV + p.wD * sD)
        val floor = maxOf(
            if (i.continuousDriveMin >= p.floorDriveMin) p.floorDriveScore else 0.0,
            if (i.consecutiveNoAnswer >= p.floorNoAnswerCount) p.floorNoAnswerScore else 0.0,
        )
        val score = minOf(100.0, maxOf(raw, floor))
        return RiskResult(raw, floor, score, levelOf(score),
            mapOf("sT" to sT, "sC" to sC, "sM" to sM, "sS" to sS, "sV" to sV, "sD" to sD))
    }

    fun levelOf(score: Double): RiskLevel = when {
        score >= p.dangerAt -> RiskLevel.DANGER
        score >= p.alertAt -> RiskLevel.ALERT
        score >= p.cautionAt -> RiskLevel.CAUTION
        else -> RiskLevel.NORMAL
    }

    private fun clamp01(x: Double) = x.coerceIn(0.0, 1.0)
    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
}
