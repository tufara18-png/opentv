package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NativeVodBrowserView(
    context: Context,
    private val scope: CoroutineScope,
    private val controller: NativeTvController,
    private val onPlayStarted: () -> Unit,
) : LinearLayout(context) {
    enum class Mode { MOVIES, SERIES }

    private val title = TextView(context)
    private val status = TextView(context)
    private val grid = RecyclerView(context)
    private val adapter = VodAdapter(
        onMovie = { controller.playMovie(it); onPlayStarted() },
        onSeries = ::openSeries,
        onEpisode = { controller.playEpisode(it.id); onPlayStarted() },
    )
    private var mode = Mode.MOVIES
    private var activeSeries: Series? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(11, 15, 25))
        setPadding(dp(34), dp(26), dp(34), dp(22))

        title.apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        status.apply {
            textSize = 16f
            setTextColor(0xFF94A3B8.toInt())
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        grid.layoutManager = GridLayoutManager(context, 6)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(18)
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun show(mode: Mode) {
        this.mode = mode
        activeSeries = null
        title.text = if (mode == Mode.MOVIES) "Films" else "Séries"
        showLoading("Chargement…")
        scope.launch {
            val items = if (mode == Mode.MOVIES) {
                controller.movies { count ->
                    post { showLoading("Chargement… $count films") }
                }.map(VodItem::MovieItem)
            } else {
                controller.series { count ->
                    post { showLoading("Chargement… $count séries") }
                }.map(VodItem::SeriesItem)
            }
            post {
                adapter.submit(items)
                if (items.isEmpty()) {
                    grid.visibility = GONE
                    status.visibility = VISIBLE
                    status.text = if (mode == Mode.MOVIES) {
                        "Aucun film reçu du fournisseur."
                    } else {
                        "Aucune série reçue du fournisseur."
                    }
                } else {
                    status.visibility = GONE
                    grid.visibility = VISIBLE
                    grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            }
        }
    }

    fun handleBack(): Boolean {
        val series = activeSeries ?: return false
        activeSeries = null
        show(Mode.SERIES)
        return true
    }

    private fun openSeries(series: Series) {
        activeSeries = series
        title.text = series.name
        showLoading("Chargement des épisodes…")
        scope.launch {
            val episodes = controller.episodes(series).map(VodItem::EpisodeItem)
            post {
                adapter.submit(episodes)
                if (episodes.isEmpty()) {
                    grid.visibility = GONE
                    status.visibility = VISIBLE
                    status.text = "Aucun épisode reçu du fournisseur."
                } else {
                    status.visibility = GONE
                    grid.visibility = VISIBLE
                    grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            }
        }
    }

    private fun showLoading(message: String) {
        status.text = message
        status.visibility = VISIBLE
        grid.visibility = GONE
    }

    private sealed interface VodItem {
        data class MovieItem(val value: Movie) : VodItem
        data class SeriesItem(val value: Series) : VodItem
        data class EpisodeItem(val value: Episode) : VodItem
    }

    private class VodAdapter(
        private val onMovie: (Movie) -> Unit,
        private val onSeries: (Series) -> Unit,
        private val onEpisode: (Episode) -> Unit,
    ) : RecyclerView.Adapter<VodHolder>() {
        private var items: List<VodItem> = emptyList()
        init { setHasStableIds(true) }

        fun submit(items: List<VodItem>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int): Long = when (val item = items[position]) {
            is VodItem.MovieItem -> item.value.id
            is VodItem.SeriesItem -> 1_000_000_000L + item.value.id
            is VodItem.EpisodeItem -> 2_000_000_000L + item.value.id
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VodHolder(VodCard(parent.context))

        override fun onBindViewHolder(holder: VodHolder, position: Int) {
            val item = items[position]
            when (item) {
                is VodItem.MovieItem -> holder.card.bind(
                    item.value.name,
                    item.value.posterUrl,
                    item.value.year?.toString().orEmpty(),
                ) { onMovie(item.value) }
                is VodItem.SeriesItem -> holder.card.bind(
                    item.value.name,
                    item.value.posterUrl,
                    item.value.year?.toString().orEmpty(),
                ) { onSeries(item.value) }
                is VodItem.EpisodeItem -> holder.card.bind(
                    "S${item.value.season} · E${item.value.episodeNumber}  ${item.value.title}",
                    item.value.stillUrl,
                    "",
                ) { onEpisode(item.value) }
            }
        }
    }

    private class VodHolder(val card: VodCard) : RecyclerView.ViewHolder(card)

    private class VodCard(context: Context) : LinearLayout(context) {
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        private val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
        }
        private val meta = TextView(context).apply {
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.START
            isFocusable = true
            isClickable = true
            setPadding(dp(7), dp(7), dp(7), dp(10))
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, dp(176)))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(meta)
            setOnFocusChangeListener { _, _ ->
                animate().scaleX(if (hasFocus()) 1.06f else 1f)
                    .scaleY(if (hasFocus()) 1.06f else 1f)
                    .setDuration(150).start()
                repaint()
            }
        }

        fun bind(name: String, art: String?, metadata: String, action: () -> Unit) {
            label.text = name
            meta.text = metadata
            image.load(art) {
                crossfade(false)
                size(dp(240), dp(176))
            }
            setOnClickListener { action() }
            repaint()
        }

        private fun repaint() {
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (hasFocus()) 0xFF1E40AF.toInt() else 0x00111827)
                setStroke(dp(1), if (hasFocus()) 0xFF60A5FA.toInt() else 0x1FFFFFFF)
            }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
