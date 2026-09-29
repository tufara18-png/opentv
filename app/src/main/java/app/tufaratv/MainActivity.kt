/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
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
import com.johncorser.telly.features.playback.TuneController

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
        handleLaunchIntent(intent)
        setContent {
            OpenTvTellyHost(
                navigator = navigator,
                onEnterPip = pip::enter,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val channelId = intent?.getLongExtra(EXTRA_PLAY_CHANNEL, 0L) ?: 0L
        if (channelId > 0L) {
            TellyServiceLocator.keyValueStore(this).putLong(TuneController.LAST_CHANNEL_KEY, channelId)
            navigator.replaceAll(Route.Playback)
            intent?.removeExtra(EXTRA_PLAY_CHANNEL)
        }

        val recordingId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recordingId > 0L) {
            // Legacy OpenTV recording notifications can still exist after an upgrade.
            // Open the Telly DVR surface rather than dropping the tap silently.
            navigator.replaceAll(Route.Recordings)
            intent?.removeExtra(EXTRA_WATCH_RECORDING)
        }
    }

    /** Compatibility shim for legacy OpenTV UI sources that still compile but are never routed. */
    fun enterPipNow() {
        pip.enter()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    companion object {
        const val EXTRA_PLAY_CHANNEL = "opentv.play_channel"
        const val EXTRA_WATCH_RECORDING = "opentv.watch_recording"
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.onModeChanged(isInPictureInPictureMode)
    }
}


/**
 * Legacy compile-time helper. The OpenTV Compose frontend is no longer mounted.
 */
fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
