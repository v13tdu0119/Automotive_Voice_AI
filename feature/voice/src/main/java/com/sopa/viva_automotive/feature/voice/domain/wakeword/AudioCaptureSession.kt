package com.sopa.viva_automotive.feature.voice.domain.wakeword

data class AudioCaptureSpec(
    val sampleRateHz: Int = 16_000,
    val frameSamples: Int = 1_024,
    val enableEchoCancellation: Boolean = true,
    val enableNoiseSuppression: Boolean = true,
)

interface AudioCaptureSession : AutoCloseable {

    val echoCancellationEnabled: Boolean

    val noiseSuppressionEnabled: Boolean

    fun read(destination: ShortArray): Int

    override fun close()

    companion object {
        const val END_OF_STREAM: Int = Int.MIN_VALUE
    }
}

fun interface AudioCaptureFactory {
    fun open(spec: AudioCaptureSpec): Result<AudioCaptureSession>
}
