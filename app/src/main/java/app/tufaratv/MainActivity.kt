/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.tufaratv.core.AppSettings
import app.tufaratv.core.ServiceLocator
import app.tufaratv.ui.nativev4.NativeSourceSetupView
import app.tufaratv.ui.nativev4.NativeTvRootView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var nativeRoot: NativeTvRootView? = null
    private var pendingChannelId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        val settings = AppSettings.get(this)
        if (settings.lastLivePlayback() != null) {
            // Fast path only when we have an actual cached playable channel. The native root will
            // immediately show a visible loading surface and refresh the URL if the cache is stale.
            mountNativeTv()
        } else {
            // Do not trust libraryPrepared by itself: upgrades historically defaulted that flag to
            // true, even when no usable source/catalogue existed. Verify Room before mounting a
            // fullscreen player that would otherwise be indistinguishable from a black screen.
            lifecycleScope.launch {
                if (hasExistingCatalogue()) mountNativeTv() else mountNativeSetup()
            }
        }
    }

    private fun mountNativeTv() {
        val root = NativeTvRootView(
            context = this,
            scope = lifecycleScope,
            onEnterPip = ::enterPipNow,
        )
        nativeRoot?.release()
        nativeRoot = root
        setContentView(root)
        pendingChannelId?.let {
            root.playChannel(it)
            pendingChannelId = null
        }
    }

    private fun mountNativeSetup() {
        nativeRoot = null
        setContentView(
            NativeSourceSetupView(
                context = this,
                scope = lifecycleScope,
                onReady = { mountNativeTv() },
            ),
        )
    }

    private suspend fun hasExistingCatalogue(): Boolean {
        val graph = ServiceLocator.get(this)
        return graph.sourceRepository.enabled().isNotEmpty() &&
            graph.database.channels().totalVisibleCount() > 0
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
            pendingChannelId = channelId
            nativeRoot?.playChannel(channelId)
            intent?.removeExtra(EXTRA_PLAY_CHANNEL)
        }

        val recordingId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recordingId > 0L) {
            nativeRoot?.openRecording(recordingId)
            intent?.removeExtra(EXTRA_WATCH_RECORDING)
        }
    }

    fun enterPipNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (nativeRoot?.isFullscreen == true) enterPipNow()
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
