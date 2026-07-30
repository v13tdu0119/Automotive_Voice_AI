package com.sopa.viva_automotive.feature.voice.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sopa.viva_automotive.core.database.settings.SettingsDataStore
import com.sopa.viva_automotive.feature.voice.data.SpeechRecognitionEngine
import com.sopa.viva_automotive.feature.voice.data.TranscriptionEvent
import com.sopa.viva_automotive.feature.voice.domain.ExecuteVehicleControlUseCase
import com.sopa.viva_automotive.feature.voice.domain.ProcessVoiceCommandUseCase
import com.sopa.viva_automotive.feature.voice.domain.VoiceAssistantStateManager
import com.sopa.viva_automotive.feature.voice.domain.embedding.SemanticIntentMatcher
import com.sopa.viva_automotive.feature.voice.domain.model.VehicleIntent
import com.sopa.viva_automotive.feature.voice.domain.wakeword.ViviHotwordDetector
import com.sopa.viva_automotive.feature.voice.domain.wakeword.WakeWordState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class VoiceAssistantService : LifecycleService() {

    @Inject lateinit var speechEngine: SpeechRecognitionEngine
    @Inject lateinit var stateManager: VoiceAssistantStateManager
    @Inject lateinit var processVoiceCommand: ProcessVoiceCommandUseCase
    @Inject lateinit var executeVehicleControl: ExecuteVehicleControlUseCase
    @Inject lateinit var semanticIntentMatcher: SemanticIntentMatcher
    @Inject lateinit var hotwordDetector: ViviHotwordDetector
    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val interactionMutex = Mutex()

    private var pipelineJob: Job? = null
    private var warmUpJob: Job? = null
    private var wakeWordJob: Job? = null
    private var settingsJob: Job? = null
    private var wakeWordMode = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (wakeWordMode && pipelineJob?.isActive != true) {
                    hotwordDetector.startListening()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                Log.d(TAG, "Audio focus lost temporarily")
                hotwordDetector.onAudioFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus lost permanently, stopping wake word detection")
                stopWakeWordMode()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                startForegroundWithNotification()
                warmUpEmbeddings()
                hotwordDetector.stopListening()
                startPipeline()
            }
            ACTION_START_WAKE_WORD -> startWakeWordMode()
            ACTION_STOP -> {
                pipelineJob?.cancel()
                stateManager.transitionToIdle()
                if (wakeWordMode) {
                    hotwordDetector.startListening()
                } else {
                    hotwordDetector.stopListening()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hotwordDetector.stopListening()
        abandonAudioFocus()
        super.onDestroy()
    }

    private fun startWakeWordMode() {
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted, wake word detection unavailable")
            if (pipelineJob?.isActive != true) stopSelf()
            return
        }
        startForegroundWithNotification()
        warmUpEmbeddings()
        if (wakeWordMode) return
        wakeWordMode = true

        when (requestAudioFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> startWakeWordPipeline()
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->
                Log.i(TAG, "Audio focus delayed, wake word will start on focus gain")
            else -> {
                Log.w(TAG, "Audio focus denied, wake word detection unavailable")
                stopWakeWordMode()
                return
            }
        }
        observeSettings()
    }

    private fun stopWakeWordMode() {
        if (!wakeWordMode) return
        wakeWordMode = false
        settingsJob?.cancel()
        wakeWordJob?.cancel()
        hotwordDetector.stopListening()
        abandonAudioFocus()
        Log.i(TAG, "Wake word detection stopped")
        if (pipelineJob?.isActive != true) stopSelf()
    }

    private fun startWakeWordPipeline() {
        if (wakeWordJob?.isActive == true) {
            hotwordDetector.startListening()
            return
        }
        wakeWordJob = lifecycleScope.launch {
            hotwordDetector.state.collect { state ->
                when (state) {
                    is WakeWordState.Triggered -> {
                        Log.i(
                            TAG,
                            "Wake word \"${state.keyword}\" detected " +
                                "(confidence=${state.confidence})",
                        )
                        runCommandInteraction()
                        if (wakeWordMode) hotwordDetector.startListening()
                    }
                    is WakeWordState.Error -> {
                        Log.w(TAG, "Wake word error: ${state.message}")
                        stateManager.transitionToError(state.message)
                    }
                    else -> Unit
                }
            }
        }
        hotwordDetector.startListening()
    }

    private suspend fun runCommandInteraction() {
        val interaction = lifecycleScope.launch {
            interactionMutex.withLock { runInteraction() }
        }
        pipelineJob = interaction
        interaction.join()
    }

    private fun observeSettings() {
        if (settingsJob?.isActive == true) return
        settingsJob = lifecycleScope.launch {
            settingsDataStore.settings.collect { settings ->
                if (!settings.voiceEnabled || !settings.handsFreeEnabled) {
                    stopWakeWordMode()
                }
            }
        }
    }

    private fun requestAudioFocus(): Int {
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .build()
        audioFocusRequest = request
        return manager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        runCatching {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startPipeline() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = lifecycleScope.launch {
            try {
                interactionMutex.withLock { runInteraction() }
            } finally {
                if (wakeWordMode) {
                    hotwordDetector.startListening()
                } else {
                    stopSelf()
                }
            }
        }
    }

    private fun warmUpEmbeddings() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = lifecycleScope.launch {
            runCatching { semanticIntentMatcher.warmUp() }
        }
    }

    private suspend fun runInteraction() {
        speechEngine.initialize().onFailure { error ->
            stateManager.transitionToError(
                error.message ?: "Voice recognition is unavailable",
            )
            delay(RESULT_DISPLAY_MS)
            stateManager.transitionToIdle()
            return
        }

        stateManager.transitionToListening()

        var finalText: String? = null
        var engineError: String? = null
        withTimeoutOrNull(LISTENING_TIMEOUT_MS) {
            speechEngine.transcribe().collect { event ->
                when (event) {
                    is TranscriptionEvent.Partial ->
                        stateManager.updatePartialTranscription(event.text)
                    is TranscriptionEvent.Final -> finalText = event.text
                    is TranscriptionEvent.Error -> engineError = event.message
                }
            }
        }

        val utterance = finalText
        when {
            engineError != null -> stateManager.transitionToError(engineError)
            utterance == null -> stateManager.transitionToError("I didn't hear anything")
            else -> {
                stateManager.transitionToProcessing(utterance)
                val intent = processVoiceCommand(utterance)
                stateManager.transitionToExecuting(describe(intent))
                executeVehicleControl(intent).fold(
                    onSuccess = { message -> stateManager.transitionToSuccess(message) },
                    onFailure = { error ->
                        stateManager.transitionToError(error.message ?: "Command failed")
                    },
                )
            }
        }

        delay(RESULT_DISPLAY_MS)
        stateManager.transitionToIdle()
    }

    private fun describe(intent: VehicleIntent): String = when (intent) {
        is VehicleIntent.SetTemperature -> "Setting temperature"
        is VehicleIntent.AdjustTemperature -> "Adjusting temperature"
        is VehicleIntent.SetFanSpeed, is VehicleIntent.AdjustFanSpeed -> "Adjusting fan"
        is VehicleIntent.SetAc -> "Switching air conditioning"
        is VehicleIntent.SetHvacPower -> "Switching climate system"
        is VehicleIntent.SetDoorLock -> "Updating door locks"
        is VehicleIntent.QueryStatus -> "Checking vehicle status"
        is VehicleIntent.Unknown -> "Interpreting command"
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Voice assistant",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Viva voice assistant")
            .setContentText("Listening for a command")
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val ACTION_START_LISTENING = "com.sopa.viva_automotive.action.START_LISTENING"
        private const val ACTION_START_WAKE_WORD = "com.sopa.viva_automotive.action.START_WAKE_WORD"
        private const val ACTION_STOP = "com.sopa.viva_automotive.action.STOP"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIFICATION_ID = 0x5641
        private const val LISTENING_TIMEOUT_MS = 15_000L
        private const val RESULT_DISPLAY_MS = 3_000L

        fun startListening(context: Context) {
            context.startForegroundService(
                Intent(context, VoiceAssistantService::class.java).setAction(ACTION_START_LISTENING),
            )
        }

        fun startWakeWordDetection(context: Context) {
            context.startForegroundService(
                Intent(context, VoiceAssistantService::class.java).setAction(ACTION_START_WAKE_WORD),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
