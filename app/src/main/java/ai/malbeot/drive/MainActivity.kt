package ai.malbeot.drive

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.malbeot.drive.signal.AaosSignals
import ai.malbeot.drive.signal.VehicleProbe
import ai.malbeot.drive.voice.VoiceCoach
import ai.malbeot.engine.DialogPolicy
import ai.malbeot.engine.DialogResult
import ai.malbeot.engine.Prompt
import ai.malbeot.engine.PromptKind
import ai.malbeot.engine.RiskEngine
import ai.malbeot.engine.RiskInput
import ai.malbeot.engine.RiskLevel
import ai.malbeot.engine.SteeringCorrectionDetector
import ai.malbeot.engine.SteeringSample
import java.util.Calendar

/**
 * 3단계: 실시간 차량 신호 + 휴식 권장도 + 음성 대화(말 걸기·대답 판정).
 * - 점수가 30/50/70을 넘으면 말벗이 말을 걸고(가벼운 대화 → 퀴즈 → 쉼터 제안), 대답을 점수에 반영한다.
 * - 아래 시연 버튼은 에뮬레이터에 음성인식 엔진이 없을 때·영상 촬영용으로 대답과 시간을 직접 넣는다.
 */
class MainActivity : AppCompatActivity() {
    private val engine = RiskEngine()
    private val detector = SteeringCorrectionDetector()
    private val dialog = DialogPolicy()
    private val values = linkedMapOf<String, String>()
    private val log = ArrayDeque<String>()
    private val samples = ArrayDeque<SteeringSample>()
    private val main = Handler(Looper.getMainLooper())
    private var speedKmh = 0.0
    private var turnOn = false
    private var driveStartMs: Long? = null
    private var demoExtraMin = 0.0          // 시연용: 연속주행 시간 앞당기기
    private var currentPrompt: Prompt? = null
    private var talk = "말벗: 대기 중"
    private var restStop = ""
    private var lastLevel = RiskLevel.NORMAL

    private lateinit var header: TextView
    private lateinit var talkView: TextView
    private lateinit var detail: TextView
    private lateinit var root: LinearLayout
    private lateinit var probe: VehicleProbe
    private lateinit var aaos: AaosSignals
    private lateinit var voice: VoiceCoach

    private val ticker = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE) }
        talkView = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12) }
        detail = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addButton("말 걸기") { askNow() }
            addButton("대답(정상)") { voice.simulate(answerFor(currentPrompt), 1_500) }
            addButton("늦은 대답") { voice.simulate(answerFor(currentPrompt), 5_000) }
            addButton("무응답") { voice.simulate(null, 7_000) }
            addButton("+30분") { demoExtraMin += 30; render() }
            addButton("휴식 완료") { restDone() }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            addView(header); addView(talkView); addView(buttons); addView(detail)
        }
        setContentView(root)
        probe = VehicleProbe(this, ::onSignal)
        aaos = AaosSignals(this, ::onSignal)
        voice = VoiceCoach(this, onStatus = { s -> onSignal(VehicleProbe.KEY_INFO, s) }, onHeard = ::onHeard)
        render()
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) =
        addView(Button(this@MainActivity).apply { text = label; textSize = 14f; isAllCaps = false; setOnClickListener { onClick() } })

    override fun onStart() {
        super.onStart()
        probe.start()
        voice.start()
        // 차속(android.car.permission.CAR_SPEED)·마이크는 '위험 권한'이라 실행 중에 사용자 허용을 받아야 한다
        val need = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) aaos.start() else requestPermissions(need.toTypedArray(), REQ_PERMS)
        main.post(ticker)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) onSignal(VehicleProbe.KEY_ERROR, "권한 거부: ${p.substringAfterLast('.')}")
        }
        aaos.start()
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        probe.stop(); aaos.stop(); voice.stop()
        super.onStop()
    }

    private fun onSignal(key: String, value: Any?) = runOnUiThread {
        val now = SystemClock.elapsedRealtime()
        when (key) {
            VehicleProbe.KEY_ERROR, VehicleProbe.KEY_INFO -> { log.addFirst("$key: $value"); while (log.size > 8) log.removeLast() }
            else -> values[key] = value?.toString() ?: "null"
        }
        when (key) {
            AaosSignals.KEY_SPEED -> {
                speedKmh = (value as? Number)?.toDouble() ?: 0.0
                if (speedKmh > 1 && driveStartMs == null) driveStartMs = now
            }
            AaosSignals.KEY_GEAR -> if (value?.toString() == "P") restDone(auto = true) // 주차 = 휴식으로 보고 초기화
            VehicleProbe.KEY_TURN_SIGNAL -> turnOn = value?.toString()?.contains("OFF", true) == false && value.toString() != "0"
            VehicleProbe.KEY_STEERING -> VehicleProbe.toDouble(value)?.let { deg ->
                samples.addLast(SteeringSample(now, deg, turnOn, speedKmh))
                while (samples.isNotEmpty() && now - samples.first().tMs > 5 * 60_000) samples.removeFirst()
            }
        }
    }

    /** 점수 계산 → 단계 색 → 필요하면 말 걸기 */
    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val corrections = detector.detect(samples.toList()).size
        val driveMin = (driveStartMs?.let { (now - it) / 60_000.0 } ?: 0.0) + demoExtraMin
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val r = engine.evaluate(RiskInput(driveMin, hour, 0.0, corrections, 1.0, speedKmh,
            dialog.currentResult(now), dialog.consecutiveNoAnswer))

        lastLevel = r.level
        dialog.nextPrompt(r.level, now, voice.busy)?.let { ask(it) }

        root.setBackgroundColor(when (r.level) {
            RiskLevel.NORMAL -> Color.parseColor("#2E7D32"); RiskLevel.CAUTION -> Color.parseColor("#F9A825")
            RiskLevel.ALERT -> Color.parseColor("#EF6C00"); RiskLevel.DANGER -> Color.parseColor("#C62828")
        })
        header.text = "휴식 권장도 ${r.score}  ${LEVEL_KO[r.level]}"
        talkView.text = if (restStop.isEmpty()) talk else "$talk\n$restStop"
        detail.text = buildString {
            appendLine("연속주행 ${"%.1f".format(driveMin)}분 · 급교정 ${corrections}건/5분 · 대화 ${DIALOG_KO[dialog.currentResult(now)]} · 연속 무응답 ${dialog.consecutiveNoAnswer}회")
            appendLine(values.entries.joinToString("  ·  ") { (k, v) -> "$k ${shorten(v)}" })
            log.forEach { appendLine(it) }
        }
    }

    /** 시연 버튼: 지금 단계에 맞는 질문을 바로 던진다(정상 단계면 가벼운 대화) */
    private fun askNow() {
        ask(dialog.promptFor(if (lastLevel == RiskLevel.NORMAL) RiskLevel.CAUTION else lastLevel))
        render()
    }

    private fun ask(p: Prompt) {
        if (voice.busy) return
        currentPrompt = p
        talk = "말벗: ${p.text}"
        voice.ask(p.text)
    }

    private fun onHeard(text: String?, latencyMs: Long) = runOnUiThread {
        val p = currentPrompt ?: return@runOnUiThread
        val a = dialog.onAnswer(p, text, latencyMs, SystemClock.elapsedRealtime())
        val heard = text?.let { "\"$it\"" } ?: "(대답 없음)"
        talk = "운전자: $heard  →  ${DIALOG_KO[a.result]} (${latencyMs / 1000.0}초)"
        when {
            a.restAccepted -> {
                restStop = "🅿 쉼터를 경유지로 추가합니다 (NaviHelper 연동 예정)"
                voice.say("좋아요. 가까운 쉼터를 경유지로 넣을게요.")
            }
            p.kind == PromptKind.REST -> voice.say("알겠어요. 조금 뒤에 다시 여쭤볼게요.")
            a.result == DialogResult.NO_ANSWER -> voice.say("괜찮으세요? 대답이 없으셔서 곧 다시 여쭐게요.")
            a.result == DialogResult.OK -> voice.say("좋아요. 안전 운전하세요.")
        }
        render()
    }

    private fun restDone(auto: Boolean = false) {
        if (auto && driveStartMs == null && demoExtraMin == 0.0) return
        driveStartMs = null; demoExtraMin = 0.0; samples.clear()
        dialog.resetAfterRest(); restStop = ""
        talk = if (auto) "말벗: 주차했네요. 푹 쉬고 출발하세요." else "말벗: 휴식 완료, 처음부터 다시 셉니다."
    }

    /** 시연 버튼용 모범 대답 */
    private fun answerFor(p: Prompt?): String = when (p?.kind) {
        PromptKind.QUIZ -> p.expected.firstOrNull() ?: "네"
        PromptKind.REST -> "응"
        else -> "요즘 아이유 노래 들어요"
    }

    private fun shorten(v: String) = if (v.length > 18) v.take(18) + "…" else v

    companion object {
        private const val REQ_PERMS = 1
        private val RUNTIME_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            android.Manifest.permission.RECORD_AUDIO,
        )
        private val LEVEL_KO = mapOf(RiskLevel.NORMAL to "정상", RiskLevel.CAUTION to "주의", RiskLevel.ALERT to "경계", RiskLevel.DANGER to "위험")
        private val DIALOG_KO = mapOf(
            DialogResult.NONE to "없음", DialogResult.OK to "정상 응답",
            DialogResult.DELAYED_OR_WRONG to "늦음/오답", DialogResult.NO_ANSWER to "무응답",
        )
    }
}
