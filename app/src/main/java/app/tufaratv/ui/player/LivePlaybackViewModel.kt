/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.shownName
import app.tufaratv.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped live playback session shared by the guide preview and full-screen player.
 *
 * Keeping one ExoPlayer lets Media3 move the same decoder from one PlayerView surface to the
 * other. The stream, buffer and playback position survive the navigation instead of opening a
 * second provider connection and starting again.
 */
class LivePlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = ServiceLocator.get(application)

    private val controllerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        PlayerController(
            context = application,
            scope = viewModelScope,
            httpClient = graph.streamingHttpClient,
            subtitlesEnabled = graph.settings.subtitlesEnabled.value,
            dvr = graph.settings.livePauseEnabled.value,
            sharedLive = true,
        )
    }
    val controller by controllerDelegate

    private val _currentChannelId = MutableStateFlow<Long?>(null)
    val currentChannelId = _currentChannelId.asStateFlow()

    private val _fullScreen = MutableStateFlow(false)
    val fullScreen = _fullScreen.asStateFlow()

    private var tuneJob: Job? = null
    private var keepFullScreenAudioInGuide = false

    fun play(channel: Channel, debounce: Boolean) {
        if (_currentChannelId.value == channel.id) return
        keepFullScreenAudioInGuide = false
        _currentChannelId.value = channel.id
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            val source = graph.sourceRepository.byId(channel.sourceId)
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            controller.play(
                PlayerController.Request(
                    url = url,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "TufaraTV/0.1 (Android)",
                    isLive = true,
                ),
                debounce = debounce,
            )
        }
    }

    fun enterGuide(soundEnabled: Boolean) {
        _fullScreen.value = false
        controller.player.volume = if (keepFullScreenAudioInGuide || soundEnabled) 1f else 0f
    }

    /**
     * Moves the current live session back to the guide without changing its audio state. The
     * player, decoder, provider connection and volume all remain continuous; choosing another
     * channel later restores the user's normal preview-sound preference.
     */
    fun returnToGuide() {
        _fullScreen.value = false
        keepFullScreenAudioInGuide = true
        controller.player.volume = 1f
    }

    fun enterFullScreen() {
        _fullScreen.value = true
        keepFullScreenAudioInGuide = false
        controller.player.volume = 1f
    }

    fun stop() {
        tuneJob?.cancel()
        tuneJob = null
        _currentChannelId.value = null
        _fullScreen.value = false
        keepFullScreenAudioInGuide = false
        if (controllerDelegate.isInitialized()) controller.stop()
    }

    override fun onCleared() {
        if (controllerDelegate.isInitialized()) controller.release()
        super.onCleared()
    }
}
