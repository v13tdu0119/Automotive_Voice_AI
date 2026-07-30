package com.sopa.viva_automotive.feature.voice.data.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.sopa.viva_automotive.feature.voice.domain.wakeword.AudioCaptureFactory
import com.sopa.viva_automotive.feature.voice.domain.wakeword.AudioCaptureSession
import com.sopa.viva_automotive.feature.voice.domain.wakeword.AudioCaptureSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAudioCaptureFactory @Inject constructor() : AudioCaptureFactory {

    @SuppressLint("MissingPermission")
    override fun open(spec: AudioCaptureSpec): Result<AudioCaptureSession> = runCatching {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            spec.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) {
            "Microphone does not support ${spec.sampleRateHz} Hz mono PCM 16-bit"
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            spec.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes * 2, spec.frameSamples * BYTES_PER_SAMPLE * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Microphone is unavailable")
        }

        val echoCanceler = attachEchoCanceler(record, spec)
        val noiseSuppressor = attachNoiseSuppressor(record, spec)

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            echoCanceler?.release()
            noiseSuppressor?.release()
            record.release()
            error("Microphone is busy")
        }

        Log.i(
            TAG,
            "Wake word capture started: aec=${echoCanceler?.enabled == true}, " +
                "ns=${noiseSuppressor?.enabled == true}",
        )
        AndroidAudioCaptureSession(record, echoCanceler, noiseSuppressor)
    }

    private fun attachEchoCanceler(
        record: AudioRecord,
        spec: AudioCaptureSpec,
    ): AcousticEchoCanceler? {
        if (!spec.enableEchoCancellation || !AcousticEchoCanceler.isAvailable()) return null
        return runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { setEnabled(true) }
        }.onFailure { Log.w(TAG, "Acoustic echo canceler unavailable", it) }.getOrNull()
    }

    private fun attachNoiseSuppressor(
        record: AudioRecord,
        spec: AudioCaptureSpec,
    ): NoiseSuppressor? {
        if (!spec.enableNoiseSuppression || !NoiseSuppressor.isAvailable()) return null
        return runCatching {
            NoiseSuppressor.create(record.audioSessionId)?.apply { setEnabled(true) }
        }.onFailure { Log.w(TAG, "Noise suppressor unavailable", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "ViviCapture"
        const val BYTES_PER_SAMPLE = 2
    }
}

private class AndroidAudioCaptureSession(
    private val record: AudioRecord,
    private val echoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?,
) : AudioCaptureSession {

    override val echoCancellationEnabled: Boolean = echoCanceler?.enabled == true

    override val noiseSuppressionEnabled: Boolean = noiseSuppressor?.enabled == true

    override fun read(destination: ShortArray): Int {
        val read = record.read(destination, 0, destination.size)
        return if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            AudioCaptureSession.END_OF_STREAM
        } else {
            read
        }
    }

    override fun close() {
        runCatching { record.stop() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        record.release()
    }
}
