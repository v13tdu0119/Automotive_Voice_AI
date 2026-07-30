package com.sopa.viva_automotive.feature.voice.data.vosk

import android.content.Context
import android.util.Log
import com.sopa.viva_automotive.core.common.coroutines.IoDispatcher
import com.sopa.viva_automotive.core.database.settings.SettingsDataStore
import com.sopa.viva_automotive.core.ui.locale.VoiceLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model

data class LoadedVoskModel(
    val model: Model,
    val language: VoiceLanguage,
)

@Singleton
class VoskModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsDataStore: SettingsDataStore,
) {

    private val mutex = Mutex()

    @Volatile
    private var loaded: LoadedVoskModel? = null

    val current: LoadedVoskModel?
        get() = loaded

    suspend fun load(): Result<LoadedVoskModel> = mutex.withLock {
        val language = VoiceLanguage.fromStorageKey(
            settingsDataStore.settings.first().voiceLanguage,
        )
        loaded?.takeIf { it.language == language }?.let { return Result.success(it) }

        withContext(ioDispatcher) {
            runCatching {
                releaseLocked()
                val assetDir = language.voskAssetDir
                val modelDir = File(context.filesDir, assetDir)
                if (!modelDir.resolve(MODEL_READY_MARKER).exists()) {
                    copyAssetDir(assetDir, modelDir)
                    modelDir.resolve(MODEL_READY_MARKER).createNewFile()
                }
                require(modelDir.resolve("conf/model.conf").exists()) {
                    "Vosk model missing conf/model.conf under assets/$assetDir"
                }
                LoadedVoskModel(Model(modelDir.absolutePath), language).also {
                    loaded = it
                    Log.i(TAG, "Loaded Vosk model for ${language.storageKey} ($assetDir)")
                }
            }.onFailure {
                Log.e(TAG, "Voice model initialization failed for $language", it)
                releaseLocked()
            }
        }
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        runCatching { loaded?.model?.close() }
        loaded = null
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            if (VoiceLanguage.entries.any { it.voskAssetDir == assetPath }) {
                error("Vosk model not found in assets/$assetPath")
            }
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            target.mkdirs()
            children.forEach { child ->
                copyAssetDir("$assetPath/$child", File(target, child))
            }
        }
    }

    private companion object {
        const val TAG = "VoskModelLoader"
        const val MODEL_READY_MARKER = ".unpacked"
    }
}
