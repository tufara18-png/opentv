package app.tufaratv.ui.nativeview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.model.Programme
import app.tufaratv.player.PlaybackQueue
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native recycled lists used over live video. Focus never recomposes the player scene. */
class NativeChannelListView(context: Context) : RecyclerView(context) {
    private val channelAdapter = ChannelAdapter()
    init {
        layoutManager = LinearLayoutManager(context)
        adapter = channelAdapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(12)
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun submit(items: List<PlaybackQueue.Item>, currentId: Long?, onSelect: (Long) -> Unit) {
        channelAdapter.submit(items, currentId, onSelect)
        val index = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        scrollToPosition(index)
        post {
            findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: post { findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }
}

private class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {
    private var items: List<PlaybackQueue.Item> = emptyList()
    private var currentId: Long? = null
    private var onSelect: (Long) -> Unit = {}
    init { setHasStableIds(true) }
    fun submit(items: List<PlaybackQueue.Item>, currentId: Long?, onSelect: (Long) -> Unit) {
        this.items = items
        this.currentId = currentId
        this.onSelect = onSelect
        notifyDataSetChanged()
    }
    override fun getItemId(position: Int) = items[position].id
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChannelHolder(ChannelRow(parent.context))
    override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
        val item = items[position]
        holder.row.bind(item, item.id == currentId) { onSelect(item.id) }
    }
}

private class ChannelHolder(val row: ChannelRow) : RecyclerView.ViewHolder(row)

private class ChannelRow(context: Context) : LinearLayout(context) {
    private val number = TextView(context)
    private val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val name = TextView(context).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
    private val now = TextView(context).apply { maxLines = 1; setTextColor(0xFFBBBBBB.toInt()) }
    private val texts = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(now, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val star = TextView(context).apply { text = "★"; textSize = 18f }
    private var playing = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(context.dpP(14), context.dpP(8), context.dpP(14), context.dpP(8))
        addView(number, LayoutParams(context.dpP(42), LayoutParams.WRAP_CONTENT))
        addView(logo, LayoutParams(context.dpP(34), context.dpP(34)))
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpP(10) })
        addView(star, LayoutParams(context.dpP(28), LayoutParams.WRAP_CONTENT))
        setOnFocusChangeListener { _, _ -> paint() }
    }

    fun bind(item: PlaybackQueue.Item, playing: Boolean, action: () -> Unit) {
        this.playing = playing
        number.text = item.number?.toString().orEmpty()
        name.text = item.name
        name.setTextColor(Color.WHITE)
        now.text = item.nowTitle.orEmpty()
        now.visibility = if (item.nowTitle.isNullOrBlank()) GONE else VISIBLE
        star.visibility = if (item.favourite) VISIBLE else GONE
        star.setTextColor(0xFFE91E3A.toInt())
        logo.load(item.logoUrl) { crossfade(false); size(context.dpP(34)) }
        setOnClickListener { action() }
        paint()
    }

    private fun paint() {
        background = GradientDrawable().apply {
            setColor(when { hasFocus() -> 0xFFE00026.toInt(); playing -> 0x665E0010; else -> Color.TRANSPARENT })
        }
    }
}

class NativeProgrammeListView(context: Context) : RecyclerView(context) {
    private val programmeAdapter = ProgrammeAdapter()
    init {
        layoutManager = LinearLayoutManager(context)
        adapter = programmeAdapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(12)
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    fun submit(items: List<Programme>, nowMillis: Long, onSelect: (Programme) -> Unit) {
        programmeAdapter.submit(items, nowMillis, onSelect)
        post { findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }
}

private class ProgrammeAdapter : RecyclerView.Adapter<ProgrammeHolder>() {
    private var items: List<Programme> = emptyList()
    private var nowMillis = 0L
    private var onSelect: (Programme) -> Unit = {}
    fun submit(items: List<Programme>, nowMillis: Long, onSelect: (Programme) -> Unit) {
        this.items = items; this.nowMillis = nowMillis; this.onSelect = onSelect; notifyDataSetChanged()
    }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ProgrammeHolder(ProgrammeRow(parent.context))
    override fun onBindViewHolder(holder: ProgrammeHolder, position: Int) {
        val item = items[position]
        holder.row.bind(item, item.startUtcMillis <= nowMillis && item.endUtcMillis > nowMillis) { onSelect(item) }
    }
}

private class ProgrammeHolder(val row: ProgrammeRow) : RecyclerView.ViewHolder(row)

private class ProgrammeRow(context: Context) : LinearLayout(context) {
    private val time = TextView(context).apply { setTextColor(0xFFCCCCCC.toInt()) }
    private val title = TextView(context).apply { setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); maxLines = 2 }
    private var live = false
    private val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        isFocusable = true
        isClickable = true
        setPadding(context.dpP(18), context.dpP(12), context.dpP(18), context.dpP(12))
        addView(time, LayoutParams(context.dpP(66), LayoutParams.WRAP_CONTENT))
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setOnFocusChangeListener { _, _ -> paint() }
    }
    fun bind(item: Programme, live: Boolean, action: () -> Unit) {
        this.live = live
        time.text = format.format(Date(item.startUtcMillis))
        title.text = item.title
        setOnClickListener { action() }
        paint()
    }
    private fun paint() {
        background = GradientDrawable().apply {
            setColor(when { hasFocus() -> 0xFFE00026.toInt(); live -> 0x26FFFFFF; else -> Color.TRANSPARENT })
        }
    }
}

private fun Context.dpP(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
