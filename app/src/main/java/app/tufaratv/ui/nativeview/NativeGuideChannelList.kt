package app.tufaratv.ui.nativeview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.model.shownName
import app.tufaratv.ui.ChannelsViewModel
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NativeGuideChannelList(context: Context) : RecyclerView(context) {
    private val rowsAdapter = RowsAdapter()
    init {
        layoutManager = LinearLayoutManager(context)
        adapter = rowsAdapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(14)
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun submit(
        rows: List<ChannelsViewModel.Row>,
        selectedKey: Any?,
        palette: NativeGuidePalette,
        onSelect: (ChannelsViewModel.Row) -> Unit,
        onFocus: (ChannelsViewModel.Row) -> Unit,
        onLongSelect: (ChannelsViewModel.Row) -> Unit,
        onExitLeft: () -> Boolean,
    ) = rowsAdapter.submit(rows, selectedKey, palette, onSelect, onFocus, onLongSelect, onExitLeft)
}

data class NativeGuidePalette(
    val surface: Int,
    val onSurface: Int,
    val secondaryText: Int,
    val primary: Int,
    val selected: Int,
)

private class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
    private var rows = emptyList<ChannelsViewModel.Row>()
    private var selectedKey: Any? = null
    private var palette = NativeGuidePalette(0xFF191919.toInt(), Color.WHITE, 0xFFCCCCCC.toInt(), 0xFFE00026.toInt(), 0x665E0010)
    private var select: (ChannelsViewModel.Row) -> Unit = {}
    private var focus: (ChannelsViewModel.Row) -> Unit = {}
    private var longSelect: (ChannelsViewModel.Row) -> Unit = {}
    private var exitLeft: () -> Boolean = { false }
    fun submit(
        rows: List<ChannelsViewModel.Row>, selectedKey: Any?, palette: NativeGuidePalette,
        select: (ChannelsViewModel.Row) -> Unit, focus: (ChannelsViewModel.Row) -> Unit,
        longSelect: (ChannelsViewModel.Row) -> Unit, exitLeft: () -> Boolean,
    ) {
        val rowsChanged = this.rows !== rows && this.rows != rows
        val oldSelected = this.selectedKey
        this.rows = rows; this.selectedKey = selectedKey; this.palette = palette; this.select = select
        this.focus = focus; this.longSelect = longSelect; this.exitLeft = exitLeft
        if (rowsChanged) {
            notifyDataSetChanged()
        } else if (oldSelected != selectedKey) {
            rows.indexOfFirst { it.key == oldSelected }.takeIf { it >= 0 }?.let(::notifyItemChanged)
            rows.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        }
    }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(GuideRow(parent.context))
    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.view.bind(row, row.key == selectedKey, palette, { select(row) }, { focus(row) }, { longSelect(row) }, exitLeft)
    }
}

private class RowHolder(val view: GuideRow) : RecyclerView.ViewHolder(view)

private class GuideRow(context: Context) : LinearLayout(context) {
    private val number = TextView(context)
    private val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val name = TextView(context).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
    private val programme = TextView(context).apply { maxLines = 1 }
    private val next = TextView(context).apply { maxLines = 1 }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    private val text = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(programme, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dpG(3)))
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var selected = false
    private var longPressHandled = false

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true; isLongClickable = true
        setPadding(dpG(12), dpG(8), dpG(12), dpG(8))
        addView(number, LayoutParams(dpG(42), LayoutParams.WRAP_CONTENT))
        addView(logo, LayoutParams(dpG(44), dpG(44)))
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpG(12) })
        setOnFocusChangeListener { _, focused -> if (focused) focusAction?.invoke(); paint() }
    }
    private var focusAction: (() -> Unit)? = null

    fun bind(
        row: ChannelsViewModel.Row, selected: Boolean, palette: NativeGuidePalette, select: () -> Unit, focus: () -> Unit,
        longSelect: () -> Unit, exitLeft: () -> Boolean,
    ) {
        this.selected = selected; focusAction = focus
        number.text = row.primary.number?.toString().orEmpty()
        name.text = row.primary.shownName + if (row.variants.size > 1) "  · ${row.variants.size} qualités" else ""
        programme.text = row.now?.let { "${clock.format(Date(it.startUtcMillis))}  ${it.title}" } ?: "Aucune information"
        next.text = row.next?.let { "Ensuite ${clock.format(Date(it.startUtcMillis))}  ${it.title}" }.orEmpty()
        next.visibility = if (row.next == null) GONE else VISIBLE
        progress.visibility = if (row.now == null) GONE else VISIBLE
        progress.progress = ((row.now?.progressAt(System.currentTimeMillis()) ?: 0f) * 1000).toInt()
        name.setTextColor(palette.onSurface); number.setTextColor(palette.secondaryText)
        programme.setTextColor(palette.secondaryText); next.setTextColor(palette.secondaryText)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        rowPalette = palette
        logo.load(row.primary.logoUrl) { crossfade(false); size(dpG(44)) }
        setOnClickListener { select() }; setOnLongClickListener { longSelect(); true }
        setOnKeyListener { _, key, event ->
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
        paint()
    }
    private var rowPalette = NativeGuidePalette(0xFF191919.toInt(), Color.WHITE, 0xFFCCCCCC.toInt(), 0xFFE00026.toInt(), 0x665E0010)
    private fun paint() {
        background = GradientDrawable().apply {
            cornerRadius = dpG(8).toFloat()
            setColor(when { hasFocus() -> rowPalette.primary; selected -> rowPalette.selected; else -> rowPalette.surface })
        }
    }
    private fun dpG(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
