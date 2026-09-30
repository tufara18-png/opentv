package app.tufaratv.ui.nativev4

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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

/**
 * One persistent native TV scene: the player Surface never leaves the hierarchy. Guide, channel
 * bar, VOD and settings are overlays, so BACK/OK transitions cannot tear down audio/video.
 */
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
    private val quickActions = LinearLayout(context)
    private val vod = NativeVodBrowserView(context, scope, controller) { showFullscreen() }
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

        buildQuickActions()
        addView(quickActions, LayoutParams(LayoutParams.WRAP_CONTENT, dp(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(34)
        })

        vod.visibility = GONE
        addView(vod, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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

    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount == 1) {
            showQuickActions()
            return true
        }

        if (mode == Mode.SETTINGS) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideSettings()
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
                KeyEvent.KEYCODE_BACK -> { showFullscreen(); true }
                KeyEvent.KEYCODE_MENU -> { showSettings(); true }
                else -> false
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { showGuide(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannelBar(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                controller.zap(-1); showInfoFor(settings.lastChannelId); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                controller.zap(+1); showInfoFor(settings.lastChannelId); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showInfoFor(settings.lastChannelId); true
            }
            KeyEvent.KEYCODE_MENU -> { showSettings(); true }
            else -> false
        }
    }

    private fun showGuide() {
        mode = Mode.GUIDE
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = GONE
        channelBar.visibility = GONE
        quickActions.visibility = GONE
        vod.visibility = GONE
        settingsDrawer.visibility = GONE
        topNav.visibility = VISIBLE
        guide.visibility = VISIBLE
        guide.bringToFront()
        playerFrame.bringToFront()
        topNav.bringToFront()
        settingsDrawer.bringToFront()
        controller.player.volume = if (settings.guidePreviewSound.value) 1f else 0f

        playerFrame.pivotX = 0f
        playerFrame.pivotY = 0f
        val scale = 0.36f
        playerFrame.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationX(width * 0.025f)
            .translationY(dp(70).toFloat())
            .setDuration(250)
            .withEndAction { guide.requestFocus() }
            .start()
    }

    private fun showFullscreen(initial: Boolean = false) {
        mode = Mode.FULLSCREEN
        handler.removeCallbacks(hideChannelBar)
        handler.removeCallbacks(hideQuick)
        controller.player.volume = 1f
        vod.visibility = GONE
        settingsDrawer.visibility = GONE
        channelBar.visibility = GONE
        quickActions.visibility = GONE
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

    private fun showVod(mode: NativeVodBrowserView.Mode) {
        this.mode = Mode.VOD
        guide.visibility = GONE
        topNav.visibility = GONE
        settingsDrawer.visibility = GONE
        channelBar.visibility = GONE
        quickActions.visibility = GONE
        vod.visibility = VISIBLE
        vod.bringToFront()
        vod.show(mode)
    }

    private fun showSettings() {
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
        topNav.orientation = HORIZONTAL
        topNav.gravity = Gravity.CENTER_VERTICAL
        topNav.setPadding(dp(22), 0, dp(22), 0)
        topNav.setBackgroundColor(Color.argb(220, 11, 15, 25))
        topNav.visibility = GONE
        addNav("Live") { showGuide() }
        addNav("Films") { showVod(NativeVodBrowserView.Mode.MOVIES) }
        addNav("Séries") { showVod(NativeVodBrowserView.Mode.SERIES) }
        addNav("Recherche") { openLegacy("search") }
        addNav("Multiview") { openLegacy("multiview") }
        addNav("DVR") { openLegacy("recordings") }
        addNav("Réglages") { showSettings() }
    }

    private fun addNav(label: String, action: () -> Unit) {
        topNav.addView(actionButton(label, action), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun buildInfoBar() {
        infoBar.orientation = VERTICAL
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
        quickActions.orientation = HORIZONTAL
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
        quickActions.addView(actionButton("Multiview") { openLegacy("multiview") })
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

    private fun openLegacy(route: String) {
        context.startActivity(
            Intent(context, TellyFeatureActivity::class.java)
                .putExtra(TellyFeatureActivity.EXTRA_ROUTE, route),
        )
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        controller.checkpointVod()
        super.onDetachedFromWindow()
    }

    fun release() = controller.release()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(9).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun View.fadeVisible(duration: Long) {
        handler.removeCallbacks(hideInfo)
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

    private enum class Mode { FULLSCREEN, GUIDE, CHANNEL_BAR, QUICK, VOD, SETTINGS }
}
