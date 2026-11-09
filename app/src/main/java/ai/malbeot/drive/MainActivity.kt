package ai.malbeot.drive

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.malbeot.engine.DialogResult
import ai.malbeot.engine.RiskEngine
import ai.malbeot.engine.RiskInput
import ai.malbeot.engine.RiskLevel

/**
 * 1단계 골격: 엔진 동작 확인용 화면.
 * 다음 단계: SignalCollector(Vehicle SDK 콜백) → 실시간 점수, DialogManager, NaviHelper 경유지, 데모 재생.
 */
class MainActivity : AppCompatActivity() {
    private val engine = RiskEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 데모: 시나리오 S1 00:30 시점 값
        val r = engine.evaluate(RiskInput(150.0, 0, 0.9, 3, 1.5, 95.0, DialogResult.NO_ANSWER, 2))
        val view = TextView(this).apply {
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "휴식 권장도 ${r.score}\n${r.level}"
            setBackgroundColor(
                when (r.level) {
                    RiskLevel.NORMAL -> Color.parseColor("#2E7D32")
                    RiskLevel.CAUTION -> Color.parseColor("#F9A825")
                    RiskLevel.ALERT -> Color.parseColor("#EF6C00")
                    RiskLevel.DANGER -> Color.parseColor("#C62828")
                }
            )
        }
        setContentView(view)
    }
}
