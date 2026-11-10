package ai.malbeot.drive

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.malbeot.drive.signal.AaosSignals
import ai.malbeot.drive.signal.VehicleProbe
import ai.malbeot.engine.DialogResult
import ai.malbeot.engine.RiskEngine
import ai.malbeot.engine.RiskInput
import ai.malbeot.engine.RiskLevel
import ai.malbeot.engine.SteeringCorrectionDetector
import ai.malbeot.engine.SteeringSample
import java.util.Calendar

/**
 * 2단계: 실시간 차량 신호 확인 화면.
 * Virtual Console에서 조향각·방향지시등·기어를 바꾸면 값과 휴식 권장도가 바로 바뀌는지 확인한다.
 */
class MainActivity : AppCompatActivity() {
    private val engine = RiskEngine()
    private val detector = SteeringCorrectionDetector()
    private val values = linkedMapOf<String, String>()
    private val log = ArrayDeque<String>()
    private val samples = ArrayDeque<SteeringSample>()
    private var speedKmh = 0.0
    private var turnOn = false
    private var driveStartMs: Long? = null
    private lateinit var text: TextView
    private lateinit var probe: VehicleProbe
    private lateinit var aaos: AaosSignals

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 22f; setPadding(40, 30, 40, 30); setTextColor(Color.WHITE) }
        setContentView(text)
        probe = VehicleProbe(this, ::onSignal)
        aaos = AaosSignals(this, ::onSignal)
        render()
    }

    override fun onStart() {
        super.onStart()
        probe.start()
        // 차속(android.car.permission.CAR_SPEED)은 '위험 권한'이라 실행 중에 사용자 허용을 받아야 한다
        val need = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) aaos.start() else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) onSignal(VehicleProbe.KEY_ERROR, "권한 거부: ${p.substringAfterLast('.')}")
        }
        aaos.start()
    }
    override fun onStop() { probe.stop(); aaos.stop(); super.onStop() }

    private fun onSignal(key: String, value: Any?) = runOnUiThread {
        val now = SystemClock.elapsedRealtime()
        when (key) {
            VehicleProbe.KEY_ERROR, VehicleProbe.KEY_INFO -> { log.addFirst("$key: $value"); while (log.size > 10) log.removeLast() }
            else -> values[key] = value?.toString() ?: "null"
        }
        when (key) {
            AaosSignals.KEY_SPEED -> {
                speedKmh = (value as? Number)?.toDouble() ?: 0.0
                if (speedKmh > 1 && driveStartMs == null) driveStartMs = now
                if (speedKmh <= 1) driveStartMs = null
            }
            VehicleProbe.KEY_TURN_SIGNAL -> turnOn = value?.toString()?.contains("OFF", true) == false && value.toString() != "0"
            VehicleProbe.KEY_STEERING -> VehicleProbe.toDouble(value)?.let { deg ->
                samples.addLast(SteeringSample(now, deg, turnOn, speedKmh))
                while (samples.isNotEmpty() && now - samples.first().tMs > 5 * 60_000) samples.removeFirst()
            }
        }
        render()
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val corrections = detector.detect(samples.toList()).size
        val driveMin = driveStartMs?.let { (now - it) / 60_000.0 } ?: 0.0
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val r = engine.evaluate(RiskInput(driveMin, hour, 0.0, corrections, 1.0, speedKmh, DialogResult.NONE, 0))
        text.setBackgroundColor(when (r.level) {
            RiskLevel.NORMAL -> Color.parseColor("#2E7D32"); RiskLevel.CAUTION -> Color.parseColor("#F9A825")
            RiskLevel.ALERT -> Color.parseColor("#EF6C00"); RiskLevel.DANGER -> Color.parseColor("#C62828")
        })
        text.text = buildString {
            appendLine("말벗 드라이브 · 차량 신호 확인")
            appendLine("휴식 권장도 ${r.score} (${r.level})  ·  연속주행 ${"%.1f".format(driveMin)}분  ·  급교정 ${corrections}건/5분")
            appendLine()
            if (values.isEmpty()) appendLine("아직 받은 차량 값 없음")
            values.forEach { (k, v) -> appendLine("$k : $v") }
            appendLine()
            log.forEach { appendLine(it) }
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private val RUNTIME_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            android.Manifest.permission.RECORD_AUDIO,
        )
    }
}
