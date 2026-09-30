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
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Programme
import kotlinx.coroutines.CoroutineScope

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
    private val topNav = LinearLayout(context)
    private val infoBar = LinearLayout(context)
    private val infoTitle = TextView(context)
    private val infoSubtitle = TextView(context)
    private val channelBar = NativeChannelBarView(context)
    private val groupDrawer = NativeGroupDrawerView(context)
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

    private val hideInfo = Runnable { infoBar.fadeGone(150) }
    private val hideChannelBar = Runnable {
        if (mode == Mode.CHANNEL_BAR) {
            channelBar.fadeGone(150)
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
        addView(playerFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildTopNav()
        addView(topNav, LayoutParams(LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP))

        buildInfoBar()
        addView(infoBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        channelBar.visibility = GONE
        addView(channelBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(88), Gravity.BOTTOM))

        groupDrawer.visibility = GONE
        addView(groupDrawer, LayoutParams(dp(330), LayoutParams.MATCH_PARENT, Gravity.START))

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
            override fun onRenderedFirstFrame() {
                shutter.animate().alpha(0f).setDuration(150).withEndAction {
                    shutter.visibility = GONE
                }.start()
            }
        })

        controller.loadGuide { loadedRows, loadedPrograms ->
            rows = loadedRows
            programMap = loadedPrograms
            guide.submit(rows, programMap, settings.lastChannelId)
            showFullscreen(initial = true)
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
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (mode == Mode.GROUPS) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideGroups()
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
                KeyEvent.KEYCODE_MENU -> { showSettings(); true }
                else -> false
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount == 1) {
            showQuickActions()
            return true
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { showGuide(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannelBar(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                controller.zap(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                controller.zap(+1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showInfoFor(settings.lastChannelId)
                true
            }
            KeyEvent.KEYCODE_MENU -> { showSettings(); true }
            else -> false
        }
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
        channelBar.visibility = GONE
        quickActions.visibility = GONE
    }

    private fun showGuide() {
        exitMultiviewIfNeeded()
        mode = Mode.GUIDE
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = GONE
        hideAllSurfaces()
        topNav.visibility = VISIBLE
        guide.visibility = VISIBLE
        guide.bringToFront()
        playerFrame.bringToFront()
        topNav.bringToFront()
        settingsDrawer.bringToFront()
        controller.player.volume = if (settings.guidePreviewSound.value) 1f else 0f

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
        topNav.visibility = GONE
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
        channelBar.fadeVisible(150)
        channelBar.bringToFront()
        handler.removeCallbacks(hideChannelBar)
        handler.postDelayed(hideChannelBar, 5_000)
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
        topNav.visibility = GONE
        vod.visibility = VISIBLE
        vod.bringToFront()
        vod.show(vodMode)
    }

    private fun showVodSeries(series: app.tufaratv.data.model.Series) {
        exitMultiviewIfNeeded()
        mode = Mode.VOD
        hideAllSurfaces()
        guide.visibility = GONE
        topNav.visibility = GONE
        vod.visibility = VISIBLE
        vod.bringToFront()
        vod.showSeriesDetail(series)
    }

    private fun showSearch() {
        exitMultiviewIfNeeded()
        mode = Mode.SEARCH
        hideAllSurfaces()
        guide.visibility = GONE
        topNav.visibility = GONE
        search.visibility = VISIBLE
        search.bringToFront()
        search.focusQuery()
    }

    private fun showRecordings() {
        exitMultiviewIfNeeded()
        mode = Mode.RECORDINGS
        hideAllSurfaces()
        guide.visibility = GONE
        topNav.visibility = GONE
        recordings.visibility = VISIBLE
        recordings.bringToFront()
        recordings.refresh()
    }

    private fun showMultiview() {
        mode = Mode.MULTIVIEW
        hideAllSurfaces()
        guide.visibility = GONE
        topNav.visibility = GONE
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
        infoBar.fadeVisible(150)
        infoBar.bringToFront()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3_000)
    }

    private fun buildTopNav() {
        topNav.orientation = LinearLayout.HORIZONTAL
        topNav.gravity = Gravity.CENTER_VERTICAL
        topNav.setPadding(dp(22), 0, dp(22), 0)
        topNav.setBackgroundColor(Color.argb(220, 11, 15, 25))
        topNav.visibility = GONE
        addNav("Live") { showGuide() }
        addNav("Films") { showVod(NativeVodBrowserView.Mode.MOVIES) }
        addNav("Séries") { showVod(NativeVodBrowserView.Mode.SERIES) }
        addNav("Recherche") { showSearch() }
        addNav("Multiview") { showMultiview() }
        addNav("DVR") { showRecordings() }
        addNav("Réglages") { showSettings() }
    }

    private fun addNav(label: String, action: () -> Unit) {
        topNav.addView(
            actionButton(label, action),
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
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
    }
}
