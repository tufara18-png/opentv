package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import app.tufaratv.data.model.StreamKind
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Native Movies / Series browser. Category changes filter the current in-memory snapshot only;
 * there is no provider or Room request in the D-pad navigation path.
 */
class NativeVodBrowserView(
    context: Context,
    private val scope: CoroutineScope,
    private val controller: NativeTvController,
    private val onPlayStarted: () -> Unit,
) : LinearLayout(context) {
    enum class Mode { MOVIES, SERIES }

    private data class Group(val key: String?, val label: String, val count: Int)

    private val title = TextView(context)
    private val status = TextView(context)
    private val content = LinearLayout(context)
    private val groups = VerticalGridView(context)
    private val groupAdapter = GroupAdapter(::selectGroup)
    private val grid = VerticalGridView(context)
    private val adapter = VodAdapter(
        onMovie = { controller.playMovie(it); onPlayStarted() },
        onSeries = ::openSeries,
        onEpisode = { controller.playEpisode(it.id); onPlayStarted() },
    )

    private var mode = Mode.MOVIES
    private var activeSeries: Series? = null
    private var selectedGroup: String? = null
    private var allItems: List<VodItem> = emptyList()
    private var categoryLabels: Map<String, String> = emptyMap()
    private var loading = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(8, 12, 20))
        setPadding(dp(28), dp(22), dp(28), dp(20))

        title.apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        status.apply {
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        content.orientation = HORIZONTAL
        content.gravity = Gravity.TOP

        groups.setNumColumns(1)
        groups.adapter = groupAdapter
        groups.itemAnimator = null
        groups.setHasFixedSize(true)
        content.addView(groups, LayoutParams(dp(270), LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(18)
        })

        grid.setNumColumns(6)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(18)
        content.addView(grid, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun show(mode: Mode) {
        this.mode = mode
        activeSeries = null
        selectedGroup = null
        allItems = emptyList()
        categoryLabels = emptyMap()
        loading = true
        title.text = if (mode == Mode.MOVIES) "Films" else "Séries"
        groups.visibility = VISIBLE
        grid.visibility = INVISIBLE
        status.text = "Chargement du catalogue…"

        scope.launch {
            categoryLabels = controller.vodCategoryLabels(
                if (mode == Mode.MOVIES) StreamKind.MOVIE else StreamKind.SERIES,
            )
            post { if (allItems.isNotEmpty()) rebuildGroupsAndGrid(requestGroupFocus = false) }
        }

        if (mode == Mode.MOVIES) {
            controller.loadMovies(
                onProgress = { count ->
                    post {
                        loading = true
                        status.text = "Mise à jour… $count films"
                    }
                },
                onUpdate = { movies, stillLoading ->
                    post {
                        loading = stillLoading
                        setItems(movies.map(VodItem::MovieItem))
                    }
                },
            )
        } else {
            controller.loadSeries(
                onProgress = { count ->
                    post {
                        loading = true
                        status.text = "Mise à jour… $count séries"
                    }
                },
                onUpdate = { series, stillLoading ->
                    post {
                        loading = stillLoading
                        setItems(series.map(VodItem::SeriesItem))
                    }
                },
            )
        }
    }

    fun showSeriesDetail(series: Series) = openSeries(series)

    fun handleBack(): Boolean {
        if (activeSeries != null) {
            activeSeries = null
            show(Mode.SERIES)
            return true
        }
        if (selectedGroup != null) {
            selectedGroup = null
            rebuildGroupsAndGrid(requestGroupFocus = true)
            return true
        }
        return false
    }

    private fun setItems(items: List<VodItem>) {
        allItems = items
        if (items.isEmpty()) {
            adapter.submit(emptyList())
            grid.visibility = INVISIBLE
            status.text = when {
                loading -> "Chargement du catalogue…"
                mode == Mode.MOVIES -> "Aucun film reçu du fournisseur."
                else -> "Aucune série reçue du fournisseur."
            }
            return
        }
        rebuildGroupsAndGrid(requestGroupFocus = !groups.hasFocus() && !grid.hasFocus())
    }

    private fun rebuildGroupsAndGrid(requestGroupFocus: Boolean) {
        val counts = LinkedHashMap<String, Int>()
        for (item in allItems) {
            val key = item.categoryKey() ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }

        val groupRows = buildList {
            add(Group(null, "Tout", allItems.size))
            counts.entries
                .map { (key, count) ->
                    Group(
                        key = key,
                        label = categoryLabels[key] ?: key.substringAfter('|'),
                        count = count,
                    )
                }
                .sortedBy { it.label.lowercase() }
                .forEach(::add)
        }
        groupAdapter.submit(groupRows, selectedGroup)

        val filtered = selectedGroup?.let { key ->
            allItems.filter { it.categoryKey() == key }
        } ?: allItems

        adapter.submit(filtered)
        grid.visibility = VISIBLE
        status.text = when {
            loading -> "Mise à jour du catalogue…"
            selectedGroup == null ->
                "${allItems.size} ${if (mode == Mode.MOVIES) "films" else "séries"}"
            else -> "${filtered.size} titre(s)"
        }

        if (requestGroupFocus) {
            val index = groupRows.indexOfFirst { it.key == selectedGroup }.coerceAtLeast(0)
            groups.scrollToPosition(index)
            groups.post {
                groups.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    private fun selectGroup(group: Group) {
        selectedGroup = group.key
        rebuildGroupsAndGrid(requestGroupFocus = false)
        grid.scrollToPosition(0)
        grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun openSeries(series: Series) {
        activeSeries = series
        selectedGroup = null
        title.text = series.name
        groups.visibility = GONE
        grid.visibility = INVISIBLE
        status.text = "Chargement des épisodes…"

        scope.launch {
            val episodes = controller.episodes(series).map(VodItem::EpisodeItem)
            post {
                allItems = episodes
                adapter.submit(episodes)
                grid.visibility = if (episodes.isEmpty()) INVISIBLE else VISIBLE
                status.text = if (episodes.isEmpty()) {
                    "Aucun épisode reçu du fournisseur."
                } else {
                    "${episodes.size} épisode(s)"
                }
                if (episodes.isNotEmpty()) {
                    grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            }
        }
    }

    private sealed interface VodItem {
        fun categoryKey(): String?

        data class MovieItem(val value: Movie) : VodItem {
            override fun categoryKey(): String? =
                value.categoryId?.let { "${value.sourceId}|$it" }
        }

        data class SeriesItem(val value: Series) : VodItem {
            override fun categoryKey(): String? =
                value.categoryId?.let { "${value.sourceId}|$it" }
        }

        data class EpisodeItem(val value: Episode) : VodItem {
            override fun categoryKey(): String? = null
        }
    }

    private class GroupAdapter(
        private val select: (Group) -> Unit,
    ) : RecyclerView.Adapter<GroupHolder>() {
        private var groups: List<Group> = emptyList()
        private var selectedKey: String? = null

        fun submit(groups: List<Group>, selectedKey: String?) {
            this.groups = groups
            this.selectedKey = selectedKey
            notifyDataSetChanged()
        }

        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            GroupHolder(GroupRow(parent.context))

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            val group = groups[position]
            holder.row.bind(group, group.key == selectedKey) { select(group) }
        }
    }

    private class GroupHolder(val row: GroupRow) : RecyclerView.ViewHolder(row)

    private class GroupRow(context: Context) : LinearLayout(context) {
        private val label = TextView(context)
        private val count = TextView(context)
        private var selected = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(11), dp(12), dp(11))

            label.apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
            }
            count.apply {
                setTextColor(0xFF94A3B8.toInt())
                textSize = 12f
                gravity = Gravity.END
            }

            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(count, LayoutParams(dp(56), LayoutParams.WRAP_CONTENT))

            setOnFocusChangeListener { _, focused ->
                animate()
                    .scaleX(if (focused) 1.025f else 1f)
                    .scaleY(if (focused) 1.025f else 1f)
                    .setDuration(120)
                    .start()
                repaint()
            }
        }

        fun bind(group: Group, selected: Boolean, action: () -> Unit) {
            label.text = group.label
            count.text = group.count.toString()
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
            when (val item = items[position]) {
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
            maxLines = 1
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

            setOnFocusChangeListener { _, focused ->
                animate()
                    .scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f)
                    .setDuration(150)
                    .start()
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
                setColor(if (hasFocus()) 0xFF1E40AF.toInt() else Color.TRANSPARENT)
                setStroke(dp(1), if (hasFocus()) 0xFF60A5FA.toInt() else 0x1FFFFFFF)
            }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
