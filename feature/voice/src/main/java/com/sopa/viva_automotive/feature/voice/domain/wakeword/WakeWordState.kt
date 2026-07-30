package com.sopa.viva_automotive.feature.voice.domain.wakeword

sealed interface WakeWordState {

    data object Idle : WakeWordState

    data object ListeningForWakeWord : WakeWordState

    data class Triggered(
        val keyword: String,
        val timestampMs: Long,
        val confidence: Float = 1f,
    ) : WakeWordState

    data object AudioFocusLost : WakeWordState

    data class Error(val message: String) : WakeWordState

    val isCapturingAudio: Boolean
        get() = this is ListeningForWakeWord
}
