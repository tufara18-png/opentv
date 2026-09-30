package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Programme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NativeTvRootView(
    context: Context,
    private val scope: CoroutineScope,
    private val onEnterPip: () -> Unit,
) : FrameLayout(context) {
    private val graph = ServiceLocator.get(context)
    private val settings = graph.settings
    private val controller = NativeTvController(context, scope)
    private val handler = Handler(Looper.getMainLooper())

    private val guide = GuideCanvasView(context)
    private val playerFrame = FrameLayout(context)
    private val playerView = PlayerView(context)
    private val shutter = View(context)
    private val buffering = ProgressBar(context)
    private val startupOverlay = LinearLayout(context)
    private val startupText = TextView(context)
    private val startupProgress = ProgressBar(context)
    private var firstFrameRendered = false
    private var startupRetuneAttempted = false
    private val infoBar = LinearLayout(context)
    private val infoTitle = TextView(context)
    private val infoSubtitle = TextView(context)
    private val channelBar = NativeChannelBarView(context)
    private val groupDrawer = NativeGroupDrawerView(context)
    private val mainMenu = NativeMainMenuView(context)
    private val quickActions = LinearLayout(context)
    private val vod = NativeVodBrowserView(context, scope, controller) { showFullscreen() }
    private val search = NativeSearchView(
        context = context,
        scope = scope,
        controller = controller,
        onChannel = { id ->
            controller.tuneChannel(id)
            showInfoFor(id)
            showFullscreen()
        },
        onMovie = {
            controller.playMovie(it)
            showFullscreen()
        },
        onSeries = { series ->
            showVodSeries(series)
        },
    )
    private val recordings = NativeRecordingLibraryView(context, scope, controller) {
        controller.playRecording(it)
        showFullscreen()
    }
    private val multiview = NativeMultiviewView(context, scope, controller)
    private val settingsDrawer = NativeSettingsDrawer(context, controller, ::reloadGuide)

    private var rows: List<TellyChannelRow> = emptyList()
    private var programMap: Map<String, List<Programme>> = emptyMap()
    private var mode = Mode.FULLSCREEN
    val isFullscreen: Boolean get() = mode == Mode.FULLSCREEN

    private val startupFallback = Runnable {
        if (firstFrameRendered) return@Runnable
        if (rows.isNotEmpty()) {
            startupOverlay.visibility = GONE
            showGuide(preserveAudio = true)
        } else {
            showStartupMessage("Aucune chaîne disponible. Ouvrez le menu pour vérifier la source.")
        }
    }

    private val bufferWatchdog = Runnable {
        if (controller.player.playbackState == Player.STATE_BUFFERING) {
            controller.resyncCurrent()
            handler.removeCallbacks(startupFallback)
            handler.postDelayed(startupFallback, 3_500)
        }
    }

    private var centerPressed = false
    private var centerLongTriggered = false
    private var downPressed = false
    private var downLongTriggered = false

    private val centerLongPress = Runnable {
        if (centerPressed && mode == Mode.FULLSCREEN) {
            centerLongTriggered = true
            showQuickActions()
        }
    }

    private val downLongPress = Runnable {
        if (downPressed && mode == Mode.FULLSCREEN) {
            downLongTriggered = true
            showRecentChannels()
        }
    }

    private val hideInfo = Runnable { infoBar.fadeGone(250) }
    private val hideChannelBar = Runnable {
        if (mode == Mode.CHANNEL_BAR) {
            channelBar.animate()
                .alpha(0f)
                .translationY(dp(48).toFloat())
                .setDuration(250)
                .withEndAction {
                    channelBar.visibility = GONE
                    channelBar.alpha = 1f
                    channelBar.translationY = 0f
                }
                .start()
            mode = Mode.FULLSCREEN
            playerView.requestFocus()
        }
    }
    private val hideQuick = Runnable {
        if (mode == Mode.QUICK) {
            quickActions.fadeGone(150)
            mode = Mode.FULLSCREEN
            playerView.requestFocus()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true

        guide.visibility = GONE
        guide.onRowFocusChanged = { rowIndex ->
            controller.loadEpgSegment(rowIndex) { updated ->
                programMap = updated
                guide.updateProgrammes(updated)
            }
        }
        addView(guide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        playerView.apply {
            useController = false
            resizeMode = settings.playerResizeMode.value
            setShutterBackgroundColor(Color.BLACK)
            player = controller.player
            isFocusable = true
        }
        playerFrame.addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shutter.setBackgroundColor(Color.BLACK)
        playerFrame.addView(shutter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buffering.visibility = GONE
        playerFrame.addView(
            buffering,
            LayoutParams(dp(52), dp(52), Gravity.CENTER),
        )
        addView(playerFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        startupOverlay.orientation = LinearLayout.VERTICAL
        startupOverlay.gravity = Gravity.CENTER
        startupOverlay.setBackgroundColor(Color.rgb(8, 12, 20))
        startupText.apply {
            text = "Chargement de la télévision…"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        startupOverlay.addView(
            startupProgress,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(18) },
        )
        startupOverlay.addView(
            startupText,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(startupOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildInfoBar()
        addView(infoBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        channelBar.visibility = GONE
        addView(channelBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(88), Gravity.BOTTOM))

        groupDrawer.visibility = GONE
        addView(groupDrawer, LayoutParams(dp(330), LayoutParams.MATCH_PARENT, Gravity.START))

        mainMenu.visibility = GONE
        addView(mainMenu, LayoutParams(dp(290), LayoutParams.MATCH_PARENT, Gravity.START))

        buildQuickActions()
        addView(
            quickActions,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(34)
            },
        )

        vod.visibility = GONE
        addView(vod, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        search.visibility = GONE
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        recordings.visibility = GONE
        addView(recordings, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        multiview.visibility = GONE
        addView(multiview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        settingsDrawer.visibility = GONE
        addView(settingsDrawer, LayoutParams(dp(430), LayoutParams.MATCH_PARENT, Gravity.END))

        controller.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                handler.removeCallbacks(bufferWatchdog)
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        buffering.visibility = VISIBLE
                        showStartupMessage("Connexion à la chaîne…")
                        handler.postDelayed(bufferWatchdog, 5_000)
                    }
                    Player.STATE_READY -> buffering.visibility = GONE
                    Player.STATE_ENDED -> {
                        buffering.visibility = GONE
                        handler.post(startupFallback)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering.visibility = GONE
                if (!startupRetuneAttempted && settings.lastChannelId > 0L) {
                    startupRetuneAttempted = true
                    showStartupMessage("Reconnexion à la chaîne…")
                    controller.resyncCurrent()
                    handler.removeCallbacks(startupFallback)
                    handler.postDelayed(startupFallback, 3_500)
                } else {
                    handler.post(startupFallback)
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                startupRetuneAttempted = false
                handler.removeCallbacks(startupFallback)
                handler.removeCallbacks(bufferWatchdog)
                buffering.visibility = GONE
                startupOverlay.animate().alpha(0f).setDuration(120).withEndAction {
                    startupOverlay.visibility = GONE
                    startupOverlay.alpha = 1f
                }.start()
                shutter.animate().alpha(0f).setDuration(150).withEndAction {
                    shutter.visibility = GONE
                }.start()
            }
        })

        controller.loadGuide { loadedRows, loadedPrograms ->
            rows = loadedRows
            programMap = loadedPrograms
            guide.submit(rows, programMap, settings.lastChannelId)
            if (rows.isEmpty()) {
                showStartupMessage("Aucune chaîne disponible. Vérifiez votre source.")
            } else {
                showFullscreen(initial = true)
                if (!firstFrameRendered) {
                    startupOverlay.visibility = VISIBLE
                    startupOverlay.bringToFront()
                    handler.removeCallbacks(startupFallback)
                    handler.postDelayed(startupFallback, 3_500)
                }
            }
        }
    }

    fun playChannel(id: Long) {
        controller.tuneChannel(id)
        showInfoFor(id)
    }

    fun openRecording(id: Long) {
        controller.openRecording(id) { found ->
            if (found != null) showFullscreen()
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        // Distinguish short/long presses before dispatching to overlays. Doing the short action on
        // ACTION_DOWN makes a long press impossible because the first repeat arrives after the UI
        // has already changed state.
        if (mode == Mode.FULLSCREEN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        centerPressed = true
                        centerLongTriggered = false
                        handler.removeCallbacks(centerLongPress)
                        handler.postDelayed(centerLongPress, 500)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(centerLongPress)
                    centerPressed = false
                    if (!centerLongTriggered) showChannelBar()
                    centerLongTriggered = false
                    return true
                }
            }
        }

        if (mode == Mode.FULLSCREEN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        downPressed = true
                        downLongTriggered = false
                        handler.removeCallbacks(downLongPress)
                        handler.postDelayed(downLongPress, 500)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(downLongPress)
                    downPressed = false
                    if (!downLongTriggered) controller.zap(+1)
                    downLongTriggered = false
                    return true
                }
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (event.keyCode == KeyEvent.KEYCODE_BACK && (event.repeatCount > 0 || event.isLongPress)) {
            when (mode) {
                Mode.MENU, Mode.GROUPS, Mode.SETTINGS, Mode.CHANNEL_BAR, Mode.QUICK -> {
                    closeOverlayImmediately()
                    return true
                }
                else -> Unit
            }
        }

        if (mode == Mode.MENU) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideMainMenu()
                return true
            }
            return false
        }

        if (mode == Mode.GROUPS) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                showMainMenu()
                return true
            }
            return false
        }

        if (mode == Mode.SETTINGS) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideSettings()
                return true
            }
            return false
        }

        if (mode == Mode.SEARCH) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                showGuide()
                return true
            }
            return false
        }

        if (mode == Mode.RECORDINGS) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                showGuide()
                return true
            }
            return false
        }

        if (mode == Mode.MULTIVIEW) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (multiview.handleBack()) return true
                showGuide()
                return true
            }
            return false
        }

        if (mode == Mode.VOD) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (!vod.handleBack()) showGuide()
                return true
            }
            return false
        }

        if (mode == Mode.CHANNEL_BAR) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                handler.removeCallbacks(hideChannelBar)
                hideChannelBar.run()
                return true
            }
            handler.removeCallbacks(hideChannelBar)
            handler.postDelayed(hideChannelBar, 5_000)
            return false
        }

        if (mode == Mode.QUICK) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                handler.removeCallbacks(hideQuick)
                hideQuick.run()
                return true
            }
            handler.removeCallbacks(hideQuick)
            handler.postDelayed(hideQuick, 5_000)
            return false
        }

        if (mode == Mode.GUIDE) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { guide.moveVertical(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { guide.moveVertical(+1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { guide.moveHorizontal(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { guide.moveHorizontal(+1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    guide.selection()?.let {
                        controller.tuneChannel(it.channel.id)
                        showInfoFor(it.channel.id, it.programme?.title)
                    }
                    showFullscreen()
                    true
                }
                KeyEvent.KEYCODE_BACK -> { showGroups(); true }
                KeyEvent.KEYCODE_MENU -> { showMainMenu(); true }
                else -> false
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { showGuide(preserveAudio = true); true }
            KeyEvent.KEYCODE_ENTER -> { showChannelBar(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                controller.zap(-1)
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                controller.zap(+1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showInfoFor(settings.lastChannelId)
                true
            }
            KeyEvent.KEYCODE_MENU -> { showMainMenu(); true }
            else -> false
        }
    }

    private fun closeOverlayImmediately() {
        handler.removeCallbacks(centerLongPress)
        handler.removeCallbacks(downLongPress)
        handler.removeCallbacks(hideChannelBar)
        handler.removeCallbacks(hideQuick)
        mainMenu.animate().cancel()
        groupDrawer.animate().cancel()
        settingsDrawer.animate().cancel()
        channelBar.animate().cancel()
        quickActions.animate().cancel()
        mainMenu.visibility = GONE
        groupDrawer.visibility = GONE
        settingsDrawer.visibility = GONE
        channelBar.visibility = GONE
        quickActions.visibility = GONE
        mode = Mode.FULLSCREEN
        controller.player.volume = 1f
        playerFrame.scaleX = 1f
        playerFrame.scaleY = 1f
        playerFrame.translationX = 0f
        playerFrame.translationY = 0f
        guide.visibility = GONE
        playerFrame.bringToFront()
        infoBar.bringToFront()
        playerView.requestFocus()
    }

    private fun exitMultiviewIfNeeded() {
        if (mode == Mode.MULTIVIEW) multiview.stop()
    }

    private fun hideAllSurfaces() {
        vod.visibility = GONE
        search.visibility = GONE
        recordings.visibility = GONE
        multiview.visibility = GONE
        settingsDrawer.visibility = GONE
        groupDrawer.visibility = GONE
        mainMenu.visibility = GONE
        channelBar.visibility = GONE
        quickActions.visibility = GONE
    }

    private fun showGuide(preserveAudio: Boolean = false) {
        exitMultiviewIfNeeded()
        mode = Mode.GUIDE
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = GONE
        hideAllSurfaces()
        guide.visibility = VISIBLE
        guide.bringToFront()
        playerFrame.bringToFront()
        settingsDrawer.bringToFront()
        controller.player.volume = if (preserveAudio || settings.guidePreviewSound.value) 1f else 0f

        playerFrame.pivotX = 0f
        playerFrame.pivotY = 0f
        playerFrame.animate()
            .scaleX(.36f)
            .scaleY(.36f)
            .translationX(width * .025f)
            .translationY(dp(70).toFloat())
            .setDuration(250)
            .withEndAction { guide.requestFocus() }
            .start()
    }

    private fun showFullscreen(initial: Boolean = false) {
        exitMultiviewIfNeeded()
        mode = Mode.FULLSCREEN
        handler.removeCallbacks(hideChannelBar)
        handler.removeCallbacks(hideQuick)
        controller.player.volume = 1f
        hideAllSurfaces()
        playerFrame.bringToFront()
        infoBar.bringToFront()

        val end = {
            guide.visibility = GONE
            playerView.requestFocus()
        }
        if (initial) {
            playerFrame.scaleX = 1f
            playerFrame.scaleY = 1f
            playerFrame.translationX = 0f
            playerFrame.translationY = 0f
            end()
        } else {
            playerFrame.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(250)
                .withEndAction { end() }
                .start()
        }
    }

    private fun showMainMenu() {
        mode = Mode.MENU
        groupDrawer.visibility = GONE
        mainMenu.submit(
            listOf(
                NativeMainMenuView.Item("live", "TV Guide") { dismissMainMenu { showGuide(preserveAudio = true) } },
                NativeMainMenuView.Item("movies", "Films") { dismissMainMenu { showVod(NativeVodBrowserView.Mode.MOVIES) } },
                NativeMainMenuView.Item("series", "Séries") { dismissMainMenu { showVod(NativeVodBrowserView.Mode.SERIES) } },
                NativeMainMenuView.Item("search", "Recherche") { dismissMainMenu { showSearch() } },
                NativeMainMenuView.Item("multi", "Multiview") { dismissMainMenu { showMultiview() } },
                NativeMainMenuView.Item("dvr", "Enregistrements") { dismissMainMenu { showRecordings() } },
                NativeMainMenuView.Item("settings", "Réglages") { dismissMainMenu { showSettings() } },
            ),
        )
        mainMenu.visibility = VISIBLE
        mainMenu.translationX = -dp(290).toFloat()
        mainMenu.bringToFront()
        mainMenu.animate().translationX(0f).setDuration(180).start()
    }

    private fun dismissMainMenu(after: () -> Unit) {
        mainMenu.animate()
            .translationX(-mainMenu.width.toFloat())
            .setDuration(160)
            .withEndAction {
                mainMenu.visibility = GONE
                after()
            }
            .start()
    }

    private fun hideMainMenu() {
        dismissMainMenu {
            mode = Mode.GUIDE
            guide.requestFocus()
        }
    }

    private fun showGroups() {
        if (controller.groups.isEmpty()) return
        mode = Mode.GROUPS
        groupDrawer.submit(controller.groups, controller.currentGroup) { group ->
            controller.loadGroup(group) { loadedRows, loadedPrograms ->
                rows = loadedRows
                programMap = loadedPrograms
                guide.submit(rows, programMap, rows.firstOrNull()?.id)
                hideGroups()
            }
        }
        groupDrawer.visibility = VISIBLE
        groupDrawer.translationX = -dp(330).toFloat()
        groupDrawer.bringToFront()
        groupDrawer.animate().translationX(0f).setDuration(180).start()
    }

    private fun hideGroups() {
        groupDrawer.animate()
            .translationX(-groupDrawer.width.toFloat())
            .setDuration(160)
            .withEndAction {
                groupDrawer.visibility = GONE
                mode = Mode.GUIDE
                guide.requestFocus()
            }
            .start()
    }

    private fun showChannelBar() {
        mode = Mode.CHANNEL_BAR
        val active = settings.lastChannelId
        channelBar.submit(rows, active) { id ->
            controller.tuneChannel(id)
            showInfoFor(id)
            handler.removeCallbacks(hideChannelBar)
            handler.postDelayed(hideChannelBar, 5_000)
        }
        channelBar.visibility = VISIBLE
        channelBar.alpha = 0f
        channelBar.translationY = dp(48).toFloat()
        channelBar.bringToFront()
        channelBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .start()
        handler.removeCallbacks(hideChannelBar)
        handler.postDelayed(hideChannelBar, 5_000)
    }

    private fun showRecentChannels() {
        mode = Mode.CHANNEL_BAR
        scope.launch {
            val recent = controller.recentChannels()
            if (recent.isEmpty()) {
                mode = Mode.FULLSCREEN
                playerView.requestFocus()
                return@launch
            }
            channelBar.submit(recent, settings.lastChannelId) { id ->
                controller.tuneChannel(id)
                showInfoFor(id)
                handler.removeCallbacks(hideChannelBar)
                handler.postDelayed(hideChannelBar, 5_000)
            }
            channelBar.visibility = VISIBLE
            channelBar.alpha = 0f
            channelBar.translationY = dp(48).toFloat()
            channelBar.bringToFront()
            channelBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
            handler.removeCallbacks(hideChannelBar)
            handler.postDelayed(hideChannelBar, 5_000)
        }
    }

    private fun showQuickActions() {
        mode = Mode.QUICK
        quickActions.fadeVisible(150)
        quickActions.bringToFront()
        quickActions.getChildAt(0)?.requestFocus()
        handler.removeCallbacks(hideQuick)
        handler.postDelayed(hideQuick, 5_000)
    }

    private fun showVod(vodMode: NativeVodBrowserView.Mode) {
        exitMultiviewIfNeeded()
        mode = Mode.VOD
        hideAllSurfaces()
        guide.visibility = GONE
        vod.visibility = VISIBLE
        vod.bringToFront()
        vod.show(vodMode)
    }

    private fun showVodSeries(series: app.tufaratv.data.model.Series) {
        exitMultiviewIfNeeded()
        mode = Mode.VOD
        hideAllSurfaces()
        guide.visibility = GONE
        vod.visibility = VISIBLE
        vod.bringToFront()
        vod.showSeriesDetail(series)
    }

    private fun showSearch() {
        exitMultiviewIfNeeded()
        mode = Mode.SEARCH
        hideAllSurfaces()
        guide.visibility = GONE
        search.visibility = VISIBLE
        search.bringToFront()
        search.focusQuery()
    }

    private fun showRecordings() {
        exitMultiviewIfNeeded()
        mode = Mode.RECORDINGS
        hideAllSurfaces()
        guide.visibility = GONE
        recordings.visibility = VISIBLE
        recordings.bringToFront()
        recordings.refresh()
    }

    private fun showMultiview() {
        mode = Mode.MULTIVIEW
        hideAllSurfaces()
        guide.visibility = GONE
        controller.player.volume = 0f
        multiview.visibility = VISIBLE
        multiview.bringToFront()
        multiview.start()
    }

    private fun showSettings() {
        exitMultiviewIfNeeded()
        mode = Mode.SETTINGS
        settingsDrawer.rebuild()
        settingsDrawer.visibility = VISIBLE
        settingsDrawer.translationX = settingsDrawer.layoutParams.width.toFloat()
        settingsDrawer.bringToFront()
        settingsDrawer.animate().translationX(0f).setDuration(200).start()
    }

    private fun hideSettings() {
        settingsDrawer.animate()
            .translationX(settingsDrawer.width.toFloat())
            .setDuration(200)
            .withEndAction {
                settingsDrawer.visibility = GONE
                if (guide.visibility == VISIBLE) {
                    mode = Mode.GUIDE
                    guide.requestFocus()
                } else {
                    mode = Mode.FULLSCREEN
                    playerView.requestFocus()
                }
            }.start()
    }

    private fun reloadGuide() {
        controller.refreshGuide { loadedRows, loadedPrograms ->
            rows = loadedRows
            programMap = loadedPrograms
            guide.submit(rows, programMap, settings.lastChannelId)
        }
    }

    private fun showInfoFor(channelId: Long, explicitProgramme: String? = null) {
        val row = rows.firstOrNull { it.id == channelId } ?: return
        val name = row.customName?.takeIf { it.isNotBlank() } ?: row.displayName
        infoTitle.text = buildString {
            row.number?.let { append(it).append("  ") }
            append(name)
        }
        val ids = listOfNotNull(row.epgOverrideId, row.epgChannelId, row.matchedEpgId)
        val now = explicitProgramme ?: ids.asSequence()
            .mapNotNull(programMap::get)
            .flatten()
            .firstOrNull { it.isLiveAt(System.currentTimeMillis()) }
            ?.title
            .orEmpty()
        infoSubtitle.text = now
        infoBar.fadeVisible(200)
        infoBar.bringToFront()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 5_000)
    }

    private fun showStartupMessage(message: String) {
        if (firstFrameRendered) return
        startupText.text = message
        startupOverlay.visibility = VISIBLE
        startupOverlay.alpha = 1f
        startupOverlay.bringToFront()
    }

    private fun buildInfoBar() {
        infoBar.orientation = LinearLayout.VERTICAL
        infoBar.setPadding(dp(28), dp(15), dp(28), dp(18))
        infoBar.setBackgroundColor(Color.argb(224, 11, 15, 25))
        infoBar.visibility = GONE
        infoTitle.setTextColor(Color.WHITE)
        infoTitle.textSize = 20f
        infoTitle.setTypeface(infoTitle.typeface, android.graphics.Typeface.BOLD)
        infoSubtitle.setTextColor(0xFFCBD5E1.toInt())
        infoSubtitle.textSize = 15f
        infoBar.addView(infoTitle)
        infoBar.addView(infoSubtitle)
    }

    private fun buildQuickActions() {
        quickActions.orientation = LinearLayout.HORIZONTAL
        quickActions.gravity = Gravity.CENTER
        quickActions.setPadding(dp(12), dp(8), dp(12), dp(8))
        quickActions.background = rounded(0xEE0B0F19.toInt())
        quickActions.visibility = GONE

        quickActions.addView(actionButton("Sous-titres") {
            controller.setSubtitles(!settings.subtitlesEnabled.value)
        })
        quickActions.addView(actionButton("Format") {
            val next = when (settings.playerResizeMode.value) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            settings.setPlayerResizeMode(next)
            playerView.resizeMode = next
        })
        quickActions.addView(actionButton("PiP") { onEnterPip() })
        quickActions.addView(actionButton("Multiview") { showMultiview() })
        quickActions.addView(actionButton("Réglages") { showSettings() })
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .setDuration(150).start()
                background = rounded(if (focused) 0xFF2563EB.toInt() else Color.TRANSPARENT)
            }
        }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(centerLongPress)
        handler.removeCallbacks(downLongPress)
        handler.removeCallbacksAndMessages(null)
        controller.checkpointVod()
        super.onDetachedFromWindow()
    }

    fun release() {
        multiview.release()
        controller.release()
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(9).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun View.fadeVisible(duration: Long) {
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(duration).start()
    }

    private fun View.fadeGone(duration: Long) {
        animate().alpha(0f).setDuration(duration).withEndAction {
            visibility = GONE
            alpha = 1f
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    private enum class Mode {
        FULLSCREEN,
        GUIDE,
        CHANNEL_BAR,
        QUICK,
        VOD,
        SEARCH,
        RECORDINGS,
        MULTIVIEW,
        SETTINGS,
        GROUPS,
        MENU,
    }
}
