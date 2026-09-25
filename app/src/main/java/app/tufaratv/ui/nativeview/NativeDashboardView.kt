/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui.nativeview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

data class NativeDashboardPalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val text: Int,
    val secondaryText: Int,
    val primary: Int,
)

data class NativeDashboardItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val badge: String? = null,
    val favourite: Boolean = false,
    val progress: Float? = null,
    val landscape: Boolean = false,
    val fitImage: Boolean = false,
    val compactLive: Boolean = false,
    val onClick: () -> Unit,
)

data class NativeDashboardSection(
    val id: String,
    val title: String,
    val items: List<NativeDashboardItem>,
)

data class NativeDashboardHero(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val onClick: () -> Unit,
)

/**
 * RecyclerView-based ten-foot dashboard.
 *
 * Compose remains the app shell while the expensive nested poster surface uses native recycled
 * views. A focus move updates two view properties instead of invalidating the full Compose scene.
 */
class NativeDashboardView(context: Context) : FrameLayout(context) {
    private val pool = RecyclerView.RecycledViewPool()
    private val adapter = DashboardAdapter(pool)
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        setHasFixedSize(false)
        setItemViewCacheSize(5)
        itemAnimator = null
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        this.adapter = this@NativeDashboardView.adapter
    }

    init {
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun submit(
        hero: NativeDashboardHero?,
        sections: List<NativeDashboardSection>,
        palette: NativeDashboardPalette,
    ) {
        setBackgroundColor(palette.background)
        adapter.submit(hero, sections, palette)
    }

    private class DashboardAdapter(
        private val pool: RecyclerView.RecycledViewPool,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var hero: NativeDashboardHero? = null
        private var sections: List<NativeDashboardSection> = emptyList()
        private var palette = NativeDashboardPalette(Color.BLACK, Color.DKGRAY, Color.DKGRAY, Color.WHITE, Color.LTGRAY, Color.RED)
        private var signature: Int = 0

        init { setHasStableIds(true) }

        fun submit(
            hero: NativeDashboardHero?,
            sections: List<NativeDashboardSection>,
            palette: NativeDashboardPalette,
        ) {
            val nextSignature = 31 * (hero?.id?.hashCode() ?: 0) +
                sections.fold(1) { total, section ->
                    31 * total + section.id.hashCode() + section.items.fold(1) { n, item ->
                        31 * n + item.id.hashCode() + item.title.hashCode() + (item.subtitle?.hashCode() ?: 0)
                    }
                } + palette.hashCode()
            this.hero = hero
            this.sections = sections
            this.palette = palette
            if (signature != nextSignature) {
                signature = nextSignature
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = sections.size + if (hero == null) 0 else 1
        override fun getItemViewType(position: Int): Int = if (hero != null && position == 0) HERO else SECTION
        override fun getItemId(position: Int): Long {
            val key = if (getItemViewType(position) == HERO) hero?.id else sectionAt(position).id
            return key.hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == HERO) HeroHolder(HeroView(parent.context))
            else SectionHolder(SectionView(parent.context, pool))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeroHolder -> hero?.let { holder.view.bind(it, palette) }
                is SectionHolder -> holder.view.bind(sectionAt(position), palette)
            }
        }

        private fun sectionAt(position: Int): NativeDashboardSection =
            sections[position - if (hero == null) 0 else 1]

        private class HeroHolder(val view: HeroView) : RecyclerView.ViewHolder(view)
        private class SectionHolder(val view: SectionView) : RecyclerView.ViewHolder(view)

        private companion object { const val HERO = 1; const val SECTION = 2 }
    }

    private class HeroView(context: Context) : FrameLayout(context) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val scrim = View(context)
        private val title = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            maxLines = 2
        }
        private val subtitle = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            maxLines = 2
        }
        private var primary = Color.RED
        private var action: (() -> Unit)? = null

        init {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(320))
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(40))
                addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(subtitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8)
                })
            }
            addView(text, LayoutParams((context.resources.displayMetrics.widthPixels * .55f).toInt(), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            isFocusable = true
            isClickable = true
            setOnClickListener { action?.invoke() }
            setOnFocusChangeListener { _, focused -> updateFocus(focused) }
        }

        fun bind(model: NativeDashboardHero, palette: NativeDashboardPalette) {
            title.text = model.title
            title.setTextColor(Color.WHITE)
            subtitle.text = model.subtitle.orEmpty()
            subtitle.setTextColor(0xFFD8D8D8.toInt())
            scrim.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(palette.background, 0x22000000, 0x44000000),
            )
            image.load(model.imageUrl) { crossfade(false) }
            primary = palette.primary
            action = model.onClick
            foreground = ringDrawable(palette.primary, hasFocus())
        }

        private fun updateFocus(focused: Boolean) {
            animate().cancel()
            animate().scaleX(if (focused) 1.012f else 1f).scaleY(if (focused) 1.012f else 1f).setDuration(80).start()
            foreground = ringDrawable(primary, focused)
        }
    }

    private class SectionView(context: Context, pool: RecyclerView.RecycledViewPool) : LinearLayout(context) {
        private val heading = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(context.dp(20), 0, context.dp(20), context.dp(8))
        }
        private val cards = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            setRecycledViewPool(pool)
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            itemAnimator = null
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(14))
            clipToPadding = false
        }
        private val cardAdapter = CardAdapter()

        init {
            orientation = VERTICAL
            layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(10)
            }
            cards.adapter = cardAdapter
            addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(cards, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun bind(section: NativeDashboardSection, palette: NativeDashboardPalette) {
            heading.text = section.title
            heading.setTextColor(palette.text)
            cardAdapter.submit(section.items, palette)
        }
    }

    private class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        private var items: List<NativeDashboardItem> = emptyList()
        private var palette = NativeDashboardPalette(Color.BLACK, Color.DKGRAY, Color.DKGRAY, Color.WHITE, Color.LTGRAY, Color.RED)

        init { setHasStableIds(true) }
        fun submit(items: List<NativeDashboardItem>, palette: NativeDashboardPalette) {
            this.items = items
            this.palette = palette
            notifyDataSetChanged()
        }
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder = CardHolder(CardView(parent.context))
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.view.bind(items[position], palette)
    }

    private class CardHolder(val view: CardView) : RecyclerView.ViewHolder(view)

    private class CardView(context: Context) : LinearLayout(context) {
        private val artFrame = FrameLayout(context)
        private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badge = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
        }
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        private val title = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val subtitle = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        private var primary = Color.RED
        private var focusRing = ringDrawable(primary, true)
        private var emptyRing = ringDrawable(primary, false)
        private var action: (() -> Unit)? = null

        init {
            orientation = VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(context.dp(4))
            artFrame.addView(art, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            artFrame.addView(badge, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(context.dp(6), 0, 0, context.dp(6))
            })
            artFrame.addView(progress, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(4), Gravity.BOTTOM))
            addView(artFrame)
            addView(textColumn)
            setOnClickListener { action?.invoke() }
            setOnFocusChangeListener { _, focused ->
                foreground = if (focused) focusRing else emptyRing
            }
        }

        fun bind(model: NativeDashboardItem, palette: NativeDashboardPalette) {
            primary = palette.primary
            val width = context.dp(when {
                model.compactLive -> 240
                model.landscape -> 190
                else -> 154
            })
            val artHeight = context.dp(when {
                model.compactLive -> 68
                model.landscape -> 107
                else -> 231
            })
            val height = context.dp(when {
                model.compactLive -> 92
                model.landscape -> 164
                else -> 292
            })
            layoutParams = RecyclerView.LayoutParams(width, height).apply {
                marginEnd = context.dp(10)
            }
            orientation = if (model.compactLive) HORIZONTAL else VERTICAL
            gravity = if (model.compactLive) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            artFrame.layoutParams = if (model.compactLive) {
                LayoutParams(artHeight, artHeight).apply { marginEnd = context.dp(10) }
            } else {
                LayoutParams(LayoutParams.MATCH_PARENT, artHeight)
            }
            textColumn.layoutParams = if (model.compactLive) {
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            } else {
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) }
            }
            artFrame.background = rounded(palette.surfaceVariant, context.dp(9).toFloat())
            artFrame.clipToOutline = true
            art.scaleType = if (model.fitImage) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            art.setPadding(if (model.fitImage) context.dp(10) else 0)
            art.load(model.imageUrl) { crossfade(false); size(width, artHeight) }
            title.text = model.title
            title.setTextColor(palette.text)
            subtitle.text = model.subtitle.orEmpty()
            subtitle.setTextColor(palette.secondaryText)
            subtitle.visibility = if (model.subtitle.isNullOrBlank()) GONE else VISIBLE
            badge.text = listOfNotNull(if (model.favourite) "♥" else null, model.badge).joinToString("  ")
            badge.visibility = if (badge.text.isBlank()) GONE else VISIBLE
            badge.background = rounded(0xB0000000.toInt(), context.dp(5).toFloat())
            progress.visibility = if (model.progress == null) GONE else VISIBLE
            progress.progress = ((model.progress ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
            action = model.onClick
            focusRing = ringDrawable(primary, true)
            emptyRing = ringDrawable(primary, false)
            foreground = if (hasFocus()) focusRing else emptyRing
        }
    }

    private companion object {
        fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

        fun ringDrawable(color: Int, visible: Boolean) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 10f
            if (visible) setStroke(4, color)
        }
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
