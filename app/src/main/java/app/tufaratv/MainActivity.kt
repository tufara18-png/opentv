/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.tufaratv.telly.OpenTvTellyHost
import com.johncorser.telly.core.ServiceLocator as TellyServiceLocator
import com.johncorser.telly.core.navigation.Navigator
import com.johncorser.telly.core.navigation.Route
import com.johncorser.telly.core.settings.TellySettings
import com.johncorser.telly.core.settings.withAppLocale
import com.johncorser.telly.features.pip.PipActivityBridge
import com.johncorser.telly.features.pip.PipState

/**
 * Single Telly UI entry point.
 *
 * OpenTV remains the backend for sources, catalogue, EPG and stream resolution.
 * No OpenTV Compose surface is mounted from this activity.
 */
class MainActivity : ComponentActivity() {
    private val navigator = Navigator(start = Route.Boot)
    private val pip = PipActivityBridge(this, PipState.shared, ::pipOnHome, ::playbackIsFullscreen)

    private fun pipOnHome(): Boolean =
        TellyServiceLocator.settingsRepository(this).get(TellySettings.PIP_ON_HOME)

    private fun playbackIsFullscreen(): Boolean =
        navigator.stack.value.lastOrNull() == Route.Playback

    override fun attachBaseContext(newBase: Context) {
        val language =
            TellyServiceLocator.settingsRepository(newBase).get(TellySettings.LANGUAGE)
        super.attachBaseContext(newBase.withAppLocale(language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenTvTellyHost(
                navigator = navigator,
                onEnterPip = pip::enter,
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.onModeChanged(isInPictureInPictureMode)
    }
}
