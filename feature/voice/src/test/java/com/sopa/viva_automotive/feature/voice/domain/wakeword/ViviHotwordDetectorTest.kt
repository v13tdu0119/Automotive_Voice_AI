package com.sopa.viva_automotive.feature.voice.domain.wakeword

import com.sopa.viva_automotive.feature.voice.data.wakeword.ScriptedHotwordEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViviHotwordDetectorTest {

    private val config = ViviHotword.defaultConfig()
    private val captureSpec = AudioCaptureSpec(frameSamples = FRAME_SAMPLES)
    private val session = mockk<AudioCaptureSession>(relaxUnitFun = true)
    private val captureFactory = mockk<AudioCaptureFactory>()
    private val engine = mockk<HotwordEngine>(relaxUnitFun = true)

    private fun TestScope.newDetector(hotwordEngine: HotwordEngine = engine) = ViviHotwordDetector(
        captureFactory = captureFactory,
        engine = hotwordEngine,
        config = config,
        captureSpec = captureSpec,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun givenReadyEngine() {
        coEvery { engine.initialize(config) } returns Result.success(Unit)
        every { captureFactory.open(captureSpec) } returns Result.success(session)
    }

    @Test
    fun `starts idle and transitions to ListeningForWakeWord`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        val observed = mutableListOf<WakeWordState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.state.toList(observed)
        }

        assertEquals(WakeWordState.Idle, detector.state.value)

        detector.startListening()
        runCurrent()

        assertEquals(WakeWordState.ListeningForWakeWord, detector.state.value)
        assertEquals(
            listOf(WakeWordState.Idle, WakeWordState.ListeningForWakeWord),
            observed,
        )

        detector.stopListening()
        runCurrent()
    }

    @Test
    fun `opens a 16 kHz microphone with echo cancellation and noise suppression`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        detector.startListening()
        runCurrent()

        verify {
            captureFactory.open(
                match { spec ->
                    spec.sampleRateHz == 16_000 &&
                        spec.enableEchoCancellation &&
                        spec.enableNoiseSuppression
                },
            )
        }

        detector.stopListening()
        runCurrent()
    }

    @Test
    fun `detection moves the state machine to Triggered and stops capturing`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns FRAME_SAMPLES
        every { engine.process(any(), FRAME_SAMPLES) } returns HotwordDetection(
            keyword = ViviHotword.KEYWORD,
            confidence = 0.93f,
            timestampMs = DETECTION_TIMESTAMP,
        )
        val detector = newDetector()

        detector.startListening()
        runCurrent()

        assertEquals(
            WakeWordState.Triggered(ViviHotword.KEYWORD, DETECTION_TIMESTAMP, 0.93f),
            detector.state.value,
        )
        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { engine.release() }
    }

    @Test
    fun `vietnamese vi vi pronunciation triggers the assistant`() = runTest {
        every { captureFactory.open(captureSpec) } returns Result.success(session)
        every { session.read(any()) } returnsMany listOf(
            FRAME_SAMPLES,
            AudioCaptureSession.END_OF_STREAM,
        )
        val scripted = ScriptedHotwordEngine { DETECTION_TIMESTAMP }
        scripted.enqueue("hey vi vi")
        val detector = newDetector(scripted)

        detector.startListening()
        runCurrent()

        assertEquals(
            WakeWordState.Triggered(ViviHotword.KEYWORD, DETECTION_TIMESTAMP, 1f),
            detector.state.value,
        )
        assertEquals(1, scripted.releaseCount)
    }

    @Test
    fun `english vy vy pronunciation never triggers the assistant`() = runTest {
        every { captureFactory.open(captureSpec) } returns Result.success(session)
        every { session.read(any()) } returnsMany listOf(
            FRAME_SAMPLES,
            FRAME_SAMPLES,
            FRAME_SAMPLES,
            AudioCaptureSession.END_OF_STREAM,
        )
        val scripted = ScriptedHotwordEngine { DETECTION_TIMESTAMP }
        scripted.enqueue("vai vai", "why why", "five five")
        val detector = newDetector(scripted)

        detector.startListening()
        runCurrent()

        assertEquals(WakeWordState.Idle, detector.state.value)
        assertEquals(1, scripted.releaseCount)
    }

    @Test
    fun `engine initialization failure surfaces an error and never opens the microphone`() =
        runTest {
            coEvery { engine.initialize(config) } returns Result.failure(
                IllegalStateException("Wake word model missing"),
            )
            val detector = newDetector()

            detector.startListening()
            runCurrent()

            assertEquals(
                WakeWordState.Error("Wake word model missing"),
                detector.state.value,
            )
            verify(exactly = 0) { captureFactory.open(any()) }
            verify(exactly = 1) { engine.release() }
        }

    @Test
    fun `microphone failure surfaces an error and releases the engine`() = runTest {
        coEvery { engine.initialize(config) } returns Result.success(Unit)
        every { captureFactory.open(captureSpec) } returns Result.failure(
            IllegalStateException("Microphone is unavailable"),
        )
        val detector = newDetector()

        detector.startListening()
        runCurrent()

        assertEquals(WakeWordState.Error("Microphone is unavailable"), detector.state.value)
        verify(exactly = 1) { engine.release() }
        verify(exactly = 0) { session.read(any()) }
    }

    @Test
    fun `negative read result reports an error`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns READ_ERROR_CODE
        val detector = newDetector()

        detector.startListening()
        runCurrent()

        val state = detector.state.value
        assertTrue("Unexpected state: $state", state is WakeWordState.Error)
        assertTrue((state as WakeWordState.Error).message.contains("$READ_ERROR_CODE"))
        verify(exactly = 1) { session.close() }
    }

    @Test
    fun `stopListening releases the microphone and the engine and returns to Idle`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        detector.startListening()
        runCurrent()
        detector.stopListening()
        runCurrent()

        assertEquals(WakeWordState.Idle, detector.state.value)
        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { engine.release() }
    }

    @Test
    fun `startListening is idempotent while already listening`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        detector.startListening()
        detector.startListening()
        runCurrent()
        detector.startListening()
        runCurrent()

        verify(exactly = 1) { captureFactory.open(captureSpec) }

        detector.stopListening()
        runCurrent()
    }

    @Test
    fun `losing audio focus stops capture and regaining it resumes listening`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        detector.startListening()
        runCurrent()

        detector.onAudioFocusLost()
        runCurrent()

        assertEquals(WakeWordState.AudioFocusLost, detector.state.value)
        verify(exactly = 1) { session.close() }

        detector.onAudioFocusGained()
        runCurrent()

        assertEquals(WakeWordState.ListeningForWakeWord, detector.state.value)
        verify(exactly = 2) { captureFactory.open(captureSpec) }

        detector.stopListening()
        runCurrent()
    }

    @Test
    fun `regaining audio focus without a previous loss does not start capture`() = runTest {
        givenReadyEngine()
        every { session.read(any()) } returns NO_DATA
        val detector = newDetector()

        detector.onAudioFocusGained()
        runCurrent()

        assertEquals(WakeWordState.Idle, detector.state.value)
        verify(exactly = 0) { captureFactory.open(any()) }
    }

    private companion object {
        const val FRAME_SAMPLES = 4
        const val NO_DATA = 0
        const val READ_ERROR_CODE = -3
        const val DETECTION_TIMESTAMP = 1_700_000_000_000L
    }
}
