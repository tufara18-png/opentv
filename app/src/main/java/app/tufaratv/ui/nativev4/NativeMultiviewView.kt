package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import app.tufaratv.core.ServiceLocator
import app.tufaratv.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NativeMultiviewView(
    context: Context,
    private val scope: CoroutineScope,
    private val controller: NativeTvController,
) : FrameLayout(context) {
    private val graph = ServiceLocator.get(context)
    private val grid = GridLayout(context)
    private val picker = NativeChannelBarView(context)
    private val panes = ArrayList<Pane>(4)
    private var activePane = 0

    init {
        setBackgroundColor(Color.BLACK)
        grid.columnCount = 2
        grid.rowCount = 2
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        repeat(4) { index ->
            val pane = Pane(context, index)
            panes += pane
            grid.addView(
                pane.container,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 2, 1f),
                    GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    height = 0
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }

        picker.visibility = GONE
        addView(
            picker,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(92), Gravity.BOTTOM).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(16)
            },
        )
    }

    fun start() {
        val rows = controller.channels
        if (rows.isEmpty()) return
        panes.forEachIndexed { index, pane ->
            val row = rows[index % rows.size]
            tunePane(index, row.id)
        }
        panes.firstOrNull()?.container?.requestFocus()
        setActivePane(0)
    }

    fun handleBack(): Boolean {
        if (picker.visibility == VISIBLE) {
            picker.visibility = GONE
            panes.getOrNull(activePane)?.container?.requestFocus()
            return true
        }
        return false
    }

    fun release() {
        panes.forEach { it.playerController.release() }
    }

    private fun showPicker(index: Int) {
        activePane = index
        picker.submit(controller.channels, null) { channelId ->
            tunePane(index, channelId)
            picker.visibility = GONE
            panes[index].container.requestFocus()
        }
        picker.visibility = VISIBLE
        picker.bringToFront()
    }

    private fun tunePane(index: Int, channelId: Long) {
        val pane = panes.getOrNull(index) ?: return
        scope.launch {
            val request = controller.requestForChannel(channelId) ?: return@launch
            pane.title.text = request.title
            pane.playerController.play(request, debounce = false)
        }
    }

    private fun setActivePane(index: Int) {
        activePane = index
        panes.forEachIndexed { i, pane ->
            pane.player.volume = if (i == index) 1f else 0f
            pane.container.background = rounded(
                if (i == index) 0xFF2563EB.toInt() else 0xFF111827.toInt(),
            )
        }
    }

    private inner class Pane(context: Context, val index: Int) {
        val playerController = PlayerController(
            context = context,
            scope = scope,
            httpClient = graph.streamingHttpClient,
            subtitlesEnabled = false,
            dvr = false,
            sharedLive = true,
        )
        val player get() = playerController.player
        val container = FrameLayout(context)
        val playerView = PlayerView(context)
        val title = TextView(context)

        init {
            playerView.useController = false
            playerView.player = player
            playerView.isFocusable = false
            container.addView(
                playerView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            title.apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundColor(0x99000000.toInt())
                maxLines = 1
            }
            container.addView(
                title,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
            container.isFocusable = true
            container.isClickable = true
            container.background = rounded(0xFF111827.toInt())
            container.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    setActivePane(index)
                    container.animate().scaleX(1.01f).scaleY(1.01f).setDuration(120).start()
                } else {
                    container.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            container.setOnClickListener { showPicker(index) }
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(3), color)
        cornerRadius = dp(4).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
