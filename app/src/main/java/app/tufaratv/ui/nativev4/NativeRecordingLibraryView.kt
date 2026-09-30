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
import app.tufaratv.data.model.Recording
import app.tufaratv.data.model.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NativeRecordingLibraryView(
    context: Context,
    private val scope: CoroutineScope,
    private val controller: NativeTvController,
    private val onPlay: (Recording) -> Unit,
) : LinearLayout(context) {
    private val status = TextView(context)
    private val grid = VerticalGridView(context)
    private val adapter = RecordingAdapter(onPlay)

    init {
        orientation = VERTICAL
        setPadding(dp(34), dp(28), dp(34), dp(22))
        setBackgroundColor(Color.rgb(8, 12, 20))

        addView(TextView(context).apply {
            text = "Enregistrements"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        status.apply {
            text = "Chargement…"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        grid.setNumColumns(1)
        grid.adapter = adapter
        grid.itemAnimator = null
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        status.text = "Chargement…"
        scope.launch {
            val items = controller.recordings()
                .sortedByDescending { maxOf(it.startedAtMillis, it.scheduledStartMillis) }
            adapter.submit(items)
            status.text = if (items.isEmpty()) "Aucun enregistrement." else "${items.size} enregistrement(s)"
            if (items.isNotEmpty()) grid.post {
                grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private class RecordingAdapter(
        private val onPlay: (Recording) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {
        private var items: List<Recording> = emptyList()
        init { setHasStableIds(true) }

        fun submit(value: List<Recording>) {
            items = value
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].id
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Row(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.row.bind(items[position], onPlay)
        }
    }

    private class Holder(val row: Row) : RecyclerView.ViewHolder(row)

    private class Row(context: Context) : LinearLayout(context) {
        private val title = TextView(context)
        private val meta = TextView(context)
        private val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(13), dp(18), dp(13))
            title.apply {
                setTextColor(Color.WHITE)
                textSize = 17f
                maxLines = 1
            }
            meta.apply {
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
                maxLines = 1
            }
            addView(title)
            addView(meta)
            setOnFocusChangeListener { _, focused ->
                animate().scaleX(if (focused) 1.015f else 1f)
                    .scaleY(if (focused) 1.015f else 1f)
                    .setDuration(120)
                    .start()
                background = rounded(if (focused) 0xFF1E3A8A.toInt() else Color.TRANSPARENT)
            }
        }

        fun bind(recording: Recording, onPlay: (Recording) -> Unit) {
            title.text = recording.title
            val stamp = maxOf(recording.startedAtMillis, recording.scheduledStartMillis)
            val state = when (recording.status) {
                RecordingStatus.SCHEDULED -> "Planifié"
                RecordingStatus.RECORDING -> "En cours"
                RecordingStatus.COMPLETED -> "Terminé"
                RecordingStatus.FAILED -> "Échec"
            }
            meta.text = listOfNotNull(
                state,
                recording.channelName.takeIf { it.isNotBlank() },
                stamp.takeIf { it > 0 }?.let { fmt.format(Date(it)) },
            ).joinToString(" · ")

            isEnabled = recording.status == RecordingStatus.COMPLETED ||
                recording.status == RecordingStatus.RECORDING
            alpha = if (isEnabled) 1f else .55f
            setOnClickListener {
                if (isEnabled) onPlay(recording)
            }
        }

        private fun rounded(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(7).toFloat()
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
