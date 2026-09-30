package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Allocation-light TV guide. The channel/program snapshot is loaded once; scrolling only mutates
 * integer/long cursors and redraws visible pixels.
 */
class GuideCanvasView(context: Context) : View(context) {
    data class Selection(val channel: TellyChannelRow, val programme: Programme?)

    private var channels: List<TellyChannelRow> = emptyList()
    private var programmes: Map<String, List<Programme>> = emptyMap()

    private var selectedRow = 0
    private var firstVisibleRow = 0
    private var focusTimeMillis = System.currentTimeMillis()
    private var windowStartMillis = halfHourFloor(System.currentTimeMillis()) - HALF_HOUR
    private val visibleWindowMillis = 3L * HOUR

    private val bg = Paint().apply { color = Color.rgb(11, 15, 25) }
    private val header = Paint().apply { color = Color.rgb(15, 23, 42) }
    private val channelBg = Paint().apply { color = Color.rgb(17, 24, 39) }
    private val cell = Paint().apply { color = Color.rgb(30, 41, 59) }
    private val cellPast = Paint().apply { color = Color.rgb(22, 30, 45) }
    private val focus = Paint().apply { color = Color.rgb(59, 130, 246) }
    private val nowLine = Paint().apply {
        color = Color.rgb(239, 68, 68)
        strokeWidth = dp(2).toFloat()
    }
    private val divider = Paint().apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = 1f
    }
    private val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 250, 252)
        textSize = sp(15)
    }
    private val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textSize = sp(12)
    }
    private val bold = Paint(primary).apply {
        textSize = sp(16)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val headerH get() = dp(58).toFloat()
    private val channelW get() = dp(270).toFloat()
    private val rowH get() = dp(72).toFloat()
    private val cellGap get() = dp(2).toFloat()
    private val pxPerMs: Float
        get() = max(0.0001f, (width - channelW) / visibleWindowMillis.toFloat())

    init {
        isFocusable = true
        setBackgroundColor(Color.rgb(11, 15, 25))
    }

    fun submit(
        rows: List<TellyChannelRow>,
        programmeMap: Map<String, List<Programme>>,
        currentChannelId: Long? = null,
    ) {
        channels = rows
        programmes = programmeMap.mapValues { (_, v) -> v.sortedBy { it.startUtcMillis } }
        selectedRow = rows.indexOfFirst { it.id == currentChannelId }.takeIf { it >= 0 } ?: 0
        ensureVerticalWindow()
        focusTimeMillis = System.currentTimeMillis()
        invalidate()
    }

    fun moveVertical(delta: Int) {
        if (channels.isEmpty()) return
        selectedRow = (selectedRow + delta).coerceIn(0, channels.lastIndex)
        ensureVerticalWindow()
        invalidate()
    }

    fun moveHorizontal(delta: Int) {
        val current = selection()
        val list = current?.channel?.let(::programsFor).orEmpty()
        if (list.isEmpty()) {
            focusTimeMillis += delta * HALF_HOUR
        } else {
            val index = list.indexOfFirst {
                focusTimeMillis >= it.startUtcMillis && focusTimeMillis < it.endUtcMillis
            }.takeIf { it >= 0 } ?: list.indexOfFirst { it.startUtcMillis >= focusTimeMillis }
                .takeIf { it >= 0 } ?: list.lastIndex
            val target = (index + delta).coerceIn(0, list.lastIndex)
            focusTimeMillis = max(list[target].startUtcMillis, list[target].endUtcMillis - 1)
        }
        keepFocusTimeVisible()
        invalidate()
    }

    fun jumpNow() {
        focusTimeMillis = System.currentTimeMillis()
        windowStartMillis = halfHourFloor(focusTimeMillis) - HALF_HOUR
        invalidate()
    }

    fun selection(): Selection? {
        val row = channels.getOrNull(selectedRow) ?: return null
        val p = programsFor(row).firstOrNull {
            focusTimeMillis >= it.startUtcMillis && focusTimeMillis < it.endUtcMillis
        } ?: programsFor(row).firstOrNull { it.startUtcMillis >= focusTimeMillis }
        return Selection(row, p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (channels.isEmpty()) {
            canvas.drawText("Aucune chaîne", dp(32).toFloat(), dp(64).toFloat(), bold)
            return
        }
        drawHeader(canvas)
        val rowsVisible = ((height - headerH) / rowH).toInt().coerceAtLeast(1)
        val last = min(channels.size, firstVisibleRow + rowsVisible + 1)
        for (index in firstVisibleRow until last) {
            val y = headerH + (index - firstVisibleRow) * rowH
            drawChannel(canvas, index, y)
            drawPrograms(canvas, index, y)
        }
        drawNow(canvas)
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, header)
        canvas.drawRect(0f, 0f, channelW, headerH, channelBg)
        canvas.drawText("TV", dp(24).toFloat(), dp(36).toFloat(), bold)

        var tick = halfHourFloor(windowStartMillis)
        val end = windowStartMillis + visibleWindowMillis
        while (tick <= end) {
            val x = channelW + (tick - windowStartMillis) * pxPerMs
            canvas.drawLine(x, 0f, x, height.toFloat(), divider)
            canvas.drawText(timeFmt.format(Date(tick)), x + dp(8), dp(35).toFloat(), secondary)
            tick += HALF_HOUR
        }
    }

    private fun drawChannel(canvas: Canvas, index: Int, y: Float) {
        val row = channels[index]
        canvas.drawRect(0f, y + cellGap, channelW - cellGap, y + rowH - cellGap, channelBg)
        if (index == selectedRow) {
            canvas.drawRect(0f, y + cellGap, dp(5).toFloat(), y + rowH - cellGap, focus)
        }
        row.number?.let { canvas.drawText(it.toString(), dp(14).toFloat(), y + dp(28), secondary) }
        val name = row.customName?.takeIf { it.isNotBlank() } ?: row.displayName
        canvas.drawText(ellipsize(name, 26), dp(58).toFloat(), y + dp(31), bold)
        val now = programsFor(row).firstOrNull { it.isLiveAt(System.currentTimeMillis()) }
        if (now != null) {
            canvas.drawText(ellipsize(now.title, 30), dp(58).toFloat(), y + dp(53), secondary)
        }
    }

    private fun drawPrograms(canvas: Canvas, index: Int, y: Float) {
        val row = channels[index]
        val list = programsFor(row)
        val start = lowerBoundEndAfter(list, windowStartMillis)
        val windowEnd = windowStartMillis + visibleWindowMillis
        var i = start
        while (i < list.size) {
            val p = list[i]
            if (p.startUtcMillis >= windowEnd) break
            val left = channelW + (p.startUtcMillis - windowStartMillis) * pxPerMs
            val right = channelW + (p.endUtcMillis - windowStartMillis) * pxPerMs
            val rect = RectF(
                max(channelW, left) + cellGap,
                y + cellGap,
                min(width.toFloat(), right) - cellGap,
                y + rowH - cellGap,
            )
            if (rect.right > rect.left) {
                val selected = index == selectedRow &&
                    focusTimeMillis >= p.startUtcMillis && focusTimeMillis < p.endUtcMillis
                canvas.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(),
                    if (selected) focus else if (p.endUtcMillis < System.currentTimeMillis()) cellPast else cell)
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawText(
                    ellipsize(p.title, max(5, (rect.width() / dp(8)).toInt())),
                    rect.left + dp(10),
                    y + dp(31),
                    if (selected) bold else primary,
                )
                canvas.drawText(
                    timeFmt.format(Date(p.startUtcMillis)),
                    rect.left + dp(10),
                    y + dp(53),
                    secondary,
                )
                canvas.restore()
            }
            i++
        }
    }

    private fun drawNow(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (now !in windowStartMillis..(windowStartMillis + visibleWindowMillis)) return
        val x = channelW + (now - windowStartMillis) * pxPerMs
        canvas.drawLine(x, 0f, x, height.toFloat(), nowLine)
    }

    private fun programsFor(row: TellyChannelRow): List<Programme> {
        val ids = listOfNotNull(row.epgOverrideId, row.epgChannelId, row.matchedEpgId)
        for (id in ids) programmes[id]?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun ensureVerticalWindow() {
        val visible = ((height.takeIf { it > 0 } ?: dp(1080)) - headerH).div(rowH)
            .toInt().coerceAtLeast(7)
        val anchor = visible / 2
        firstVisibleRow = (selectedRow - anchor).coerceIn(0, max(0, channels.size - visible))
    }

    private fun keepFocusTimeVisible() {
        val padding = HALF_HOUR
        val right = windowStartMillis + visibleWindowMillis
        if (focusTimeMillis < windowStartMillis + padding) {
            windowStartMillis = halfHourFloor(focusTimeMillis) - padding
        } else if (focusTimeMillis > right - padding) {
            windowStartMillis = halfHourFloor(focusTimeMillis - visibleWindowMillis + padding)
        }
    }

    private fun lowerBoundEndAfter(list: List<Programme>, time: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].endUtcMillis <= time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun ellipsize(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars - 1) + "…"

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun sp(v: Int) = v * resources.displayMetrics.scaledDensity

    companion object {
        private const val HALF_HOUR = 30L * 60 * 1000
        private const val HOUR = 60L * 60 * 1000
        private fun halfHourFloor(value: Long): Long = value - value.mod(HALF_HOUR)
    }
}
