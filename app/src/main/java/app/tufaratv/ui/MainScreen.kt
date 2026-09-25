/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui

import androidx.activity.compose.BackHandler
import app.tufaratv.R
import app.tufaratv.core.findActivity
import app.tufaratv.core.StatusBus
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.tufaratv.core.AppSettings
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.Movie
import app.tufaratv.data.remote.TmdbMeta
import app.tufaratv.data.remote.TmdbListItem
import app.tufaratv.data.model.Recording
import app.tufaratv.data.model.Series
import app.tufaratv.data.parser.displayTitle
import app.tufaratv.ui.channels.HomeScreen
import app.tufaratv.ui.player.LivePlaybackViewModel
import app.tufaratv.ui.recordings.RecordingsScreen
import app.tufaratv.ui.vod.ContinueWatchingRow
import app.tufaratv.ui.vod.MoviesScreen
import app.tufaratv.ui.vod.MoviePosterRow
import app.tufaratv.ui.vod.SectionHeader
import app.tufaratv.ui.vod.TmdbPosterRow
import app.tufaratv.ui.vod.SeriesScreen
import app.tufaratv.ui.vod.SeriesPosterRow
import app.tufaratv.ui.nativeview.NativeDashboardHero
import app.tufaratv.ui.nativeview.NativeDashboardItem
import app.tufaratv.ui.nativeview.NativeDashboardPalette
import app.tufaratv.ui.nativeview.NativeDashboardSection
import app.tufaratv.ui.nativeview.NativeDashboardView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

/**
 * The shell: a slim navigation rail down the left over a content area. The rail sits collapsed as
 * an icon strip and expands to show labels the moment focus lands in it — the TiviMate-style side
 * menu people asked for, instead of a top bar that ate a row of the guide. It overlays the content
 * rather than pushing it, so expanding the menu never reflows the guide underneath.
 */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    LIVE(R.string.nav_live_tv, Icons.Filled.LiveTv),
    MOVIES(R.string.nav_movies, Icons.Filled.Movie),
    SHOWS(R.string.nav_shows, Icons.Filled.Tv),
    RECORDINGS(R.string.nav_recordings, Icons.Filled.FiberManualRecord),
}

private val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 236.dp

@Composable
fun MainScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    livePlayback: LivePlaybackViewModel,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenTmdbMovie: (TmdbListItem) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenTmdbSeries: (TmdbListItem) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit,
    activeProfileName: String,
) {
    // Content-type toggles: a switched-off type has its tab hidden here (and its sync skipped in
    // CatalogRepository). Recordings is never a content type, so it always stays — which also means
    // there is always at least one tab and never a blank shell, even with all three types off.
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val visibleTabs = remember(liveEnabled, moviesEnabled, seriesEnabled) {
        buildList {
            add(Tab.HOME)
            if (liveEnabled) add(Tab.LIVE)
            if (moviesEnabled) add(Tab.MOVIES)
            if (seriesEnabled) add(Tab.SHOWS)
            add(Tab.RECORDINGS)
        }
    }
    // The default/home tab is the first visible one — Live TV normally, otherwise the first type
    // still switched on (or Recordings if none are).
    val homeTab = visibleTabs.first()

    // Saveable, not just remember: MainScreen's content leaves composition (but its NavBackStackEntry
    // stays alive) every time a detail/player screen is pushed on top, so a plain `remember` would
    // reset back to the home tab on every return trip — which is exactly the "back from a movie drops
    // me on Live TV" bug this fixes.
    var tab by rememberSaveable { mutableStateOf(homeTab) }

    // If the selected tab gets hidden (its type toggled off while it's open), drop back to the
    // home tab so the content area never tries to show a tab that's no longer there.
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) tab = homeTab
    }

    // Back from a non-home tab returns to the home tab rather than dropping out of the app.
    BackHandler(enabled = tab != homeTab) { tab = homeTab }

    // On the home tab, Back would otherwise drop straight out to the TV launcher — one stray press
    // and you've closed the app. Ask first. (A dialog or panel open in a child screen swallows Back
    // before this, so this only fires at the true root.)
    var showExit by remember { mutableStateOf(false) }
    BackHandler(enabled = tab == homeTab) { showExit = true }
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = { showExit = false; context.findActivity()?.finish() }) {
                    Text(stringResource(R.string.exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.exit_cancel)) }
            },
        )
    }

    // Keep the content viewport fixed. Expanding the rail used to change Row constraints from
    // 76dp to 236dp, forcing the complete dashboard/EPG (including poster images and hundreds of
    // guide cells) through measure/layout on every focus crossing. TiviMate overlays its expanded
    // menu instead; only the small rail redraws and the heavy screen underneath never reflows.
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        val showGlobalRail = tab != Tab.LIVE
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = if (showGlobalRail) RAIL_COLLAPSED else 0.dp),
        ) {
                // Only one heavy screen is composed at a time. Cross-fading two poster/EPG trees
                // made low-end boxes decode and lay out both during every tab switch.
                when (tab) {
                    Tab.HOME -> StreamingHomeScreen(
                        onPlayChannel = onPlayChannel,
                        onOpenMovie = onOpenMovie,
                        onOpenTmdbMovie = onOpenTmdbMovie,
                        onOpenSeries = onOpenSeries,
                        onOpenTmdbSeries = onOpenTmdbSeries,
                        onResume = onResume,
                    )
                    Tab.LIVE -> HomeScreen(
                        isTelevision = isTelevision,
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                        onPlayChannel = onPlayChannel,
                        onAddSource = onAddSource,
                        onRefresh = onRefresh,
                        livePlayback = livePlayback,
                        onPlayCatchup = onPlayCatchup,
                    )
                    Tab.MOVIES -> MoviesScreen(
                        onOpenMovie = onOpenMovie,
                        onOpenTmdb = onOpenTmdbMovie,
                        onResume = onResume,
                        onOpenSearch = onOpenSearch,
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                    )
                    Tab.SHOWS -> SeriesScreen(
                        onOpenSeries = onOpenSeries,
                        onOpenTmdb = onOpenTmdbSeries,
                        onResume = onResume,
                        onOpenSearch = onOpenSearch,
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                    )
                    Tab.RECORDINGS -> RecordingsScreen(onPlay = onPlayRecording)
                }
        }
        if (showGlobalRail) {
            NavRail(
                tabs = visibleTabs,
                current = tab,
                onSelect = { tab = it },
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onOpenProfiles = onOpenProfiles,
                activeProfileName = activeProfileName,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
      }
      StatusBar()
    }
}

@Composable
private fun StreamingHomeScreen(
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenTmdbMovie: (TmdbListItem) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenTmdbSeries: (TmdbListItem) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    vodViewModel: VodViewModel = viewModel(),
    homeViewModel: StreamingHomeViewModel = viewModel(),
) {
    val resume by vodViewModel.continueWatching.collectAsState()
    val recentLive by homeViewModel.recentLive.collectAsState()
    val favouriteMovies by vodViewModel.favouriteMovies.collectAsState()
    val favouriteSeries by vodViewModel.favouriteSeries.collectAsState()
    val movies by vodViewModel.tmdbTrendingMovies.collectAsState()
    val series by vodViewModel.tmdbTrendingSeries.collectAsState()
    val featuredItem = movies.firstOrNull()
    var featuredMeta by remember(featuredItem?.tmdbId) { mutableStateOf<TmdbMeta?>(null) }
    LaunchedEffect(featuredItem?.tmdbId) {
        featuredMeta = featuredItem?.let { vodViewModel.tmdbDetail(it.tmdbId, it.isMovie) }
    }

    // Home is now the product entry point, so VOD warm-up starts here rather than waiting for the
    // user to discover the Movies or Shows tabs. The repository TTL keeps this cheap on warm starts.
    LaunchedEffect(Unit) { vodViewModel.ensureVodLoaded() }

    val palette = NativeDashboardPalette(
        background = MaterialTheme.colorScheme.background.toArgb(),
        surface = MaterialTheme.colorScheme.surface.toArgb(),
        surfaceVariant = MaterialTheme.colorScheme.surfaceVariant.toArgb(),
        text = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondaryText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        primary = MaterialTheme.colorScheme.primary.toArgb(),
    )
    val sections = buildList {
        if (resume.isNotEmpty()) add(
            NativeDashboardSection(
                id = "continue",
                title = stringResource(R.string.vod_continue_watching),
                items = resume.map { item ->
                    NativeDashboardItem(
                        id = item.mediaKey,
                        title = item.title,
                        imageUrl = item.posterUrl,
                        progress = item.progress,
                        landscape = true,
                        onClick = { onResume(item.mediaKey, item.streamUrl, item.title) },
                    )
                },
            ),
        )
        if (recentLive.isNotEmpty()) add(
            NativeDashboardSection(
                id = "recent_live",
                title = stringResource(R.string.home_recent_live),
                items = recentLive.map { item ->
                    NativeDashboardItem(
                        id = "live:${item.channel.id}",
                        title = item.channel.displayName,
                        subtitle = item.now?.title ?: stringResource(R.string.nav_live_tv),
                        imageUrl = item.channel.logoUrl,
                        badge = item.channel.number?.toString(),
                        progress = item.now?.progressAt(System.currentTimeMillis()),
                        landscape = true,
                        fitImage = true,
                        compactLive = true,
                        onClick = { onPlayChannel(item.channel) },
                    )
                },
            ),
        )
        if (favouriteMovies.isNotEmpty()) add(
            NativeDashboardSection(
                id = "favourite_movies",
                title = stringResource(R.string.vod_favourites_movies),
                items = favouriteMovies.map { movie ->
                    NativeDashboardItem(
                        id = "movie:${movie.id}", title = movie.displayTitle,
                        subtitle = movie.year?.toString(), imageUrl = movie.posterUrl,
                        badge = movie.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        favourite = true, onClick = { onOpenMovie(movie) },
                    )
                },
            ),
        )
        if (favouriteSeries.isNotEmpty()) add(
            NativeDashboardSection(
                id = "favourite_series",
                title = stringResource(R.string.vod_favourites_series),
                items = favouriteSeries.map { show ->
                    NativeDashboardItem(
                        id = "series:${show.id}", title = show.displayTitle,
                        subtitle = show.year?.toString(), imageUrl = show.posterUrl,
                        badge = show.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        favourite = true, onClick = { onOpenSeries(show) },
                    )
                },
            ),
        )
        if (movies.isNotEmpty()) add(
            NativeDashboardSection(
                id = "trending_movies",
                title = stringResource(R.string.home_trending_movies),
                items = movies.map { item ->
                    NativeDashboardItem(
                        id = "tmdb-movie:${item.tmdbId}", title = item.title,
                        subtitle = item.year?.toString(), imageUrl = item.posterUrl,
                        badge = item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        onClick = { onOpenTmdbMovie(item) },
                    )
                },
            ),
        )
        if (series.isNotEmpty()) add(
            NativeDashboardSection(
                id = "trending_series",
                title = stringResource(R.string.home_trending_series),
                items = series.map { item ->
                    NativeDashboardItem(
                        id = "tmdb-series:${item.tmdbId}", title = item.title,
                        subtitle = item.year?.toString(), imageUrl = item.posterUrl,
                        badge = item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        onClick = { onOpenTmdbSeries(item) },
                    )
                },
            ),
        )
    }
    val featured = featuredItem?.let { item ->
        NativeDashboardHero(
            id = "featured:${item.tmdbId}",
            title = featuredMeta?.title?.takeIf { it.isNotBlank() } ?: item.title,
            subtitle = listOfNotNull(
                (featuredMeta?.year ?: item.year)?.toString(),
                (featuredMeta?.rating ?: item.rating)?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                featuredMeta?.overview?.takeIf { it.isNotBlank() },
            ).joinToString("  •  "),
            imageUrl = featuredMeta?.backdropUrl ?: item.posterUrl,
            onClick = { onOpenTmdbMovie(item) },
        )
    }
    AndroidView(
        factory = { NativeDashboardView(it) },
        update = { it.submit(featured, sections, palette) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Large streaming-service hero. Details arrive lazily; the browse poster remains a safe fallback. */
@Composable
private fun FeaturedTitle(
    item: TmdbListItem,
    loadMeta: suspend () -> TmdbMeta?,
    onOpen: () -> Unit,
) {
    var meta by remember(item.tmdbId) { mutableStateOf<TmdbMeta?>(null) }
    LaunchedEffect(item.tmdbId) { meta = loadMeta() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(Color.Black),
    ) {
        AsyncImage(
            model = meta?.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.52f to Color.Black.copy(alpha = 0.36f),
                        1f to Color.Transparent,
                    ),
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.54f)
                .padding(start = 42.dp, end = 24.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_featured),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                meta?.title?.takeIf { it.isNotBlank() } ?: item.title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    (meta?.year ?: item.year)?.toString(),
                    (meta?.rating ?: item.rating)?.takeIf { it > 0.0 }?.let { "★ %.1f".format(it) },
                ).joinToString("  •  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.84f),
            )
            meta?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onOpen) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_view_title))
            }
        }
    }
}

@Composable
private fun RecentLiveCard(
    item: StreamingHomeViewModel.RecentLiveItem,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AsyncImage(
                    model = item.channel.logoUrl,
                    contentDescription = item.channel.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.channel.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.now?.title ?: stringResource(R.string.nav_live_tv),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.now?.let { now ->
            Spacer(Modifier.height(10.dp))
            val progress = now.progressAt(System.currentTimeMillis())
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        item.next?.takeIf { it.id != item.now?.id }?.let { next ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Ensuite · " + next.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(1.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(border.copy(alpha = if (focused) 0.9f else 0f))
        )
    }
}

/**
 * A slim line along the bottom that says what the app is doing in the background — loading
 * channels, building the guide, loading movies — so a slow moment on a big provider reads as work
 * in progress, not a frozen screen. Invisible when there's nothing to report.
 */
@Composable
private fun StatusBar() {
    val message by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    val text = message ?: return
    val p = progress
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NavRail(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    modifier: Modifier = Modifier,
) {
    // Expand whenever focus is anywhere inside the rail; collapse back to icons when it leaves.
    var expanded by remember { mutableStateOf(false) }
    // As with the guide's category rail, a snapped width costs one layout pass instead of forcing
    // the whole dashboard/EPG to reflow on every frame of a width animation.
    val width = if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .focusGroup()
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Brand: the logo mark alone when collapsed, the mark + "TufaraTV" wordmark when open. The
        // name stays on purpose — it's what people search for.
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_tufaratv_logo),
                contentDescription = "TufaraTV",
                modifier = Modifier.size(34.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "TufaraTV",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        tabs.forEach { t ->
            RailItem(t.icon, stringResource(t.labelRes), expanded, current == t) { onSelect(t) }
        }

        Spacer(Modifier.height(1.dp).fillMaxWidth())
        Spacer(Modifier.weight(1f))

        RailItem(Icons.Filled.Search, stringResource(R.string.nav_search), expanded, false, onOpenSearch)
        RailItem(Icons.Filled.Person, activeProfileName, expanded, false, onOpenProfiles)
        RailItem(Icons.Filled.Settings, stringResource(R.string.nav_settings), expanded, false, onOpenSettings)
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val tint = if (focused) MaterialTheme.colorScheme.onPrimary
    else if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
