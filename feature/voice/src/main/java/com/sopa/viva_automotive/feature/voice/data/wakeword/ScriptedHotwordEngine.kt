package com.sopa.viva_automotive.feature.voice.data.wakeword

import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordConfig
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordDetection
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordEngine
import com.sopa.viva_automotive.feature.voice.domain.wakeword.PhoneticMatcher

class ScriptedHotwordEngine(
    private val now: () -> Long = System::currentTimeMillis,
) : HotwordEngine {

    private val hypotheses = ArrayDeque<String>()

    var initializeResult: Result<Unit> = Result.success(Unit)

    var initializeCount: Int = 0
        private set

    var resetCount: Int = 0
        private set

    var releaseCount: Int = 0
        private set

    var processedFrames: Int = 0
        private set

    @Volatile
    private var activeConfig: HotwordConfig? = null

    fun enqueue(vararg transcripts: String) {
        hypotheses.addAll(transcripts)
    }

    override suspend fun initialize(config: HotwordConfig): Result<Unit> {
        initializeCount++
        return initializeResult.onSuccess { activeConfig = config }
    }

    override fun process(pcm: ShortArray, length: Int): HotwordDetection? {
        processedFrames++
        val config = activeConfig ?: return null
        val hypothesis = hypotheses.removeFirstOrNull() ?: return null
        val match = PhoneticMatcher.match(hypothesis, config) ?: return null
        return HotwordDetection(
            keyword = match.phrase.phrase,
            confidence = match.confidence,
            timestampMs = now(),
        )
    }

    override fun reset() {
        resetCount++
    }

    override fun release() {
        releaseCount++
        activeConfig = null
    }
}
