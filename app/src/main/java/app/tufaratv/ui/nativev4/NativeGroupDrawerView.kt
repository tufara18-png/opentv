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
import app.tufaratv.data.model.Category

class NativeGroupDrawerView(context: Context) : LinearLayout(context) {
    private val grid = VerticalGridView(context)
    private val adapter = GroupAdapter()

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(18), dp(22), dp(18), dp(18))
        setBackgroundColor(0xF20B0F19.toInt())

        addView(TextView(context).apply {
            text = "Groupes"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        grid.setNumColumns(1)
        grid.adapter = adapter
        grid.itemAnimator = null
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun submit(groups: List<Category>, selected: Category?, onSelect: (Category) -> Unit) {
        adapter.submit(groups, selected?.id, onSelect)
        val index = groups.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        grid.scrollToPosition(index)
        grid.post {
            grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: grid.post { grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private class GroupAdapter : RecyclerView.Adapter<Holder>() {
        private var groups: List<Category> = emptyList()
        private var selectedId: String? = null
        private var onSelect: (Category) -> Unit = {}

        fun submit(groups: List<Category>, selectedId: String?, onSelect: (Category) -> Unit) {
            this.groups = groups
            this.selectedId = selectedId
            this.onSelect = onSelect
            notifyDataSetChanged()
        }

        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Row(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = groups[position]
            holder.row.bind(item.name, item.id == selectedId) { onSelect(item) }
        }
    }

    private class Holder(val row: Row) : RecyclerView.ViewHolder(row)

    private class Row(context: Context) : TextView(context) {
        private var selected = false

        init {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnFocusChangeListener { _, focused ->
                animate().scaleX(if (focused) 1.025f else 1f)
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
                cornerRadius = dp(7).toFloat()
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
