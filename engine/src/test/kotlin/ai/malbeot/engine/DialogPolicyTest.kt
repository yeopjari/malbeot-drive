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
        val q2 = p.nextPrompt(RiskLevel.ALERT, 40_000, false)!! // 무응답 30초 뒤 재확인
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
        assertEquals(DialogResult.DELAYED_OR_WRONG, p.onAnswer(quiz, "7", 9_000, 0).result)
        assertEquals(0, p.consecutiveNoAnswer)
    }

    @Test fun rest_yes_is_accepted_and_result_expires() {
        val p = DialogPolicy()
        val rest = p.promptFor(RiskLevel.DANGER)
        assertTrue(p.onAnswer(rest, "응 가자", 1_000, 0).restAccepted)
        assertEquals(DialogResult.OK, p.currentResult(5 * 60_000))
        assertEquals(DialogResult.NONE, p.currentResult(11 * 60_000))
    }
}
