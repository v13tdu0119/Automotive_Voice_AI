package com.sopa.viva_automotive.feature.voice.domain.wakeword

import com.sopa.viva_automotive.core.common.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Singleton
class ViviHotwordDetector @Inject constructor(
    private val captureFactory: AudioCaptureFactory,
    private val engine: HotwordEngine,
    private val config: HotwordConfig,
    private val captureSpec: AudioCaptureSpec,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Idle)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val detectorScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()

    private var detectorJob: Job? = null
    private var listening = false
    private var pausedByFocusLoss = false
    private var generation = 0

    fun startListening() {
        val previous: Job?
        val startedGeneration: Int
        synchronized(lock) {
            if (listening) return
            listening = true
            pausedByFocusLoss = false
            generation++
            startedGeneration = generation
            previous = detectorJob
        }

        val job = detectorScope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            try {
                runDetectionLoop()
            } finally {
                synchronized(lock) {
                    if (generation == startedGeneration) listening = false
                }
            }
        }
        synchronized(lock) { detectorJob = job }
        job.start()
    }

    fun stopListening() {
        val job = synchronized(lock) {
            if (!listening) return
            listening = false
            pausedByFocusLoss = false
            detectorJob
        }
        job?.cancel()
        _state.value = WakeWordState.Idle
    }

    fun onAudioFocusLost() {
        val job = synchronized(lock) {
            if (!listening) return
            listening = false
            pausedByFocusLoss = true
            detectorJob
        }
        job?.cancel()
        _state.value = WakeWordState.AudioFocusLost
    }

    fun onAudioFocusGained() {
        synchronized(lock) { if (!pausedByFocusLoss) return }
        startListening()
    }

    private suspend fun runDetectionLoop() {
        engine.initialize(config).onFailure { failure ->
            engine.release()
            _state.value = WakeWordState.Error(failure.message ?: ENGINE_UNAVAILABLE)
            return
        }

        val session = captureFactory.open(captureSpec).getOrElse { failure ->
            engine.release()
            _state.value = WakeWordState.Error(failure.message ?: MICROPHONE_UNAVAILABLE)
            return
        }

        engine.reset()
        _state.value = WakeWordState.ListeningForWakeWord

        val detection = try {
            consume(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            _state.value = WakeWordState.Error(failure.message ?: DETECTION_FAILED)
            null
        } finally {
            runCatching { session.close() }
            engine.release()
        }

        if (detection != null) {
            _state.value = WakeWordState.Triggered(
                keyword = detection.keyword,
                timestampMs = detection.timestampMs,
                confidence = detection.confidence,
            )
        }
    }

    private suspend fun consume(session: AudioCaptureSession): HotwordDetection? {
        val buffer = ShortArray(captureSpec.frameSamples)
        while (currentCoroutineContext().isActive) {
            when (val read = session.read(buffer)) {
                AudioCaptureSession.END_OF_STREAM -> {
                    _state.value = WakeWordState.Idle
                    return null
                }

                0 -> delay(EMPTY_READ_BACKOFF_MS)

                else -> {
                    if (read < 0) {
                        _state.value = WakeWordState.Error("$MICROPHONE_READ_FAILED (code $read)")
                        return null
                    }
                    val detection = engine.process(buffer, read)
                    if (detection != null) return detection
                    yield()
                }
            }
        }
        return null
    }

    private companion object {
        const val EMPTY_READ_BACKOFF_MS = 10L
        const val ENGINE_UNAVAILABLE = "Wake word engine is unavailable"
        const val MICROPHONE_UNAVAILABLE = "Microphone is unavailable"
        const val MICROPHONE_READ_FAILED = "Microphone read failed"
        const val DETECTION_FAILED = "Wake word detection failed"
    }
}
