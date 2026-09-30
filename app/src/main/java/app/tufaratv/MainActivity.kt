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
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.tufaratv.core.ServiceLocator
import app.tufaratv.telly.OpenTvTellyHost
import app.tufaratv.ui.nativev4.NativeTvRootView
import app.tufaratv.ui.nativev4.TellyFeatureActivity
import com.johncorser.telly.core.ServiceLocator as TellyServiceLocator
import com.johncorser.telly.core.navigation.Navigator
import com.johncorser.telly.core.navigation.Route
import com.johncorser.telly.core.settings.TellySettings
import com.johncorser.telly.core.settings.withAppLocale
import com.johncorser.telly.features.pip.PipActivityBridge
import com.johncorser.telly.features.pip.PipState
import com.johncorser.telly.features.playback.TuneController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigator = Navigator(start = Route.Boot)
    private val pip = PipActivityBridge(this, PipState.shared, ::pipOnHome, ::playbackIsFullscreen)
    private var nativeRoot: NativeTvRootView? = null
    private var pendingChannelId: Long? = null

    private fun pipOnHome(): Boolean =
        TellyServiceLocator.settingsRepository(this).get(TellySettings.PIP_ON_HOME)

    private fun playbackIsFullscreen(): Boolean =
        nativeRoot?.isFullscreen ?: (navigator.stack.value.lastOrNull() == Route.Playback)

    override fun attachBaseContext(newBase: Context) {
        val language =
            TellyServiceLocator.settingsRepository(newBase).get(TellySettings.LANGUAGE)
        super.attachBaseContext(newBase.withAppLocale(language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        lifecycleScope.launch {
            val ready = catalogueReady()
            if (ready) {
                mountNativeTv()
            } else {
                mountOnboarding()
                watchForFirstSource()
            }
        }
    }

    private fun mountNativeTv() {
        val root = NativeTvRootView(
            context = this,
            scope = lifecycleScope,
            onEnterPip = pip::enter,
        )
        nativeRoot?.release()
        nativeRoot = root
        setContentView(root)
        pendingChannelId?.let {
            root.playChannel(it)
            pendingChannelId = null
        }
    }

    private fun mountOnboarding() {
        nativeRoot = null
        setContent {
            OpenTvTellyHost(
                navigator = navigator,
                onEnterPip = pip::enter,
            )
        }
    }

    private suspend fun catalogueReady(): Boolean {
        val graph = ServiceLocator.get(this)
        val sources = graph.sourceRepository.enabled()
        return sources.any { it.lastCatalogSyncMillis > 0L } &&
            graph.database.channels().totalVisibleCount() > 0
    }

    private suspend fun watchForFirstSource() {
        while (nativeRoot == null) {
            if (catalogueReady()) {
                mountNativeTv()
                return
            }
            delay(500)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (nativeRoot?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
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
            pendingChannelId = channelId
            nativeRoot?.playChannel(channelId)
            intent?.removeExtra(EXTRA_PLAY_CHANNEL)
        }

        val recordingId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recordingId > 0L) {
            startActivity(
                Intent(this, TellyFeatureActivity::class.java)
                    .putExtra(TellyFeatureActivity.EXTRA_ROUTE, "recordings"),
            )
            intent?.removeExtra(EXTRA_WATCH_RECORDING)
        }
    }

    fun enterPipNow() {
        pip.enter()
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

    override fun onDestroy() {
        nativeRoot?.release()
        nativeRoot = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLAY_CHANNEL = "opentv.play_channel"
        const val EXTRA_WATCH_RECORDING = "opentv.watch_recording"
    }
}

fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
