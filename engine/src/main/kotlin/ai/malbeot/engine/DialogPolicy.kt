package ai.malbeot.engine

/**
 * 말벗 대화 정책: 언제 말을 걸고, 대답을 어떻게 판정하고, 결과를 점수에 어떻게 넣을지.
 * 계획 문서 4장(시나리오 S1·S4·S5) 기준. 안드로이드 의존성이 없어 PC에서 단위 테스트한다.
 */
enum class PromptKind { CHAT, QUIZ, REST }

data class Prompt(val kind: PromptKind, val text: String, val expected: List<String> = emptyList())

/**
 * 시간 기준은 '질문이 끝난 뒤 운전자가 말을 시작하기까지(발화 시작 지연)'로 잰다.
 * - 일상 대화의 차례 바꿈 간격은 보통 1초 이하 → 3초 안에 말을 시작하면 정상
 * - 3~7초: 늦은 반응(졸림 의심). 7초 안에 말을 시작하지 않으면 무응답.
 *   100km/h에서 7초 ≈ 194m 주행이므로 그 이상 기다리지 않는다.
 * - 무응답이면 15초 뒤 다시 확인(2회 무응답 → 바닥 70점, 쉼터 제안)
 * 수치는 초기 추정치이며 실차 데이터로 보정한다.
 */
data class DialogConfig(
    val fastAnswerMs: Long = 3_000,        // 이 안에 말을 시작하면 정상
    val noAnswerMs: Long = 7_000,          // 이 안에 말을 시작하지 않으면 무응답
    val sameLevelCooldownMs: Long = 3 * 60_000,
    val noAnswerRetryMs: Long = 15_000,     // 무응답이면 15초 뒤 다시 확인
    val resultValidMs: Long = 10 * 60_000,  // 대화 결과가 점수에 반영되는 시간
    val refusalRetryMs: Long = 10 * 60_000, // 쉼터 제안 거절 후 다시 묻기까지(S5)
    val softAfterRefusals: Int = 2,         // 이만큼 거절하면 경유지 대신 환기·안내로 낮춤(S5)
)

class DialogPolicy(val cfg: DialogConfig = DialogConfig()) {
    var lastResult: DialogResult = DialogResult.NONE; private set
    var consecutiveNoAnswer: Int = 0; private set
    private var lastResultAt: Long = Long.MIN_VALUE / 2
    private var lastAskAt: Long = Long.MIN_VALUE / 2
    private var lastAskedLevel: RiskLevel = RiskLevel.NORMAL
    private var quizIndex = 0
    var restRefusals: Int = 0; private set
    private var lastRefusalAt: Long = Long.MIN_VALUE / 2
    /** 말을 걸 차례였지만 운전 부하(방향지시등·큰 조향) 때문에 미루는 중(S6) */
    var deferred: Boolean = false; private set

    /** 점수 계산에 넣을 현재 대화 결과(유효 시간이 지나면 NONE) */
    fun currentResult(now: Long): DialogResult =
        if (now - lastResultAt <= cfg.resultValidMs) lastResult else DialogResult.NONE

    /**
     * 지금 말을 걸어야 하면 질문을, 아니면 null.
     * driverBusy=true(방향지시등 작동·큰 조향 중)이면 말 걸 차례여도 미룬다(S6). 끝나면 바로 묻는다.
     */
    fun nextPrompt(level: RiskLevel, now: Long, busy: Boolean, driverBusy: Boolean = false): Prompt? {
        if (busy || level == RiskLevel.NORMAL) { deferred = false; return null }
        val levelUp = level.ordinal > lastAskedLevel.ordinal
        val retry = lastResult == DialogResult.NO_ANSWER && now - lastResultAt >= cfg.noAnswerRetryMs // 무응답 판정 시점부터
        val cooled = now - lastAskAt >= cfg.sameLevelCooldownMs
        val refusedRecently = level == RiskLevel.DANGER && !retry && now - lastRefusalAt < cfg.refusalRetryMs
        if ((!levelUp && !retry && !cooled) || refusedRecently) { deferred = false; return null }
        if (driverBusy) { deferred = true; return null }
        deferred = false
        lastAskAt = now
        lastAskedLevel = level
        return promptFor(level)
    }

    fun promptFor(level: RiskLevel): Prompt = when (level) {
        RiskLevel.NORMAL, RiskLevel.CAUTION ->
            Prompt(PromptKind.CHAT, "운전하신 지 꽤 됐네요. 요즘 즐겨 듣는 노래 있으세요?")
        RiskLevel.ALERT -> QUIZZES[quizIndex++ % QUIZZES.size]
        RiskLevel.DANGER -> if (restRefusals >= cfg.softAfterRefusals)
            Prompt(PromptKind.REST, "창문을 조금 열어 환기해 보세요. 쉼터에 들르고 싶으시면 응 이라고만 해 주세요.", YES_WORDS)
        else
            Prompt(PromptKind.REST, "졸음 위험이 높아요. 가까운 쉼터에 들를까요? 응 이라고 하시면 경유지로 넣을게요.", YES_WORDS)
    }

    /**
     * 대답 판정. heard=null 이면 못 들음(무응답).
     * CHAT: 무엇이든 빨리 말하면 OK. QUIZ: 정답+빠름이면 OK, 늦거나 틀리면 DELAYED_OR_WRONG.
     * REST: 대답 여부만 점수에 반영하고, 수락 여부는 accepted로 따로 돌려준다.
     */
    fun onAnswer(prompt: Prompt, heard: String?, latencyMs: Long, now: Long): Answer {
        val text = heard?.trim().orEmpty()
        val result = when {
            text.isEmpty() || latencyMs > cfg.noAnswerMs -> DialogResult.NO_ANSWER
            prompt.kind == PromptKind.QUIZ && !matches(text, prompt.expected) -> DialogResult.DELAYED_OR_WRONG
            latencyMs > cfg.fastAnswerMs -> DialogResult.DELAYED_OR_WRONG
            else -> DialogResult.OK
        }
        val accepted = prompt.kind == PromptKind.REST && isYes(text)
        val refused = prompt.kind == PromptKind.REST && result != DialogResult.NO_ANSWER && !accepted
        consecutiveNoAnswer = when {
            result == DialogResult.NO_ANSWER -> consecutiveNoAnswer + 1
            refused -> consecutiveNoAnswer // 거절은 존중하되 졸음 판단은 풀지 않는다(위험 단계 유지)
            else -> 0
        }
        if (refused) { restRefusals++; lastRefusalAt = now }
        if (accepted) restRefusals = 0
        lastResult = result
        lastResultAt = now
        return Answer(result, accepted, refused)
    }

    /** 휴식(시동 끔·주차) 후 초기화 */
    fun resetAfterRest() {
        lastResult = DialogResult.NONE; consecutiveNoAnswer = 0
        lastAskedLevel = RiskLevel.NORMAL; lastAskAt = Long.MIN_VALUE / 2
        restRefusals = 0; lastRefusalAt = Long.MIN_VALUE / 2; deferred = false
    }

    data class Answer(val result: DialogResult, val restAccepted: Boolean, val restRefused: Boolean = false)

    companion object {
        val YES_WORDS = listOf("응", "네", "예", "좋아", "그래", "어 ", "웅", "콜", "들를게", "가자")
        val QUIZZES = listOf(
            Prompt(PromptKind.QUIZ, "잠깐 퀴즈요. 삼 더하기 사는 뭘까요?", listOf("7", "칠", "일곱")),
            Prompt(PromptKind.QUIZ, "오늘 무슨 요일인지 말씀해 주세요.", listOf("요일")),
            Prompt(PromptKind.QUIZ, "십에서 삼을 빼면요?", listOf("7", "칠", "일곱")),
        )
        val NO_WORDS = listOf("아니", "싫어", "괜찮", "됐어", "안 가", "나중에")
        fun matches(text: String, words: List<String>) = words.any { "$text ".contains(it) }
        fun isYes(text: String) = !matches(text, NO_WORDS) && matches(text, YES_WORDS)
    }
}
