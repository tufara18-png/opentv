package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView

class NativeMainMenuView(context: Context) : LinearLayout(context) {
    data class Item(val id: String, val label: String, val action: () -> Unit)

    private val grid = VerticalGridView(context)
    private val adapter = MenuAdapter()

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(18), dp(24), dp(18), dp(18))
        setBackgroundColor(0xF20B0F19.toInt())

        addView(TextView(context).apply {
            text = "TufaraTV"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        grid.setNumColumns(1)
        grid.adapter = adapter
        grid.itemAnimator = null
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun submit(items: List<Item>, selectedId: String = "live") {
        adapter.submit(items, selectedId)
        val index = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        grid.scrollToPosition(index)
        grid.post {
            grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: grid.post { grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private class MenuAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<Item> = emptyList()
        private var selectedId = "live"

        fun submit(items: List<Item>, selectedId: String) {
            this.items = items
            this.selectedId = selectedId
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Row(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.row.bind(item.label, item.id == selectedId, item.action)
        }
    }

    private class Holder(val row: Row) : RecyclerView.ViewHolder(row)

    private class Row(context: Context) : TextView(context) {
        private var selected = false

        init {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnFocusChangeListener { _, focused ->
                animate()
                    .scaleX(if (focused) 1.025f else 1f)
                    .scaleY(if (focused) 1.025f else 1f)
                    .setDuration(120)
                    .start()
                repaint()
            }
        }

        fun bind(label: String, selected: Boolean, action: () -> Unit) {
            text = label
            this.selected = selected
            setOnClickListener { action() }
            repaint()
        }

        private fun repaint() {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(
                    when {
                        hasFocus() -> 0xFF2563EB.toInt()
                        selected -> 0x552563EB
                        else -> Color.TRANSPARENT
                    },
                )
            }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
