package ai.malbeot.drive.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 말하기(TTS) + 듣기(STT). Pleos TTS/STT가 '준비 중'(11/10 콘솔 확인)이라 안드로이드 기본 기능을 쓴다.
 * 듣기가 안 되는 환경(에뮬레이터에 음성인식 엔진 없음 등)에서는 화면의 시연 버튼으로 대답을 넣는다.
 *
 * onHeard(text, latencyMs): text=null 이면 못 들음(무응답)
 */
class VoiceCoach(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onHeard: (text: String?, latencyMs: Long) -> Unit,
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listenStartedAt = 0L
    var busy = false; private set
    val canListen: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS &&
                tts?.setLanguage(Locale.KOREAN).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
            onStatus(if (ttsReady) "말하기(TTS) 준비됨" else "말하기(TTS) 사용 불가 → 화면 글자로만 표시")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id?.startsWith("ask-") == true) main.post { listen() } }
                @Deprecated("old API") override fun onError(id: String?) { if (id?.startsWith("ask-") == true) main.post { listen() } }
            })
        }
        onStatus(if (canListen) "듣기(STT) 준비됨" else "듣기(STT) 엔진 없음 → 시연 버튼으로 대답")
    }

    /** 질문을 말하고, 끝나면 자동으로 듣기 시작 */
    fun ask(text: String) {
        if (busy) return
        busy = true
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ask-${SystemClock.elapsedRealtime()}")
        else main.postDelayed({ listen() }, 1_500)
    }

    private fun listen() {
        if (!busy) return
        listenStartedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, NO_ANSWER_TIMEOUT_MS) // 10초 안에 대답이 없으면 무응답
        if (!canListen) { onStatus("듣는 중… (시연 버튼으로 대답 가능)"); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) = onStatus("듣는 중…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        })
    }

    /** 시연 버튼: 대답을 직접 넣는다(latencyMs 지정 가능) */
    fun simulate(text: String?, latencyMs: Long? = null) {
        if (!busy) return
        recognizer?.cancel()
        finish(text, latencyMs)
    }

    private val timeout = Runnable { recognizer?.cancel(); finish(null) }

    private fun finish(text: String?, forcedLatency: Long? = null) {
        if (!busy) return
        busy = false
        main.removeCallbacks(timeout)
        val latency = forcedLatency ?: (SystemClock.elapsedRealtime() - listenStartedAt)
        onHeard(text, latency)
    }

    fun say(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "say-${SystemClock.elapsedRealtime()}") }

    companion object { const val NO_ANSWER_TIMEOUT_MS = 10_000L }

    fun stop() {
        main.removeCallbacks(timeout)
        runCatching { recognizer?.destroy() }; recognizer = null
        runCatching { tts?.shutdown() }; tts = null
        busy = false
    }
}
