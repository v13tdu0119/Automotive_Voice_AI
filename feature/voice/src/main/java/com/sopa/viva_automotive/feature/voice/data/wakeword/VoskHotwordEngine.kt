package com.sopa.viva_automotive.feature.voice.data.wakeword

import android.util.Log
import com.sopa.viva_automotive.feature.voice.data.vosk.VoskModelLoader
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordConfig
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordDetection
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordEngine
import com.sopa.viva_automotive.feature.voice.domain.wakeword.PhoneticMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

@Singleton
class VoskHotwordEngine @Inject constructor(
    private val modelLoader: VoskModelLoader,
) : HotwordEngine {

    private data class FinalHypothesis(
        val text: String,
        val minWordConfidence: Float,
    )

    @Volatile
    private var recognizer: Recognizer? = null

    @Volatile
    private var activeConfig: HotwordConfig? = null

    private var speechFrames: Int = 0

    override suspend fun initialize(config: HotwordConfig): Result<Unit> =
        modelLoader.load().mapCatching { loaded ->
            releaseRecognizer()
            recognizer = createRecognizer(loaded.model).apply { setWords(true) }
            activeConfig = config
            speechFrames = 0
        }

    override fun process(pcm: ShortArray, length: Int): HotwordDetection? {
        val activeRecognizer = recognizer ?: return null
        val config = activeConfig ?: return null

        if (rmsOf(pcm, length) >= SPEECH_RMS_THRESHOLD) speechFrames++

        if (!activeRecognizer.acceptWaveForm(pcm, length)) return null

        val utteranceSpeechFrames = speechFrames
        speechFrames = 0
        val hypothesis = parseFinal(activeRecognizer.result) ?: return null
        Log.d(
            TAG,
            "Final hypothesis \"${hypothesis.text}\" " +
                "conf=${hypothesis.minWordConfidence} speechFrames=$utteranceSpeechFrames",
        )

        if (utteranceSpeechFrames < MIN_SPEECH_FRAMES) return null
        if (hypothesis.minWordConfidence < minWordConfidence(config)) return null

        val match = PhoneticMatcher.match(hypothesis.text, config) ?: return null
        reset()
        return HotwordDetection(
            keyword = match.phrase.phrase,
            confidence = (match.confidence * hypothesis.minWordConfidence).coerceIn(0f, 1f),
            timestampMs = System.currentTimeMillis(),
        )
    }

    override fun reset() {
        speechFrames = 0
        runCatching { recognizer?.reset() }
    }

    override fun release() {
        releaseRecognizer()
        activeConfig = null
        speechFrames = 0
    }

    private fun createRecognizer(model: Model): Recognizer =
        runCatching { Recognizer(model, SAMPLE_RATE, GRAMMAR) }
            .onFailure {
                Log.w(TAG, "Restricted wake word grammar rejected, using full vocabulary", it)
            }
            .getOrElse { Recognizer(model, SAMPLE_RATE) }

    private fun releaseRecognizer() {
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun parseFinal(json: String): FinalHypothesis? = runCatching {
        val result = JSONObject(json)
        val text = result.optString(RESULT_KEY).trim()
        if (text.isEmpty() || text == UNKNOWN_TOKEN) return@runCatching null
        var minConfidence = 1f
        val words = result.optJSONArray(WORDS_KEY)
        if (words != null) {
            for (index in 0 until words.length()) {
                val confidence = words.getJSONObject(index).optDouble("conf", 1.0).toFloat()
                minConfidence = minOf(minConfidence, confidence)
            }
        }
        FinalHypothesis(text, minConfidence)
    }.getOrNull()

    private fun rmsOf(pcm: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (index in 0 until length) {
            val sample = pcm[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    private fun minWordConfidence(config: HotwordConfig): Float =
        STRICTEST_WORD_CONFIDENCE -
            (STRICTEST_WORD_CONFIDENCE - LOOSEST_WORD_CONFIDENCE) * config.sensitivity

    private companion object {
        const val TAG = "ViviHotwordEngine"
        const val SAMPLE_RATE = 16_000f
        const val RESULT_KEY = "text"
        const val WORDS_KEY = "result"
        const val UNKNOWN_TOKEN = "[unk]"
        const val GRAMMAR = """["hey vi vi", "vi vi", "[unk]"]"""
        const val SPEECH_RMS_THRESHOLD = 450f
        const val MIN_SPEECH_FRAMES = 4
        const val STRICTEST_WORD_CONFIDENCE = 0.98f
        const val LOOSEST_WORD_CONFIDENCE = 0.75f
    }
}
