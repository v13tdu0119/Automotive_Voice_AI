package com.sopa.viva_automotive.feature.voice.data.vosk

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.sopa.viva_automotive.core.common.coroutines.IoDispatcher
import com.sopa.viva_automotive.feature.voice.data.SpeechRecognitionEngine
import com.sopa.viva_automotive.feature.voice.data.TranscriptionEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer

@Singleton
class VoskSpeechRecognitionEngine @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val modelLoader: VoskModelLoader,
) : SpeechRecognitionEngine {

    @Volatile
    private var endOfUtteranceRequested: Boolean = false

    override fun requestEndOfUtterance() {
        endOfUtteranceRequested = true
    }

    override suspend fun initialize(): Result<Unit> = modelLoader.load().map { }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller (service/UI).
    override fun transcribe(): Flow<TranscriptionEvent> = callbackFlow {
        val loaded = modelLoader.current
        if (loaded == null) {
            trySend(
                TranscriptionEvent.Error(
                    "Voice model is not available. Check offline STT assets for the selected language.",
                ),
            )
            close()
            return@callbackFlow
        }

        endOfUtteranceRequested = false
        val recognizer = Recognizer(loaded.model, SAMPLE_RATE.toFloat())
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, BUFFER_SIZE_SAMPLES * 2),
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            recognizer.close()
            audioRecord.release()
            trySend(TranscriptionEvent.Error("Microphone is unavailable"))
            close()
            return@callbackFlow
        }

        audioRecord.startRecording()
        Log.d(TAG, "Listening with Vosk ${loaded.language.storageKey}")

        val readerJob = launch(ioDispatcher) {
            try {
                readLoop(this@callbackFlow, audioRecord, recognizer)
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
                recognizer.close()
            }
            close()
        }

        awaitClose {
            endOfUtteranceRequested = true
            runCatching { audioRecord.stop() }
            readerJob.cancel()
        }
    }.flowOn(ioDispatcher)

    private suspend fun readLoop(
        channel: SendChannel<TranscriptionEvent>,
        audioRecord: AudioRecord,
        recognizer: Recognizer,
    ) {
        val buffer = ShortArray(BUFFER_SIZE_SAMPLES)
        var lastPartial = ""
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (endOfUtteranceRequested) {
                val forced = JSONObject(recognizer.finalResult).optString("text").trim()
                    .ifEmpty { lastPartial }
                if (forced.isNotEmpty()) {
                    channel.send(TranscriptionEvent.Final(forced))
                } else {
                    channel.send(TranscriptionEvent.Error("I didn't hear anything"))
                }
                return
            }

            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read < 0) {
                channel.send(TranscriptionEvent.Error("Microphone read failed (code $read)"))
                return
            }
            if (read == 0) continue
            if (recognizer.acceptWaveForm(buffer, read)) {
                val text = JSONObject(recognizer.result).optString("text").trim()
                if (text.isNotEmpty()) {
                    channel.send(TranscriptionEvent.Final(text))
                    return
                }
            } else {
                val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    channel.send(TranscriptionEvent.Partial(partial))
                }
            }
        }
    }

    private companion object {
        const val TAG = "VoskEngine"
        const val SAMPLE_RATE = 16_000
        const val BUFFER_SIZE_SAMPLES = 4_096
    }
}
