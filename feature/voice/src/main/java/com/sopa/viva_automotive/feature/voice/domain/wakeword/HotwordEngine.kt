package com.sopa.viva_automotive.feature.voice.domain.wakeword

data class HotwordPhrase(
    val phrase: String,
    val phonemes: List<String>,
) {
    init {
        require(phrase.isNotBlank()) { "Wake phrase must not be blank" }
        require(phonemes.isNotEmpty()) { "Wake phrase '$phrase' needs at least one phoneme" }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun of(phrase: String, phonemes: String): HotwordPhrase = HotwordPhrase(
            phrase = phrase,
            phonemes = phonemes.trim().split(WHITESPACE).filter { it.isNotEmpty() },
        )
    }
}

data class HotwordConfig(
    val accepted: List<HotwordPhrase>,
    val rejected: List<HotwordPhrase> = emptyList(),
    val sensitivity: Float = 0.5f,
) {
    init {
        require(accepted.isNotEmpty()) { "At least one accepted wake phrase is required" }
        require(sensitivity in 0f..1f) { "Sensitivity must be within 0..1, was $sensitivity" }
    }

    val matchThreshold: Float
        get() = STRICTEST_THRESHOLD - (STRICTEST_THRESHOLD - LOOSEST_THRESHOLD) * sensitivity

    private companion object {
        const val STRICTEST_THRESHOLD = 0.95f
        const val LOOSEST_THRESHOLD = 0.6f
    }
}

data class HotwordDetection(
    val keyword: String,
    val confidence: Float,
    val timestampMs: Long,
)

interface HotwordEngine {

    suspend fun initialize(config: HotwordConfig): Result<Unit>

    fun process(pcm: ShortArray, length: Int): HotwordDetection?

    fun reset()

    fun release()
}

object ViviHotword {

    const val KEYWORD = "Vi-vi"

    val VIVI: HotwordPhrase = HotwordPhrase.of(KEYWORD, "v iː v iː")

    val HEY_VIVI: HotwordPhrase = HotwordPhrase.of("Hey Vi-vi", "h eɪ v iː v iː")

    val ENGLISH_VY_VY: HotwordPhrase = HotwordPhrase.of("Vy-vy", "v aɪ v aɪ")

    private val WHY_WHY: HotwordPhrase = HotwordPhrase.of("why why", "w aɪ w aɪ")

    private val WI_FI: HotwordPhrase = HotwordPhrase.of("wifi", "w aɪ f aɪ")

    private val FIVE_FIVE: HotwordPhrase = HotwordPhrase.of("five five", "f aɪ v f aɪ v")

    fun defaultConfig(sensitivity: Float = 0.5f): HotwordConfig = HotwordConfig(
        accepted = listOf(VIVI, HEY_VIVI),
        rejected = listOf(ENGLISH_VY_VY, WHY_WHY, WI_FI, FIVE_FIVE),
        sensitivity = sensitivity,
    )
}
