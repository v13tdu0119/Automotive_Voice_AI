package com.sopa.viva_automotive.feature.voice.di

import com.sopa.viva_automotive.feature.voice.data.wakeword.AndroidAudioCaptureFactory
import com.sopa.viva_automotive.feature.voice.data.wakeword.VoskHotwordEngine
import com.sopa.viva_automotive.feature.voice.domain.wakeword.AudioCaptureFactory
import com.sopa.viva_automotive.feature.voice.domain.wakeword.AudioCaptureSpec
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordConfig
import com.sopa.viva_automotive.feature.voice.domain.wakeword.HotwordEngine
import com.sopa.viva_automotive.feature.voice.domain.wakeword.ViviHotword
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WakeWordModule {

    @Binds
    @Singleton
    abstract fun bindAudioCaptureFactory(
        impl: AndroidAudioCaptureFactory,
    ): AudioCaptureFactory

    @Binds
    @Singleton
    abstract fun bindHotwordEngine(impl: VoskHotwordEngine): HotwordEngine
}

@Module
@InstallIn(SingletonComponent::class)
object WakeWordConfigModule {

    @Provides
    @Singleton
    fun provideHotwordConfig(): HotwordConfig = ViviHotword.defaultConfig()

    @Provides
    @Singleton
    fun provideAudioCaptureSpec(): AudioCaptureSpec = AudioCaptureSpec()
}
