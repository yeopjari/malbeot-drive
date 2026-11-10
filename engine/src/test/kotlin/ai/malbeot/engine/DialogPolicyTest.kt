package ai.malbeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogPolicyTest {
    @Test fun normal_level_never_asks() {
        assertNull(DialogPolicy().nextPrompt(RiskLevel.NORMAL, 0, busy = false))
    }

    @Test fun level_up_asks_once_then_waits_for_cooldown() {
        val p = DialogPolicy()
        assertEquals(PromptKind.CHAT, p.nextPrompt(RiskLevel.CAUTION, 0, false)!!.kind)
        assertNull(p.nextPrompt(RiskLevel.CAUTION, 60_000, false))
        assertEquals(PromptKind.QUIZ, p.nextPrompt(RiskLevel.ALERT, 61_000, false)!!.kind) // 단계 상승은 즉시
        assertNotNull(p.nextPrompt(RiskLevel.ALERT, 61_000 + 3 * 60_000, false))
    }

    @Test fun two_no_answers_reach_floor_70_in_engine() {
        val p = DialogPolicy()
        val q = p.nextPrompt(RiskLevel.ALERT, 0, false)!!
        p.onAnswer(q, null, 8_000, 8_000)
        assertNull(p.nextPrompt(RiskLevel.ALERT, 20_000, false))   // 무응답 후 15초 전에는 재질문 안 함
        val q2 = p.nextPrompt(RiskLevel.ALERT, 40_000, false)!! // 무응답 15초 뒤 재확인
        p.onAnswer(q2, "", 8_000, 48_000)
        assertEquals(2, p.consecutiveNoAnswer)
        val r = RiskEngine().evaluate(RiskInput(30.0, 14, 0.0, 0, 1.0, 100.0, p.currentResult(48_000), p.consecutiveNoAnswer))
        assertTrue(r.score >= 70.0)
    }

    @Test fun quiz_answer_judging() {
        val p = DialogPolicy()
        val quiz = DialogPolicy.QUIZZES[0]
        assertEquals(DialogResult.OK, p.onAnswer(quiz, "칠이요", 2_000, 0).result)
        assertEquals(DialogResult.DELAYED_OR_WRONG, p.onAnswer(quiz, "팔", 2_000, 0).result)
        assertEquals(DialogResult.DELAYED_OR_WRONG, p.onAnswer(quiz, "7", 5_000, 0).result) // 3~7초: 늦음
        assertEquals(DialogResult.NO_ANSWER, p.onAnswer(quiz, "7", 9_000, 0).result)        // 7초 넘게 말 시작 안 함
        assertEquals(1, p.consecutiveNoAnswer)
        assertEquals(DialogResult.OK, p.onAnswer(quiz, "7", 2_500, 0).result)
        assertEquals(0, p.consecutiveNoAnswer)
    }

    @Test fun rest_yes_is_accepted_and_result_expires() {
        val p = DialogPolicy()
        val rest = p.promptFor(RiskLevel.DANGER)
        assertTrue(p.onAnswer(rest, "응 가자", 1_000, 0).restAccepted)
        assertEquals(DialogResult.OK, p.currentResult(5 * 60_000))
        assertEquals(DialogResult.NONE, p.currentResult(11 * 60_000))
    }

    @Test fun s5_refusal_is_respected_then_softened() {
        val p = DialogPolicy()
        val rest = p.nextPrompt(RiskLevel.DANGER, 0, false)!!
        val a = p.onAnswer(rest, "아니 괜찮아", 1_000, 1_000)
        assertTrue(a.restRefused); assertTrue(!a.restAccepted)
        assertNull(p.nextPrompt(RiskLevel.DANGER, 5 * 60_000, false))          // 10분 동안 다시 묻지 않음
        val again = p.nextPrompt(RiskLevel.DANGER, 11 * 60_000, false)!!
        p.onAnswer(again, "아니", 1_000, 11 * 60_000)
        assertEquals(2, p.restRefusals)
        assertTrue(p.promptFor(RiskLevel.DANGER).text.contains("환기"))     // 2회 거절 → 안내 수준 낮춤
        assertTrue(p.onAnswer(p.promptFor(RiskLevel.DANGER), "응", 1_000, 12 * 60_000).restAccepted)
    }

    @Test fun s6_defers_while_driver_busy_then_asks() {
        val p = DialogPolicy()
        assertNull(p.nextPrompt(RiskLevel.CAUTION, 0, false, driverBusy = true))
        assertTrue(p.deferred)
        assertEquals(PromptKind.CHAT, p.nextPrompt(RiskLevel.CAUTION, 3_000, false, driverBusy = false)!!.kind)
        assertTrue(!p.deferred)
    }

    @Test fun engine_reports_points_per_factor() {
        val r = RiskEngine().evaluate(RiskInput(120.0, 3, 1.0, 0, 1.0, 100.0, DialogResult.NONE, 0))
        assertEquals(12.5, r.points["sT"]); assertEquals(15.0, r.points["sC"]); assertEquals(10.0, r.points["sM"])
        assertEquals(37.5, r.raw); assertEquals(50.0, r.score)  // 2시간 바닥 규칙
    }
}
