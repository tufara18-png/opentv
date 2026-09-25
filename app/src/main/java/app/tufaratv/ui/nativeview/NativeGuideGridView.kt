package app.tufaratv.ui.nativeview

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.model.Programme
import app.tufaratv.data.model.shownName
import app.tufaratv.ui.ChannelsViewModel
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native, allocation-bounded TV guide. Only visible channel rows exist; horizontal guide strips
 * share one scroll position. This avoids a Compose tree containing every programme block.
 */
class NativeGuideGridView(context: Context) : LinearLayout(context) {
    private val header = GuideHeader(context)
    private val list = RecyclerView(context)
    private val rowsAdapter = GridRowsAdapter(::onStripScrolled)
    private var scrollXShared = 0
    private var syncingScroll = false

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = rowsAdapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(10)
        list.isFocusable = false
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        header.strip.setOnScrollChangeListener { view, x, _, _, _ -> onStripScrolled(view, x) }
    }

    fun submit(
        rows: List<ChannelsViewModel.Row>,
        windowStartMillis: Long,
        selectedKey: Any?,
        palette: NativeGuidePalette,
        onSelect: (ChannelsViewModel.Row) -> Unit,
        onFocus: (ChannelsViewModel.Row) -> Unit,
        onLongSelect: (ChannelsViewModel.Row) -> Unit,
        onProgramme: (ChannelsViewModel.Row, Programme) -> Unit,
        onExitLeft: () -> Boolean,
    ) {
        header.bind(windowStartMillis, palette)
        rowsAdapter.submit(
            rows, windowStartMillis, selectedKey, palette, scrollXShared,
            onSelect, onFocus, onLongSelect, onProgramme, onExitLeft,
        )
    }

    private fun onStripScrolled(source: View, x: Int) {
        if (syncingScroll || x == scrollXShared) return
        scrollXShared = x
        syncingScroll = true
        if (source !== header.strip) header.strip.scrollTo(x, 0)
        for (i in 0 until list.childCount) {
            val strip = list.getChildAt(i).findViewWithTag<HorizontalScrollView>(STRIP_TAG)
            if (strip !== source) strip?.scrollTo(x, 0)
        }
        syncingScroll = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

private class GridRowsAdapter(
    private val scrollListener: (View, Int) -> Unit,
) : RecyclerView.Adapter<GridRowHolder>() {
    private var rows = emptyList<ChannelsViewModel.Row>()
    private var windowStart = 0L
    private var selectedKey: Any? = null
    private var palette = DEFAULT_GUIDE_PALETTE
    private var scrollX = 0
    private var select: (ChannelsViewModel.Row) -> Unit = {}
    private var focus: (ChannelsViewModel.Row) -> Unit = {}
    private var longSelect: (ChannelsViewModel.Row) -> Unit = {}
    private var programme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> }
    private var exitLeft: () -> Boolean = { false }

    fun submit(
        rows: List<ChannelsViewModel.Row>, windowStart: Long, selectedKey: Any?,
        palette: NativeGuidePalette, scrollX: Int,
        select: (ChannelsViewModel.Row) -> Unit, focus: (ChannelsViewModel.Row) -> Unit,
        longSelect: (ChannelsViewModel.Row) -> Unit,
        programme: (ChannelsViewModel.Row, Programme) -> Unit,
        exitLeft: () -> Boolean,
    ) {
        val structureChanged = this.rows !== rows && this.rows != rows || this.windowStart != windowStart || this.palette != palette
        val oldSelected = this.selectedKey
        this.rows = rows
        this.windowStart = windowStart
        this.selectedKey = selectedKey
        this.palette = palette
        this.scrollX = scrollX
        this.select = select
        this.focus = focus
        this.longSelect = longSelect
        this.programme = programme
        this.exitLeft = exitLeft
        if (structureChanged) {
            notifyDataSetChanged()
        } else if (oldSelected != selectedKey) {
            rows.indexOfFirst { it.key == oldSelected }.takeIf { it >= 0 }?.let(::notifyItemChanged)
            rows.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        }
    }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        GridRowHolder(NativeGridRow(parent.context, scrollListener))

    override fun onBindViewHolder(holder: GridRowHolder, position: Int) {
        val row = rows[position]
        holder.row.bind(
            row, windowStart, row.key == selectedKey, palette, scrollX,
            { select(row) }, { focus(row) }, { longSelect(row) },
            { programme(row, it) }, exitLeft,
        )
    }
}

private class GridRowHolder(val row: NativeGridRow) : RecyclerView.ViewHolder(row)

private class NativeGridRow(
    context: Context,
    scrollListener: (View, Int) -> Unit,
) : LinearLayout(context) {
    private val channel = LinearLayout(context)
    private val number = TextView(context).apply { maxLines = 1; textSize = 12f }
    private val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val name = TextView(context).apply { maxLines = 2; setTypeface(typeface, Typeface.BOLD) }
    private val stripContent = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val strip = HorizontalScrollView(context).apply {
        tag = STRIP_TAG
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(stripContent, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnScrollChangeListener { view, x, _, _, _ -> scrollListener(view, x) }
    }
    private var selected = false
    private var palette = DEFAULT_GUIDE_PALETTE
    private var boundKey: Any? = null
    private var boundProgrammes: List<Programme>? = null
    private var boundWindow = Long.MIN_VALUE
    private var focusAction: (() -> Unit)? = null
    private var longPressHandled = false

    init {
        orientation = HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, dp(67))
        setPadding(0, dp(2), 0, dp(1))
        channel.orientation = HORIZONTAL
        channel.gravity = Gravity.CENTER_VERTICAL
        channel.isFocusable = true
        channel.isClickable = true
        channel.isLongClickable = true
        channel.setPadding(dp(8), 0, dp(8), 0)
        channel.addView(number, LayoutParams(dp(46), LayoutParams.WRAP_CONTENT))
        channel.addView(logo, LayoutParams(dp(38), dp(38)))
        channel.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addView(channel, LayoutParams(dp(CHANNEL_WIDTH_DP), LayoutParams.MATCH_PARENT))
        addView(space(dp(3)), LayoutParams(dp(3), LayoutParams.MATCH_PARENT))
        addView(strip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        channel.setOnFocusChangeListener { _, focused ->
            if (focused) focusAction?.invoke()
            paintChannel()
        }
    }

    fun bind(
        row: ChannelsViewModel.Row,
        windowStart: Long,
        selected: Boolean,
        palette: NativeGuidePalette,
        scrollX: Int,
        select: () -> Unit,
        focus: () -> Unit,
        longSelect: () -> Unit,
        onProgramme: (Programme) -> Unit,
        exitLeft: () -> Boolean,
    ) {
        this.selected = selected
        this.palette = palette
        focusAction = focus
        number.text = row.primary.number?.toString().orEmpty()
        name.text = row.primary.shownName + if (row.variants.size > 1) "\n${row.variants.size} qualités" else ""
        number.setTextColor(palette.secondaryText)
        name.setTextColor(palette.onSurface)
        logo.load(row.primary.logoUrl) { crossfade(false); size(dp(38)) }
        channel.setOnClickListener { select() }
        channel.setOnLongClickListener { longSelect(); true }
        channel.setOnKeyListener { _, key, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT -> exitLeft()
                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) longPressHandled = false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && !longPressHandled) {
                        longPressHandled = true
                        longSelect()
                    }
                    if (event.action == KeyEvent.ACTION_UP && !longPressHandled) select()
                    true
                }
                else -> false
            }
        }
        paintChannel()

        if (boundKey != row.key || boundProgrammes !== row.programmes || boundWindow != windowStart) {
            boundKey = row.key
            boundProgrammes = row.programmes
            boundWindow = windowStart
            rebuildProgrammes(row, windowStart, palette, focus, onProgramme)
        } else {
            stripContent.children.filterIsInstance<ProgrammeCell>().forEach { it.refreshPalette(palette) }
        }
        if (strip.scrollX != scrollX) strip.post { strip.scrollTo(scrollX, 0) }
    }

    private fun rebuildProgrammes(
        row: ChannelsViewModel.Row,
        windowStart: Long,
        palette: NativeGuidePalette,
        focus: () -> Unit,
        onProgramme: (Programme) -> Unit,
    ) {
        stripContent.removeAllViews()
        val programmes = row.programmes
        if (programmes.isEmpty()) {
            val empty = TextView(context).apply {
                text = "Aucune information"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                setTextColor(palette.secondaryText)
                background = rounded(palette.surface, 6)
            }
            stripContent.addView(empty, LayoutParams(dp(GUIDE_WINDOW_MINUTES * MINUTE_WIDTH_DP), LayoutParams.MATCH_PARENT))
            return
        }
        var cursor = windowStart
        programmes.forEach { programme ->
            val start = programme.startUtcMillis.coerceAtLeast(windowStart)
            val gapMinutes = ((start - cursor) / 60_000L).coerceAtLeast(0).toInt()
            if (gapMinutes > 0) stripContent.addView(space(dp(gapMinutes * MINUTE_WIDTH_DP)))
            val durationMinutes = ((programme.endUtcMillis - start) / 60_000L).coerceAtLeast(1).toInt()
            val cell = ProgrammeCell(context).apply {
                bind(programme, palette, focus) { onProgramme(programme) }
            }
            stripContent.addView(cell, LayoutParams(dp(maxOf(durationMinutes * MINUTE_WIDTH_DP, MIN_PROGRAMME_WIDTH_DP)), LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(3)
            })
            cursor = programme.endUtcMillis
        }
        val usedMinutes = ((cursor - windowStart) / 60_000L).coerceAtLeast(0).toInt()
        if (usedMinutes < GUIDE_WINDOW_MINUTES) {
            stripContent.addView(space(dp((GUIDE_WINDOW_MINUTES - usedMinutes) * MINUTE_WIDTH_DP)))
        }
    }

    private fun paintChannel() {
        channel.background = rounded(
            when { channel.hasFocus() -> palette.primary; selected -> palette.selected; else -> palette.surface },
            8,
        )
    }

    private fun space(width: Int) = View(context).apply { layoutParams = LayoutParams(width, LayoutParams.MATCH_PARENT) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

private class ProgrammeCell(context: Context) : TextView(context) {
    private var programme: Programme? = null
    private var palette = DEFAULT_GUIDE_PALETTE
    private var isNow = false

    init {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
        setPadding(dp(8), 0, dp(8), 0)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, _ -> paint() }
    }

    fun bind(programme: Programme, palette: NativeGuidePalette, focus: () -> Unit, click: () -> Unit) {
        this.programme = programme
        this.palette = palette
        isNow = System.currentTimeMillis() in programme.startUtcMillis until programme.endUtcMillis
        text = programme.title
        setOnClickListener { click() }
        setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) click()
                true
            } else {
                false
            }
        }
        setOnFocusChangeListener { _, focused -> if (focused) focus(); paint() }
        paint()
    }

    fun refreshPalette(palette: NativeGuidePalette) { this.palette = palette; paint() }

    private fun paint() {
        val bg = when { hasFocus() -> palette.primary; isNow -> palette.selected; else -> palette.surface }
        setTextColor(if (hasFocus()) contrastText(palette.primary) else palette.onSurface)
        background = rounded(bg, 6)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

private class GuideHeader(context: Context) : LinearLayout(context) {
    val strip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
    }
    private val content = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var boundStart = Long.MIN_VALUE
    private var boundPalette: NativeGuidePalette? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        orientation = HORIZONTAL
        addView(View(context), LayoutParams(dp(CHANNEL_WIDTH_DP + 3), LayoutParams.MATCH_PARENT))
        strip.addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(strip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun bind(windowStart: Long, palette: NativeGuidePalette) {
        if (boundStart == windowStart && boundPalette == palette) return
        boundStart = windowStart
        boundPalette = palette
        content.removeAllViews()
        repeat(48) { index ->
            val label = TextView(context).apply {
                text = clock.format(Date(windowStart + index * 30L * 60_000L))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, 0, 0)
                setTextColor(palette.secondaryText)
                setBackgroundColor(palette.surface)
            }
            content.addView(label, LayoutParams(dp(30 * MINUTE_WIDTH_DP), LayoutParams.MATCH_PARENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp.toFloat()
}

private fun contrastText(color: Int): Int {
    val r = android.graphics.Color.red(color)
    val g = android.graphics.Color.green(color)
    val b = android.graphics.Color.blue(color)
    return if (r * 299 + g * 587 + b * 114 > 150_000) android.graphics.Color.BLACK else android.graphics.Color.WHITE
}

private val DEFAULT_GUIDE_PALETTE = NativeGuidePalette(
    0xFF191919.toInt(), 0xFFFFFFFF.toInt(), 0xFFBBBBBB.toInt(), 0xFFE00026.toInt(), 0xFF4A101A.toInt(),
)
private const val STRIP_TAG = "native-guide-strip"
private const val CHANNEL_WIDTH_DP = 220
private const val MINUTE_WIDTH_DP = 4
private const val MIN_PROGRAMME_WIDTH_DP = 72
private const val GUIDE_WINDOW_MINUTES = 24 * 60
