package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NativeSearchView(
    context: Context,
    private val scope: CoroutineScope,
    private val controller: NativeTvController,
    private val onChannel: (Long) -> Unit,
    private val onMovie: (Movie) -> Unit,
    private val onSeries: (Series) -> Unit,
) : LinearLayout(context) {
    private val query = EditText(context)
    private val status = TextView(context)
    private val results = VerticalGridView(context)
    private val adapter = SearchAdapter(onChannel, onMovie, onSeries)
    private var searchJob: Job? = null

    init {
        orientation = VERTICAL
        setPadding(dp(34), dp(28), dp(34), dp(22))
        setBackgroundColor(Color.rgb(8, 12, 20))

        addView(TextView(context).apply {
            text = "Recherche"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        query.apply {
            hint = "Chaînes, films, séries…"
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            isFocusable = true
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(0xFF111827.toInt())
            setOnFocusChangeListener { _, focused ->
                background = rounded(if (focused) 0xFF1E3A8A.toInt() else 0xFF111827.toInt())
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    scheduleSearch(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(query, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(12)
        })

        status.apply {
            text = "Tapez au moins 2 caractères."
            setTextColor(0xFF94A3B8.toInt())
            textSize = 14f
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        results.setNumColumns(1)
        results.adapter = adapter
        results.itemAnimator = null
        addView(results, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        post { query.requestFocus() }
    }

    fun focusQuery() = query.requestFocus()

    private fun scheduleSearch(value: String) {
        searchJob?.cancel()
        val q = value.trim()
        if (q.length < 2) {
            adapter.submit(emptyList())
            status.text = "Tapez au moins 2 caractères."
            return
        }
        status.text = "Recherche…"
        searchJob = scope.launch {
            delay(160)
            val channels = controller.searchChannels(q, 80)
            val movies = controller.searchMovies(q, 50)
            val series = controller.searchSeries(q, 50)
            val merged = buildList<ResultItem> {
                channels.forEach { add(ResultItem.ChannelItem(it)) }
                movies.forEach { add(ResultItem.MovieItem(it)) }
                series.forEach { add(ResultItem.SeriesItem(it)) }
            }
            adapter.submit(merged)
            status.text = if (merged.isEmpty()) "Aucun résultat." else "${merged.size} résultat(s)"
            if (merged.isNotEmpty()) {
                results.post { results.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private sealed interface ResultItem {
        data class ChannelItem(val row: TellyChannelRow) : ResultItem
        data class MovieItem(val movie: Movie) : ResultItem
        data class SeriesItem(val series: Series) : ResultItem
    }

    private class SearchAdapter(
        private val onChannel: (Long) -> Unit,
        private val onMovie: (Movie) -> Unit,
        private val onSeries: (Series) -> Unit,
    ) : RecyclerView.Adapter<SearchHolder>() {
        private var items: List<ResultItem> = emptyList()
        init { setHasStableIds(true) }

        fun submit(value: List<ResultItem>) {
            items = value
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int): Long = when (val item = items[position]) {
            is ResultItem.ChannelItem -> item.row.id
            is ResultItem.MovieItem -> 1_000_000_000L + item.movie.id
            is ResultItem.SeriesItem -> 2_000_000_000L + item.series.id
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SearchHolder(SearchRow(parent.context))

        override fun onBindViewHolder(holder: SearchHolder, position: Int) {
            when (val item = items[position]) {
                is ResultItem.ChannelItem -> holder.row.bind(
                    "Chaîne",
                    item.row.customName?.takeIf { it.isNotBlank() } ?: item.row.displayName,
                ) { onChannel(item.row.id) }
                is ResultItem.MovieItem -> holder.row.bind("Film", item.movie.name) { onMovie(item.movie) }
                is ResultItem.SeriesItem -> holder.row.bind("Série", item.series.name) { onSeries(item.series) }
            }
        }
    }

    private class SearchHolder(val row: SearchRow) : RecyclerView.ViewHolder(row)

    private class SearchRow(context: Context) : LinearLayout(context) {
        private val type = TextView(context)
        private val label = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(12), dp(18), dp(12))
            type.apply {
                textSize = 12f
                setTextColor(0xFF60A5FA.toInt())
                gravity = Gravity.CENTER
            }
            label.apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            addView(type, LayoutParams(dp(90), LayoutParams.WRAP_CONTENT))
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            setOnFocusChangeListener { _, focused ->
                animate().scaleX(if (focused) 1.015f else 1f)
                    .scaleY(if (focused) 1.015f else 1f)
                    .setDuration(120)
                    .start()
                background = rounded(if (focused) 0xFF1E3A8A.toInt() else Color.TRANSPARENT)
            }
        }

        fun bind(kind: String, name: String, action: () -> Unit) {
            type.text = kind
            label.text = name
            setOnClickListener { action() }
        }

        private fun rounded(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(7).toFloat()
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
