/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tufaratv.BuildConfig
import app.tufaratv.core.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
    data class Downloading(val update: UpdateChecker.Update, val fraction: Float) : UpdateUiState
    data class Failed(val update: UpdateChecker.Update) : UpdateUiState
}

/** Shared updater state consumed by the native TV settings/update surfaces. */
object UpdateHub {
    val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
}

/**
 * Update logic deliberately has no UI dependency. The previous Compose dialog was removed with
 * the rest of the Compose frontend; native Android-TV views observe this state when exposing the
 * updater.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val checker = UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME)
    private val installer = ApkInstaller(graph.httpClient)

    private val _state = UpdateHub.state
    val state = _state.asStateFlow()

    init { checkThrottled() }

    private fun checkThrottled() {
        viewModelScope.launch {
            val prefs = getApplication<Application>()
                .getSharedPreferences("opentv", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@launch

            val update = checker.check()
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (update != null) _state.value = UpdateUiState.Available(update)
        }
    }

    fun checkNow() {
        viewModelScope.launch {
            val update = checker.check()
            _state.value = update?.let(UpdateUiState::Available) ?: UpdateUiState.Idle
        }
    }

    fun install() {
        val update = when (val s = _state.value) {
            is UpdateUiState.Available -> s.update
            is UpdateUiState.Failed -> s.update
            is UpdateUiState.Downloading -> s.update
            UpdateUiState.Idle -> return
        }
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(update, 0f)
            runCatching {
                installer.downloadAndInstall(
                    context = getApplication(),
                    url = update.apkUrl,
                    expectedBytes = update.apkSizeBytes,
                ) { fraction -> _state.value = UpdateUiState.Downloading(update, fraction) }
            }.onSuccess {
                _state.value = UpdateUiState.Idle
            }.onFailure {
                _state.value = UpdateUiState.Failed(update)
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
