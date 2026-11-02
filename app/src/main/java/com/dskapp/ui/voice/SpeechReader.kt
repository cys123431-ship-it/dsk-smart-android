package com.dskapp.ui.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Reads assistant answers aloud with the phone's built-in TextToSpeech (no cost, offline voices).
 *
 * The engine is created lazily on the first [speak] because initialization is slow and only
 * users who actually tap the button should pay for it.
 */
class SpeechReader(context: Context, private val onError: (String) -> Unit) {

    private val appContext = context.applicationContext
    private val _speakingId = MutableStateFlow<Long?>(null)
    val speakingId: StateFlow<Long?> = _speakingId

    private var tts: TextToSpeech? = null
    private var released = false

    /** A request that arrived before the engine finished initializing. */
    private var pending: Pair<Long, List<String>>? = null

    /** Toggles read-aloud: tapping the answer that is already playing stops it. */
    fun speak(id: Long, markdown: String) {
        if (released) return
        if (_speakingId.value == id) {
            stop()
            return
        }
        val chunks = SpeechText.chunks(markdown)
        if (chunks.isEmpty()) return
        _speakingId.value = id
        val engine = tts
        if (engine == null) {
            pending = id to chunks
            createEngine()
        } else {
            playChunks(engine, id, chunks)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        _speakingId.value = null
    }

    /** Stops playback and shuts the engine down; safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun createEngine() {
        val engine = TextToSpeech(appContext) { status ->
            onEngineInit(status)
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finishIfLast(utteranceId)
            }

            @Deprecated("Required override of the abstract base class")
            override fun onError(utteranceId: String?) {
                finishIfLast(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishIfLast(utteranceId)
            }
        })
    }

    private fun onEngineInit(status: Int) {
        if (released) return
        if (status != TextToSpeech.SUCCESS) {
            pending = null
            _speakingId.value = null
            onError("음성 읽기를 사용할 수 없습니다.")
            return
        }
        val engine = tts ?: return
        runCatching {
            val result = engine.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                pending = null
                _speakingId.value = null
                onError("한국어 음성 데이터가 없습니다. 휴대폰 설정 > 텍스트 음성 변환에서 설치해 주세요.")
                return
            }
        }
        val request = pending
        pending = null
        if (request != null) {
            playChunks(engine, request.first, request.second)
        }
    }

    private fun playChunks(engine: TextToSpeech, id: Long, chunks: List<String>) {
        runCatching { engine.stop() }
        lastIndex = chunks.lastIndex
        for ((index, chunk) in chunks.withIndex()) {
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "$id:$index"
            engine.speak(chunk, mode, null, utteranceId)
        }
    }

    /** Clears [speakingId] only when the finished utterance was the newest one for the active id. */
    private fun finishIfLast(utteranceId: String?) {
        val current = _speakingId.value ?: return
        if (utteranceId == null) return
        val prefix = "$current:"
        if (!utteranceId.startsWith(prefix)) return
        val index = utteranceId.removePrefix(prefix).toIntOrNull() ?: return
        if (index >= lastIndex) {
            _speakingId.value = null
        }
    }

    /** Index of the final chunk of the answer being read; progress callbacks run on a binder thread. */
    @Volatile
    private var lastIndex: Int = 0
}
