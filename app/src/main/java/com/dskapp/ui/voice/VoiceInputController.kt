package com.dskapp.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over [SpeechRecognizer].
 *
 * The platform recognizer is used instead of a third-party SDK so no audio ever leaves via
 * an extra vendor, the app stays small, and it works on every device that has Google or
 * Samsung speech services installed.
 *
 * Must be created and released on the main thread; [parsePartial] controls whether interim
 * results are surfaced (used to update the text field live while speaking).
 */
class VoiceInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStateChanged: (Boolean) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null

    /** True when the device has a speech recognition service at all. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(locale: String = DEFAULT_LOCALE) {
        if (!isAvailable()) {
            onError("이 기기에서 음성 인식을 사용할 수 없습니다.")
            return
        }

        release()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(createIntent(locale))
        }
        onStateChanged(true)
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.let { instance ->
            runCatching {
                instance.cancel()
                instance.destroy()
            }
        }
        recognizer = null
        onStateChanged(false)
    }

    private fun createIntent(locale: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            onStateChanged(false)
            if (text.isNullOrBlank()) {
                onError("음성을 인식하지 못했습니다. 다시 시도해 주세요.")
            } else {
                onFinal(text)
            }
        }

        override fun onError(error: Int) {
            onStateChanged(false)
            onError(describeError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류가 발생했습니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "네트워크 오류로 음성 인식을 사용할 수 없습니다."
        SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다. 다시 말씀해 주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 사용 중입니다. 잠시 후 다시 시도해 주세요."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았습니다."
        else -> "음성 인식 오류 (코드 $code)"
    }

    private companion object {
        /** Korean dictation by default; the recognizer accepts a BCP-47 tag. */
        const val DEFAULT_LOCALE = "ko-KR"
    }
}
