package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.db.TellyChannelRow

class NativeChannelBarView(context: Context) : RecyclerView(context) {
    private val rows = ChannelBarAdapter()

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        adapter = rows
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(10)
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setBackgroundColor(Color.argb(225, 11, 15, 25))
    }

    fun submit(
        channels: List<TellyChannelRow>,
        activeId: Long?,
        onTune: (Long) -> Unit,
    ) {
        rows.submit(channels, activeId, onTune)
        val index = channels.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        scrollToPosition(index)
        post { findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private class ChannelBarAdapter : Adapter<Holder>() {
        private var items: List<TellyChannelRow> = emptyList()
        private var activeId: Long? = null
        private var onTune: (Long) -> Unit = {}
        init { setHasStableIds(true) }

        fun submit(items: List<TellyChannelRow>, activeId: Long?, onTune: (Long) -> Unit) {
            this.items = items
            this.activeId = activeId
            this.onTune = onTune
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int) = items[position].id
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ChannelChip(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.view.bind(item, item.id == activeId) { onTune(item.id) }
        }
    }

    private class Holder(val view: ChannelChip) : ViewHolder(view)

    private class ChannelChip(context: Context) : LinearLayout(context) {
        private val number = TextView(context).apply {
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        }
        private val name = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
        }
        private var active = false

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumWidth = dp(210)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(number)
            addView(name)
            setOnFocusChangeListener { _, _ ->
                animate().scaleX(if (hasFocus()) 1.05f else 1f)
                    .scaleY(if (hasFocus()) 1.05f else 1f)
                    .setDuration(150).start()
                repaint()
            }
        }

        fun bind(row: TellyChannelRow, active: Boolean, onClick: () -> Unit) {
            this.active = active
            number.text = row.number?.toString().orEmpty()
            name.text = row.customName?.takeIf { it.isNotBlank() } ?: row.displayName
            setOnClickListener { onClick() }
            repaint()
        }

        private fun repaint() {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(
                    when {
                        hasFocus() -> 0xFF3B82F6.toInt()
                        active -> 0x553B82F6
                        else -> 0x00111827
                    },
                )
                setStroke(dp(1), 0x26FFFFFF)
            }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }
}
