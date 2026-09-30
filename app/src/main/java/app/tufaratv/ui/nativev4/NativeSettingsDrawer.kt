package app.tufaratv.ui.nativev4

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tufaratv.core.ServiceLocator

class NativeSettingsDrawer(
    context: Context,
    private val controller: NativeTvController,
    private val onGuideReload: () -> Unit,
) : LinearLayout(context) {
    private val graph = ServiceLocator.get(context)
    private val settings = graph.settings
    private val list = RecyclerView(context)
    private val adapter = SettingsAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(245, 11, 15, 25))
        setPadding(dp(24), dp(28), dp(24), dp(28))

        addView(TextView(context).apply {
            text = "Réglages"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
    }

    fun rebuild() {
        val quality = settings.qualityPreferenceOrder.value.joinToString(" › ")
        adapter.submit(
            listOf(
                Entry("Déduplication des chaînes", yesNo(settings.deduplicateChannels.value)) {
                    settings.setDeduplicateChannels(!settings.deduplicateChannels.value)
                    onGuideReload()
                    rebuild()
                },
                Entry("Qualité préférée", quality) {
                    val current = settings.qualityPreferenceOrder.value
                    val next = when {
                        current.firstOrNull()?.startsWith("4K", true) == true ->
                            app.tufaratv.core.AppSettings.DEFAULT_QUALITY_ORDER
                        else -> listOf(
                            "4K", "UHD", "FHD++++", "FHD+++", "FHD++", "FHD+", "FHD",
                            "1080P", "HD", "720P", "inconnu", "SD", "LOW",
                        )
                    }
                    settings.setQualityPreferenceOrder(next)
                    rebuild()
                },
                Entry("Live", yesNo(settings.liveEnabled.value)) {
                    settings.setLiveEnabled(!settings.liveEnabled.value); rebuild()
                },
                Entry("Films", yesNo(settings.moviesEnabled.value)) {
                    settings.setMoviesEnabled(!settings.moviesEnabled.value); rebuild()
                },
                Entry("Séries", yesNo(settings.seriesEnabled.value)) {
                    settings.setSeriesEnabled(!settings.seriesEnabled.value); rebuild()
                },
                Entry("Son dans l’aperçu du guide", yesNo(settings.guidePreviewSound.value)) {
                    settings.setGuidePreviewSound(!settings.guidePreviewSound.value); rebuild()
                },
                Entry("Sous-titres", yesNo(settings.subtitlesEnabled.value)) {
                    controller.setSubtitles(!settings.subtitlesEnabled.value); rebuild()
                },
                Entry("Format vidéo", resizeName(settings.playerResizeMode.value)) {
                    val next = when (settings.playerResizeMode.value) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    settings.setPlayerResizeMode(next)
                    rebuild()
                },
                Entry("Pause / rewind Live", yesNo(settings.livePauseEnabled.value)) {
                    settings.setLivePauseEnabled(!settings.livePauseEnabled.value); rebuild()
                },
                Entry("Reprendre la dernière chaîne", yesNo(settings.resumeLastChannel.value)) {
                    settings.setResumeLastChannel(!settings.resumeLastChannel.value); rebuild()
                },
            ),
        )
        post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun yesNo(v: Boolean) = if (v) "Activé" else "Désactivé"

    private fun resizeName(mode: Int) = when (mode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Remplir"
        else -> "Ajuster"
    }

    private data class Entry(val title: String, val value: String, val action: () -> Unit)

    private class SettingsAdapter : RecyclerView.Adapter<SettingsHolder>() {
        private var items: List<Entry> = emptyList()
        fun submit(items: List<Entry>) { this.items = items; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SettingsHolder(SettingsRow(parent.context))
        override fun onBindViewHolder(holder: SettingsHolder, position: Int) =
            holder.row.bind(items[position])
    }

    private class SettingsHolder(val row: SettingsRow) : RecyclerView.ViewHolder(row)

    private class SettingsRow(context: Context) : LinearLayout(context) {
        private val label = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 16f
        }
        private val value = TextView(context).apply {
            setTextColor(0xFF94A3B8.toInt()); textSize = 14f
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(13), dp(18), dp(13))
            addView(label)
            addView(value)
            setOnFocusChangeListener { _, _ ->
                animate().scaleX(if (hasFocus()) 1.02f else 1f)
                    .scaleY(if (hasFocus()) 1.02f else 1f)
                    .setDuration(150).start()
                paint()
            }
        }

        fun bind(entry: Entry) {
            label.text = entry.title
            value.text = entry.value
            setOnClickListener { entry.action() }
            paint()
        }

        private fun paint() {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (hasFocus()) 0xFF1E40AF.toInt() else Color.TRANSPARENT)
            }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
