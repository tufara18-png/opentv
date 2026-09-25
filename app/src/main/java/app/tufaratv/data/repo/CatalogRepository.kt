/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

import android.util.Log
import androidx.room.withTransaction
import app.tufaratv.core.AppSettings
import app.tufaratv.data.db.CanonicalEpisodeDao
import app.tufaratv.data.db.CanonicalMovieDao
import app.tufaratv.data.db.CanonicalSeriesDao
import app.tufaratv.data.db.CategoryDao
import app.tufaratv.data.db.ChannelDao
import app.tufaratv.data.db.EpisodeDao
import app.tufaratv.data.db.MovieDao
import app.tufaratv.data.db.OpenTvDatabase
import app.tufaratv.data.db.PlaybackPositionDao
import app.tufaratv.data.db.PreferredVariantDao
import app.tufaratv.data.db.SeriesDao
import app.tufaratv.data.db.SourceDao
import app.tufaratv.data.model.CanonicalContent
import app.tufaratv.data.model.CanonicalEpisode
import app.tufaratv.data.model.CanonicalKind
import app.tufaratv.data.model.CanonicalMatchKind
import app.tufaratv.data.model.Category
import app.tufaratv.data.model.PreferredVariant
import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.LiveStreamFormat
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.SourceKind
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.parser.CanonicalMatcher
import app.tufaratv.data.parser.ChannelNameNormalizer
import app.tufaratv.data.parser.M3uParser
import app.tufaratv.data.parser.VodTitleCleaner
import app.tufaratv.data.remote.StalkerApi
import app.tufaratv.data.remote.TmdbClient
import app.tufaratv.data.remote.TmdbGenre
import app.tufaratv.data.remote.TmdbListItem
import app.tufaratv.data.remote.TmdbMeta
import app.tufaratv.data.remote.XtreamApi
import app.tufaratv.data.db.FtsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps one channel per distinct quality signature, preserving order (so "best first" is
 * preserved when the caller has already sorted by rank).
 *
 * Two streams with the same rank AND the same label are treated as the same quality — one is
 * kept, the rest dropped. This is what collapses "RAW / RAW" and "HD / HD" duplicates down to
 * a single option, and turns a falsely-multi-quality channel back into a single one.
 */
internal fun distinctByQuality(channels: List<app.tufaratv.data.model.Channel>): List<app.tufaratv.data.model.Channel> {
    val seen = HashSet<String>()
    return channels.filter { seen.add("${it.qualityRank}|${it.qualityLabel.lowercase()}") }
}

/** A home/detail row: a genre label and the titles under it. Generic so movies and series share it. */
@androidx.compose.runtime.Immutable
data class GenreGroup<T>(val genre: String, val items: List<T>)

/**
 * One library entry a person is credited in — a movie or a series — for the Person screen's mixed
 * poster grid. A thin wrapper so the screen can render one grid yet still route a click to the right
 * detail page (movie vs series) without a second lookup.
 */
sealed interface PersonTitle {
    data class MovieItem(val movie: Movie) : PersonTitle
    data class SeriesItem(val series: Series) : PersonTitle
}

/** One quality variant of a film, with the quality parsed from its name by [ChannelNameNormalizer]. */
data class MovieVariant(val movie: Movie, val qualityLabel: String, val qualityRank: Int)

/** Route-sized playback target for moving to the next episode without leaving the full-screen player. */
data class NextEpisodePlayback(
    val mediaKey: String,
    val streamUrl: String,
    val title: String,
    val userAgent: String,
    val contentKey: String,
    val variantsKey: String,
)

/** One logical film with its switchable quality tiers, best first — the VOD analogue of a channel group. */
@androidx.compose.runtime.Immutable
data class MovieVariantGroup(
    /** Best-quality variant: what a row shows and plays by default. */
    val primary: Movie,
    /** Every variant (including [primary]), best quality first. */
    val variants: List<MovieVariant>,
) {
    val hasMultipleQualities: Boolean get() = variants.size > 1
}

/**
 * Collapses variant rows down to one per canonical entry (the merged, cross-provider identity
 * `CanonicalMatcher` linked them to at sync time — see [CanonicalContent]) — the browse/home
 * surfaces' dedup pass, so two providers' copies of the same film show as one card, not two.
 *
 * Pure and in-memory, over whatever list the caller already loaded: cheap at a few thousand rows,
 * and it means every `Movie`/`Series` query in this file gets the same treatment through one
 * choke point rather than N slightly-different copies of the same grouping logic. A row not yet
 * linked to a canonical entry (`canonicalId == null` — the brief window between sync's upsert and
 * the matcher pass) is kept as its own, unique group via its own row id, never dropped.
 *
 * [LinkedHashMap] is what makes this order-preserving: re-inserting an *existing* key (a better-
 * quality variant replacing the one currently kept for that canonical id) does not move it, so
 * the output keeps the input's relative order — newest-first stays newest-first, genre order
 * stays genre order.
 */
private fun dedupeMoviesByCanonical(movies: List<Movie>): List<Movie> {
    val byKey = LinkedHashMap<String, Movie>()
    for (movie in movies) {
        val key = movie.canonicalId?.let { "canonical:$it" } ?: run {
            val identity = CanonicalMatcher.keyOf(movie.name, movie.year)
            "heuristic:${identity.titleKey}|${identity.year ?: ""}"
        }
        val existing = byKey[key]
        if (existing == null || movie.qualityRank > existing.qualityRank) byKey[key] = movie
    }
    return byKey.values.toList()
}

/** See [dedupeMoviesByCanonical]. */
private fun dedupeSeriesByCanonical(series: List<Series>): List<Series> {
    val byKey = LinkedHashMap<Long, Series>()
    for (s in series) {
        val key = s.canonicalId ?: -s.id
        val existing = byKey[key]
        if (existing == null || s.qualityRank > existing.qualityRank) byKey[key] = s
    }
    return byKey.values.toList()
}

/** The [SourceVariant.language] tags [CatalogRepository.movieVariants] treats as "understood" —
 *  French, Italian, or an explicitly multi-language copy. Matches [VodTitleCleaner]'s own token
 *  spellings for those (`FR`/`FRA`/`FRE`, `IT`/`ITA`, `MULTI`), uppercased before comparing. */
private val PREFERRED_MOVIE_LANGUAGES = setOf("FR", "FRA", "FRE", "IT", "ITA", "MULTI")

/**
 * Collapses obvious quality variants of the same film — "The Godfather 1972 HD" and
 * "The Godfather 4K" — into one entry with switchable tiers, the VOD analogue of a channel's
 * quality group. Pure and in-memory: movies carry no stored groupKey, so the key is computed here
 * from the normalised, year-stripped name plus the year (the field, else the one embedded in the
 * name). Group order follows first appearance; variants within a group are best-quality first.
 *
 * It folds only genuine quality variants: edition tokens [ChannelNameNormalizer] does not know
 * (IMAX, EXTENDED, 3D) stay in the name and keep those cuts separate — intended. Two same-named
 * films that both lack any year will merge, an accepted edge for a best-effort collapse.
 */
internal fun collapseMovieVariants(movies: List<Movie>): List<MovieVariantGroup> {
    val groups = LinkedHashMap<String, MutableList<MovieVariant>>()
    for (movie in movies) {
        val identity = CanonicalMatcher.keyOf(movie.name, movie.year)
        val parsed = VodTitleCleaner.parse(movie.name)
        // Once sync has resolved a canonical identity, that id beats every title heuristic.
        // Only rows in the brief pre-link window fall back to normalized title + release year.
        val key = movie.canonicalId?.let { "canonical:$it" }
            ?: "heuristic:${identity.titleKey}|${identity.year?.toString() ?: ""}"
        groups.getOrPut(key) { mutableListOf() }
            .add(MovieVariant(movie, parsed.qualityLabel, parsed.qualityRank))
    }
    return groups.values.map { variants ->
        val ordered = variants.sortedWith(
            compareByDescending<MovieVariant> { it.qualityRank }.thenBy { it.movie.name },
        )
        MovieVariantGroup(primary = ordered.first().movie, variants = ordered)
    }
}

/**
 * Channels, movies and series.
 *
 * Same principle as [EpgRepository]: a refresh that fails must never leave the user with less
 * than they started with. Catalogue writes go through [ChannelDao.replaceCatalogue], which
 * merges rather than wipes and carries favourites, hidden flags and manual ordering across.
 */
class CatalogRepository(
    private val database: OpenTvDatabase,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val canonicalMovieDao: CanonicalMovieDao,
    private val canonicalSeriesDao: CanonicalSeriesDao,
    private val canonicalEpisodeDao: CanonicalEpisodeDao,
    private val preferredVariantDao: PreferredVariantDao,
    private val positionDao: PlaybackPositionDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {
    private data class AvailabilityCandidates(
        val tmdbIds: Set<String>,
        val yearsByTitleKey: Map<String, Set<Int?>>,
    )

    private fun List<CanonicalContent>.asAvailabilityCandidates(): AvailabilityCandidates =
        AvailabilityCandidates(
            tmdbIds = mapNotNullTo(hashSetOf()) { it.tmdbId?.takeIf(String::isNotBlank) },
            yearsByTitleKey = groupBy(CanonicalContent::titleKey)
                .mapValues { (_, matches) -> matches.mapTo(hashSetOf()) { it.year } },
        )

    private fun AvailabilityCandidates.contains(item: TmdbListItem): Boolean {
        if (item.tmdbId in tmdbIds) return true
        val years = yearsByTitleKey[CanonicalMatcher.keyOf(item.title, item.year).titleKey] ?: return false
        return years.any { localYear -> localYear == null || item.year == null || localYear == item.year }
    }

    /** TMDB back-fill for VOD detail pages, gated on a user-supplied key. See [TmdbClient]. */
    private val tmdb = TmdbClient(http, settings)

    sealed interface SyncResult {
        data class Success(
            val channelCount: Int,
            val movieCount: Int,
            val seriesCount: Int,
        ) : SyncResult

        data class Failed(val reason: String, val cause: Throwable?) : SyncResult
    }

    fun observeChannels(sourceId: Long? = null, categoryId: String? = null): Flow<List<Channel>> =
        channelDao.observe(sourceId, categoryId)

    fun observeChannelsIn(categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategories(categoryIds)

    /** See [ChannelDao.observeVisibleCategoryIds]. */
    fun observeVisibleCategoryIds(): Flow<Set<String>> =
        channelDao.observeVisibleCategoryIds().map { it.toSet() }

    /**
     * Channels in a set of categories INCLUDING hidden ones, optionally scoped to one source — the
     * channel manager's browse feed. Separate from [observeChannelsIn] (which drops `hidden` rows
     * for the guide) because the manager must show hidden channels so they can be un-hidden.
     */
    fun observeChannelsInIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategoriesIncludingHidden(sourceId, categoryIds)

    fun observeFavouriteChannels(): Flow<List<Channel>> = channelDao.observeFavourites()

    /** Reactive number of visible channels on disk — the UI uses this to tell "guide still
     * building" (channels exist) apart from "nothing loaded" (a failed or empty sync). */
    fun observeChannelCount(): Flow<Int> = channelDao.observeVisibleCount()

    fun observeCategories(kind: StreamKind): Flow<List<Category>> = categoryDao.observe(kind)

    fun observeMovies(categoryId: String? = null): Flow<List<Movie>> =
        movieDao.observe(categoryId).map { dedupeMoviesByCanonical(it) }

    fun observeSeries(categoryId: String? = null): Flow<List<Series>> =
        seriesDao.observe(categoryId).map { dedupeSeriesByCanonical(it) }

    fun observeEpisodes(sourceId: Long, seriesId: String): Flow<List<Episode>> =
        episodeDao.observeForSeries(sourceId, seriesId)

    fun searchChannels(query: String): Flow<List<Channel>> = channelDao.search(query)

    fun searchChannelsIncludingHidden(query: String): Flow<List<Channel>> =
        channelDao.searchIncludingHidden(query)

    /**
     * FTS-backed, canonical-deduped: one row per merged title (its best-quality variant),
     * ranked by [app.tufaratv.data.db.CanonicalMovieDao.search]'s `MATCH` relevance rather than a
     * `LIKE` scan — and, via [app.tufaratv.data.model.CanonicalContent.alsoKnownAs], still finds a
     * title by whatever wording a provider used even once its display title becomes TMDB's. Still
     * returns plain [Movie] rows (unchanged shape) so existing callers/UI need no changes.
     */
    fun searchMovies(query: String): Flow<List<Movie>> {
        val fts = FtsQuery.prefixMatch(query)
        if (fts.isBlank()) return flowOf(emptyList())
        return canonicalMovieDao.search(fts).map { canonicalRows ->
            val ids = canonicalRows.map { it.id }
            val bestByCanonical = movieDao.byCanonicalIds(ids)
                .groupBy { it.canonicalId }
                .mapValues { (_, variants) -> variants.maxByOrNull { it.qualityRank } }
            ids.mapNotNull { bestByCanonical[it] }
        }
    }

    /** See [searchMovies] — same FTS-backed, canonical-deduped approach. */
    fun searchSeries(query: String): Flow<List<Series>> {
        val fts = FtsQuery.prefixMatch(query)
        if (fts.isBlank()) return flowOf(emptyList())
        return canonicalSeriesDao.search(fts).map { canonicalRows ->
            val ids = canonicalRows.map { it.id }
            val bestByCanonical = seriesDao.byCanonicalIds(ids)
                .groupBy { it.canonicalId }
                .mapValues { (_, variants) -> variants.maxByOrNull { it.qualityRank } }
            ids.mapNotNull { bestByCanonical[it] }
        }
    }

    suspend fun channel(id: Long): Channel? = channelDao.byId(id)

    suspend fun movie(id: Long): Movie? = movieDao.byId(id)

    /**
     * The IMDb id for a film, for Stremio add-on stream lookups. Resolved through the user's TMDB
     * key (using the provider's TMDB id when present, else a title+year search). Null without a key
     * or a match — the add-on feature stays inert rather than guessing.
     */
    suspend fun imdbIdFor(movie: Movie): String? = withContext(Dispatchers.IO) {
        tmdb.imdbId(title = movie.name, year = movie.year, isMovie = true, tmdbId = movie.tmdbId)
    }

    suspend fun episode(id: Long): Episode? = episodeDao.byId(id)

    /** Cached episode list for the show containing [localEpisodeId]. No network request. */
    suspend fun seriesEpisodes(localEpisodeId: Long): List<Episode> = withContext(Dispatchers.IO) {
        val current = episodeDao.byId(localEpisodeId) ?: return@withContext emptyList()
        episodeDao.forSeries(current.sourceId, current.seriesId)
    }

    /** Route-sized playback target for any episode selected inside the player. */
    suspend fun episodePlayback(localEpisodeId: Long): NextEpisodePlayback? = withContext(Dispatchers.IO) {
        val chosen = episodeDao.byId(localEpisodeId) ?: return@withContext null
        val canonicalEpisodeId = chosen.canonicalEpisodeId ?: return@withContext null
        val canonicalEpisode = canonicalEpisodeDao.byId(canonicalEpisodeId) ?: return@withContext null
        val source = sourceDao.byId(chosen.sourceId)
        NextEpisodePlayback(
            mediaKey = "ep:${chosen.id}",
            streamUrl = chosen.streamUrl,
            title = "S${chosen.season}E${chosen.episodeNumber} · ${chosen.title}",
            userAgent = source?.userAgent ?: "TufaraTV/0.1 (Android)",
            contentKey = "series:${canonicalEpisode.canonicalSeriesId}",
            variantsKey = "episode:$canonicalEpisodeId",
        )
    }

    /**
     * Resolves the episode immediately following [localEpisodeId] in canonical series order.
     *
     * The returned URL is only the initial/default stream. [variantsKey] points the VOD player at
     * the next canonical episode, so its normal source/quality ladder is rebuilt after navigation
     * and the series-scoped [contentKey] keeps the viewer's source/quality preferences intact.
     */
    suspend fun nextEpisode(localEpisodeId: Long): NextEpisodePlayback? = withContext(Dispatchers.IO) {
        val current = episodeDao.byId(localEpisodeId) ?: return@withContext null
        val currentCanonicalId = current.canonicalEpisodeId ?: return@withContext null
        val currentCanonical = canonicalEpisodeDao.byId(currentCanonicalId) ?: return@withContext null
        val next = canonicalEpisodeDao.nextAfter(
            canonicalSeriesId = currentCanonical.canonicalSeriesId,
            season = currentCanonical.season,
            episodeNumber = currentCanonical.episodeNumber,
        ) ?: return@withContext null
        val rows = episodeDao.byCanonicalEpisodeId(next.id)
        if (rows.isEmpty()) return@withContext null

        // Prefer continuity on the same provider when that episode exists there. Otherwise use the
        // best advertised quality; the player may still pick another source from the full ladder.
        val chosen = rows.firstOrNull { it.sourceId == current.sourceId }
            ?: rows.maxByOrNull { it.qualityRank }
            ?: return@withContext null
        episodePlayback(chosen.id)
    }

    suspend fun movieByStreamUrl(url: String): Movie? = movieDao.byStreamUrl(url)

    suspend fun episodeByStreamUrl(url: String): Episode? = episodeDao.byStreamUrl(url)

    suspend fun series(id: Long): app.tufaratv.data.model.Series? = seriesDao.byId(id)

    /** Canonical/TMDB-facing identity helpers used by UI surfaces that must never expose
     * provider-supplied VOD titles once a TMDB identity exists. */
    suspend fun canonicalMovie(id: Long): CanonicalContent? = canonicalMovieDao.byId(id)
    suspend fun canonicalSeries(id: Long): CanonicalContent? = canonicalSeriesDao.byId(id)
    suspend fun canonicalEpisode(id: Long): CanonicalEpisode? = canonicalEpisodeDao.byId(id)
    suspend fun seriesByProviderIdentity(sourceId: Long, seriesId: String): Series? =
        seriesDao.bySourceAndSeriesId(sourceId, seriesId)

    // ---- Netflix-style home feeds ---------------------------------------------------------------
    // All local: derived from the catalogue already on disk plus the active profile's watch history.
    // The plain catalogue rows (recently added) are Flows so they fill in live as a VOD sync lands;
    // the computed rows (recommended, by-genre, more-like-this) are one-shot suspend reads, cheap
    // enough to recompute on screen open over a few thousand titles (one in-memory pass each — a
    // per-genre LIKE query would multiply round-trips and match substrings). A later UI agent wraps
    // these into home rows and detail screens.

    /** "Recently Added" movies, newest first. Reactive. */
    fun recentlyAddedMovies(limit: Int = 30): Flow<List<Movie>> =
        movieDao.observeRecentlyAdded(limit).map { dedupeMoviesByCanonical(it) }

    /** "Recently Added" series, newest first. Reactive. */
    fun recentlyAddedSeries(limit: Int = 30): Flow<List<Series>> =
        seriesDao.observeRecentlyAdded(limit).map { dedupeSeriesByCanonical(it) }

    /** Every movie, newest first, deduped to one row per canonical entry — for a caller that
     *  wants to build its own groupings. The single choke point [moviesByGenre]/[recommendedMovies]/
     *  [moreLikeThis] etc. all route through, so the whole library is only ever scanned once per
     *  screen open and every one of them agrees on what "the library" is. */
    suspend fun allMovies(): List<Movie> =
        withContext(Dispatchers.IO) { dedupeMoviesByCanonical(movieDao.all()) }

    /** Every series, newest first, deduped. See [allMovies]. */
    suspend fun allSeries(): List<Series> =
        withContext(Dispatchers.IO) { dedupeSeriesByCanonical(seriesDao.all()) }

    fun observeFavouriteMovies(): Flow<List<Movie>> = movieDao.observeFavourites()

    fun observeFavouriteSeries(): Flow<List<Series>> = seriesDao.observeFavourites()

    /** How many movies / series are on disk — a cheap COUNT the home screen uses to tell "the
     *  library grew" from "unchanged since last open" without loading every row. */
    suspend fun movieCount(): Int = withContext(Dispatchers.IO) { movieDao.count() }
    suspend fun seriesCount(): Int = withContext(Dispatchers.IO) { seriesDao.count() }

    /**
     * Movies grouped by genre for the by-genre home rows: the [maxGenres] biggest genres, each with
     * up to [perGenre] titles (newest first). A movie appears under every genre it lists — provider
     * genre strings are frequently multi-valued ("Action, Thriller" / "Action|Thriller"), so they
     * are split on comma and pipe (see [splitGenres]).
     */
    suspend fun moviesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.IO) { groupByGenre(allMovies(), Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre for the by-genre home rows. See [moviesByGenre]. */
    suspend fun seriesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.IO) { groupByGenre(allSeries(), Series::genre, maxGenres, perGenre) }

    /**
     * Movies grouped by genre from an ALREADY-LOADED list — the single-scan path the home screen
     * uses. [VodViewModel.loadHomeFeeds] reads [allMovies] once and hands that one list to this, to
     * [recommendedMoviesFrom], etc., so opening Movies scans the (20k-title) table once instead of
     * once per row. Pure grouping, off the main thread. See [moviesByGenre] for the scanning variant.
     */
    suspend fun moviesByGenreFrom(all: List<Movie>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre from an already-loaded list — the single-scan path. See [moviesByGenreFrom]. */
    suspend fun seriesByGenreFrom(all: List<Series>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Series::genre, maxGenres, perGenre) }

    /**
     * "Recommended for you" — a simple, explainable genre-affinity heuristic, no ML.
     *
     * Tallies the genres of the movies this profile has watched or resumed, then returns the
     * highest-scoring UNWATCHED movies, where a movie's score is how many of its genres the profile
     * favours (ties broken by rating, then recency). With no usable history — a fresh profile, or
     * only watched movies that carry no genre — it falls back to top-rated, then recently-added.
     */
    suspend fun recommendedMovies(profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(allMovies(), watchedMovieIds(profileId), limit) }

    /**
     * "Recommended for you" from an ALREADY-LOADED movie list — the single-scan path (see
     * [recommendedMovies]). Only the profile's watch history is read from disk here; the movie
     * library is the caller's [allMovies] list, shared with the genre rows so the home screen scans
     * the (20k-title) table once for the whole home feed rather than once per row.
     */
    suspend fun recommendedMoviesFrom(all: List<Movie>, profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(all, watchedMovieIds(profileId), limit) }

    /** The movie ids this profile has watched or resumed — the input to the affinity heuristic. */
    private suspend fun watchedMovieIds(profileId: Long): Set<Long> =
        positionDao.forProfile(profileId).mapNotNull { movieIdFromMediaKey(it.mediaKey) }.toSet()

    /** The pure genre-affinity ranking pass shared by [recommendedMovies] and [recommendedMoviesFrom]. */
    private fun recommendFrom(all: List<Movie>, watchedMovieIds: Set<Long>, limit: Int): List<Movie> {
        val byId = all.associateBy { it.id }

        val affinity = HashMap<String, Int>()
        for (id in watchedMovieIds) {
            val watched = byId[id] ?: continue
            for (genre in splitGenres(watched.genre)) affinity[genre] = (affinity[genre] ?: 0) + 1
        }

        val unwatched = all.filter { it.id !in watchedMovieIds }

        fun topRatedFallback(): List<Movie> = unwatched
            .sortedWith(compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis })
            .take(limit)

        if (affinity.isEmpty()) return topRatedFallback()

        val ranked = unwatched
            .map { it to genreScore(it.genre, affinity) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.rating ?: -1.0 }
                    .thenByDescending { it.first.addedMillis },
            )
            .map { it.first }
            .take(limit)

        return ranked.ifEmpty { topRatedFallback() }
    }

    /**
     * "More Like This" for a movie: other titles sharing a genre, most genres in common first,
     * same-source titles preferred, the film itself excluded. Falls back to other titles in the
     * same source/category when the movie has no genre metadata to match on.
     */
    suspend fun moreLikeThis(movie: Movie, limit: Int = 20): List<Movie> = withContext(Dispatchers.IO) {
        // Prefer TMDB's actual recommendation graph when this title has a stable identity, then
        // intersect it with locally playable canonical entries. Provider genre overlap remains the
        // offline fallback, never the primary recommender.
        val tmdbPicks = movie.tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { tmdb.recommendations(id, isMovie = true) }.getOrDefault(emptyList())
                .mapNotNull { item ->
                    val canonical = canonicalMovieDao.findByTmdbId(item.tmdbId) ?: run {
                        val key = CanonicalMatcher.keyOf(item.title, item.year).titleKey
                        canonicalMovieDao.findAllByTitleKey(key)
                            .firstOrNull { it.year == null || item.year == null || it.year == item.year }
                    }
                    canonical?.let { movieDao.byCanonicalIds(listOf(it.id)).maxByOrNull(Movie::qualityRank) }
                }
                .filter { it.id != movie.id }
                .distinctBy { it.canonicalId ?: it.id }
                .take(limit)
        }.orEmpty()
        if (tmdbPicks.size >= minOf(6, limit)) return@withContext tmdbPicks

        val genres = splitGenres(movie.genre).toSet()
        val fallback = if (genres.isEmpty()) {
            movieDao.similarByCategory(movie.sourceId, movie.categoryId, movie.id, limit)
        } else {
            allMovies().asSequence()
                .filter { it.id != movie.id }
                .map { it to sharedGenreCount(it.genre, genres) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Movie, Int>> { it.second }
                        .thenByDescending { it.first.sourceId == movie.sourceId }
                        .thenByDescending { it.first.rating ?: -1.0 },
                )
                .map { it.first }
                .take(limit)
                .toList()
        }
        (tmdbPicks + fallback)
            .distinctBy { it.canonicalId ?: it.id }
            .take(limit)
    }

    // ---- Related by person (Plex-style "click an actor / director") ----------------------------

    /** Movies a person is billed in, best-rated first. Blank query yields nothing. */
    suspend fun moviesWithActor(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesWithActor(it, limit) }.orEmpty()
    }

    /** Movies a person directed, best-rated first. */
    suspend fun moviesByDirector(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesByDirector(it, limit) }.orEmpty()
    }

    /** Series a person is billed in, best-rated first. */
    suspend fun seriesWithActor(name: String, limit: Int = 40): List<Series> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { seriesDao.seriesWithActor(it, limit) }.orEmpty()
    }

    /**
     * Everything in the library featuring a person — the data behind the Person screen. Merges movies
     * they act in, movies they directed and series they act in, de-duplicating movies that credit the
     * same person as both actor and director. Movies (best-rated first) lead, then series.
     */
    suspend fun titlesWithPerson(name: String, perKind: Int = 40): List<PersonTitle> =
        withContext(Dispatchers.IO) {
            val query = name.trim()
            if (query.isEmpty()) return@withContext emptyList()

            val movies = LinkedHashMap<Long, Movie>()
            for (m in movieDao.moviesWithActor(query, perKind)) movies[m.id] = m
            for (m in movieDao.moviesByDirector(query, perKind)) movies.putIfAbsent(m.id, m)

            val orderedMovies = movies.values.sortedWith(
                compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis },
            )
            val series = seriesDao.seriesWithActor(query, perKind)

            buildList(orderedMovies.size + series.size) {
                orderedMovies.forEach { add(PersonTitle.MovieItem(it)) }
                series.forEach { add(PersonTitle.SeriesItem(it)) }
            }
        }

    /** "More Like This" for a series. See [moreLikeThis]. */
    suspend fun moreLikeThisSeries(series: Series, limit: Int = 20): List<Series> = withContext(Dispatchers.IO) {
        val tmdbPicks = series.tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { tmdb.recommendations(id, isMovie = false) }.getOrDefault(emptyList())
                .mapNotNull { item ->
                    val canonical = canonicalSeriesDao.findByTmdbId(item.tmdbId) ?: run {
                        val key = CanonicalMatcher.keyOf(item.title, item.year).titleKey
                        canonicalSeriesDao.findAllByTitleKey(key)
                            .firstOrNull { it.year == null || item.year == null || it.year == item.year }
                    }
                    canonical?.let { seriesDao.byCanonicalIds(listOf(it.id)).maxByOrNull(Series::qualityRank) }
                }
                .filter { it.id != series.id }
                .distinctBy { it.canonicalId ?: it.id }
                .take(limit)
        }.orEmpty()
        if (tmdbPicks.size >= minOf(6, limit)) return@withContext tmdbPicks

        val genres = splitGenres(series.genre).toSet()
        val fallback = if (genres.isEmpty()) {
            seriesDao.similarByCategory(series.sourceId, series.categoryId, series.id, limit)
        } else {
            allSeries().asSequence()
                .filter { it.id != series.id }
                .map { it to sharedGenreCount(it.genre, genres) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Series, Int>> { it.second }
                        .thenByDescending { it.first.sourceId == series.sourceId }
                        .thenByDescending { it.first.rating ?: -1.0 },
                )
                .map { it.first }
                .take(limit)
                .toList()
        }
        (tmdbPicks + fallback)
            .distinctBy { it.canonicalId ?: it.id }
            .take(limit)
    }

    /**
     * Loads a movie and, if its detail fields are still bare, back-fills them from the provider's
     * `get_vod_info` and persists the merged row before returning it. Safe to call whenever a detail
     * screen opens: it runs off the main thread, only touches the network when something is missing,
     * tolerates any failure (returning the row unchanged), and — because it copies the stored row —
     * preserves the favourite flag. Non-Xtream sources are returned as-is.
     */
    suspend fun movieDetail(id: Long): Movie? = withContext(Dispatchers.IO) {
        val movie = movieDao.byId(id) ?: return@withContext null
        var result = movie
        val source = sourceDao.byId(movie.sourceId)

        // 1) Provider back-fill from get_vod_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.movieInfo(source, result.streamId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    director = result.director ?: info.director,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                    durationSeconds = result.durationSeconds ?: info.durationSeconds,
                )
            }
        }

        // 2) TMDB is the source of truth once matched, not just a gap-filler — a provider's own
        //    title/plot/cast is usually a decorated mess (see the whole VodTitleCleaner story), so
        //    once TMDB resolves a match its value WINS over whatever the provider already supplied,
        //    including this title's display name. A provider-only field TMDB has no concept of
        //    (streamUrl, qualityRank, …) is untouched; this is metadata-only.
        //
        //    Tried whenever not yet matched OR the name still looks provider-dirty
        //    (`VodTitleCleaner.clean` would still change it, e.g. a trailing `(2026 MULTI)`) — the
        //    second half is what makes this self-healing for a row that matched TMDB *before* this
        //    title-override existed, instead of needing a manual resync to ever pick it up. Once a
        //    match's title is genuinely clean, `clean(name) == name` and this stops re-querying.
        if (tmdb.isConfigured() && needsTmdbRefresh(result)) {
            val meta = runCatching {
                tmdb.movieMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                // The year already gets its own field right under the title ("2026 · ★ 6.8 ·
                // 2h 10m") — a title that also spells it out (TMDB's own title sometimes does,
                // and so did the provider's) would just say it twice on screen.
                val resolvedYear = meta.year ?: result.year
                result = result.copy(
                    name = VodTitleCleaner.stripRedundantYear(meta.title ?: result.name, resolvedYear),
                    posterUrl = meta.posterUrl ?: result.posterUrl,
                    backdropUrl = meta.backdropUrl ?: result.backdropUrl,
                    plot = meta.overview ?: result.plot,
                    cast = meta.cast ?: result.cast,
                    director = meta.director ?: result.director,
                    genre = meta.genre ?: result.genre,
                    tmdbId = meta.tmdbId ?: result.tmdbId,
                    rating = meta.rating ?: result.rating,
                    year = resolvedYear,
                )
            }
        }

        if (result != movie) movieDao.upsertAll(listOf(result))
        val canonicalId = reconcileMovieCanonical(result)
        if (canonicalId != null && canonicalId != result.canonicalId) {
            movieDao.setCanonical(result.id, canonicalId, CanonicalMatchKind.TMDB)
            result = result.copy(canonicalId = canonicalId, canonicalMatchKind = CanonicalMatchKind.TMDB)
        }
        result
    }

    /** Loads a series and lazily back-fills its detail fields from `get_series_info`. See [movieDetail]. */
    suspend fun seriesDetail(id: Long): Series? = withContext(Dispatchers.IO) {
        val series = seriesDao.byId(id) ?: return@withContext null
        var result = series
        val source = sourceDao.byId(series.sourceId)

        // 1) Provider back-fill from get_series_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.seriesInfo(source, result.seriesId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                )
            }
        }

        // 2) TMDB is the source of truth once matched — see [movieDetail]'s step 2 for why this
        //    overrides rather than just fills gaps, why the gate also re-tries a dirty-looking
        //    name, and why that's what makes it self-healing. TMDB has no director for TV.
        if (tmdb.isConfigured() && needsTmdbRefresh(result)) {
            val meta = runCatching {
                tmdb.seriesMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                val resolvedYear = meta.year ?: result.year
                result = result.copy(
                    name = VodTitleCleaner.stripRedundantYear(meta.title ?: result.name, resolvedYear),
                    posterUrl = meta.posterUrl ?: result.posterUrl,
                    backdropUrl = meta.backdropUrl ?: result.backdropUrl,
                    plot = meta.overview ?: result.plot,
                    cast = meta.cast ?: result.cast,
                    genre = meta.genre ?: result.genre,
                    tmdbId = meta.tmdbId ?: result.tmdbId,
                    rating = meta.rating ?: result.rating,
                    year = resolvedYear,
                )
            }
        }

        if (result != series) seriesDao.upsertAll(listOf(result))
        val canonicalId = reconcileSeriesCanonical(result)
        if (canonicalId != null && canonicalId != result.canonicalId) {
            seriesDao.setCanonical(result.id, canonicalId, CanonicalMatchKind.TMDB)
            result = result.copy(canonicalId = canonicalId, canonicalMatchKind = CanonicalMatchKind.TMDB)
        }
        result
    }

    private fun needsTmdbRefresh(movie: Movie): Boolean =
        movie.tmdbId == null ||
            VodTitleCleaner.clean(movie.name) != movie.name ||
            movie.posterUrl == null || movie.backdropUrl == null || movie.plot.isNullOrBlank() ||
            movie.cast.isNullOrBlank() || movie.genre.isNullOrBlank() || movie.rating == null || movie.year == null

    private fun needsTmdbRefresh(series: Series): Boolean =
        series.tmdbId == null ||
            VodTitleCleaner.clean(series.name) != series.name ||
            series.posterUrl == null || series.backdropUrl == null || series.plot.isNullOrBlank() ||
            series.cast.isNullOrBlank() || series.genre.isNullOrBlank() || series.rating == null || series.year == null

    private suspend fun reconcileMovieCanonical(movie: Movie): Long? {
        val tmdbId = movie.tmdbId?.takeIf { it.isNotBlank() } ?: return movie.canonicalId
        val current = movie.canonicalId?.let { canonicalMovieDao.byId(it) }
        val exact = canonicalMovieDao.findByTmdbId(tmdbId)
        val winner = when {
            exact != null -> exact
            current != null -> current
            else -> return null
        }
        val loser = current?.takeIf { it.id != winner.id }
        val title = VodTitleCleaner.stripRedundantYear(movie.name, movie.year)
        canonicalMovieDao.update(
            winner.copy(
                tmdbId = tmdbId,
                title = title,
                titleKey = CanonicalMatcher.keyOf(title, movie.year).titleKey,
                year = movie.year ?: winner.year,
                posterUrl = movie.posterUrl ?: winner.posterUrl,
                backdropUrl = movie.backdropUrl ?: winner.backdropUrl,
                plot = movie.plot ?: winner.plot,
                rating = movie.rating ?: winner.rating,
                genre = movie.genre ?: winner.genre,
                cast = movie.cast ?: winner.cast,
                director = movie.director ?: winner.director,
                favourite = winner.favourite || (loser?.favourite == true),
                lastViewedMillis = maxOf(winner.lastViewedMillis, loser?.lastViewedMillis ?: 0L),
                alsoKnownAs = mergeAlsoKnownAs(
                    mergeAlsoKnownAs(winner.alsoKnownAs, title, winner.title),
                    title,
                    loser?.title.orEmpty(),
                ),
            ),
        )
        if (loser != null) {
            canonicalMovieDao.repointVariants(loser.id, winner.id)
            migratePreferredVariant("movie:${loser.id}", "movie:${winner.id}")
            canonicalMovieDao.delete(loser.id)
        }
        return winner.id
    }

    private suspend fun reconcileSeriesCanonical(series: Series): Long? {
        val tmdbId = series.tmdbId?.takeIf { it.isNotBlank() } ?: return series.canonicalId
        val current = series.canonicalId?.let { canonicalSeriesDao.byId(it) }
        val exact = canonicalSeriesDao.findByTmdbId(tmdbId)
        val winner = when {
            exact != null -> exact
            current != null -> current
            else -> return null
        }
        val loser = current?.takeIf { it.id != winner.id }
        val title = VodTitleCleaner.stripRedundantYear(series.name, series.year)
        canonicalSeriesDao.update(
            winner.copy(
                tmdbId = tmdbId,
                title = title,
                titleKey = CanonicalMatcher.keyOf(title, series.year).titleKey,
                year = series.year ?: winner.year,
                posterUrl = series.posterUrl ?: winner.posterUrl,
                backdropUrl = series.backdropUrl ?: winner.backdropUrl,
                plot = series.plot ?: winner.plot,
                rating = series.rating ?: winner.rating,
                genre = series.genre ?: winner.genre,
                cast = series.cast ?: winner.cast,
                favourite = winner.favourite || (loser?.favourite == true),
                lastViewedMillis = maxOf(winner.lastViewedMillis, loser?.lastViewedMillis ?: 0L),
                alsoKnownAs = mergeAlsoKnownAs(
                    mergeAlsoKnownAs(winner.alsoKnownAs, title, winner.title),
                    title,
                    loser?.title.orEmpty(),
                ),
            ),
        )
        if (loser != null) {
            mergeCanonicalSeriesEpisodes(loser.id, winner.id)
            canonicalSeriesDao.repointVariants(loser.id, winner.id)
            migratePreferredVariant("series:${loser.id}", "series:${winner.id}")
            canonicalSeriesDao.delete(loser.id)
        }
        return winner.id
    }

    private suspend fun mergeCanonicalSeriesEpisodes(loserSeriesId: Long, winnerSeriesId: Long) {
        for (losingEpisode in canonicalEpisodeDao.forSeries(loserSeriesId)) {
            val existing = canonicalEpisodeDao.findBySlot(winnerSeriesId, losingEpisode.season, losingEpisode.episodeNumber)
            if (existing == null) {
                canonicalEpisodeDao.update(losingEpisode.copy(canonicalSeriesId = winnerSeriesId))
            } else {
                canonicalEpisodeDao.repointVariants(losingEpisode.id, existing.id)
                migratePreferredVariant("episode:${losingEpisode.id}", "episode:${existing.id}")
                canonicalEpisodeDao.delete(losingEpisode.id)
            }
        }
    }

    private suspend fun migratePreferredVariant(oldKey: String, newKey: String) {
        val old = preferredVariantDao.get(oldKey) ?: return
        val current = preferredVariantDao.get(newKey)
        preferredVariantDao.upsert(
            old.copy(
                contentKey = newKey,
                sourceKey = current?.sourceKey ?: old.sourceKey,
                qualityKey = current?.qualityKey ?: old.qualityKey,
                engineKey = current?.engineKey ?: old.engineKey,
                updatedAtMillis = maxOf(current?.updatedAtMillis ?: 0L, old.updatedAtMillis),
            ),
        )
        preferredVariantDao.clear(oldKey)
    }

    /**
     * Collapses a list of movies (e.g. one category's titles) into logical films with switchable
     * quality tiers — see [collapseMovieVariants]. Pure; hand it whatever list the UI is about to
     * show. Kept here as the discoverable entry point for the VOD UI.
     */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> = collapseMovieVariants(movies)

    // -- feed helpers --

    /** A movie counts as "already enriched" once any headline detail field is set, so [movieDetail]
     *  re-fetches only truly-bare rows. A movie the provider has no metadata for re-fetches each open;
     *  acceptable, and it self-limits the moment anything comes back. */
    private val Movie.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null || director != null

    private val Series.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null

    /** Splits a provider genre string ("Action, Thriller" / "Action|Thriller") into clean genres. */
    private fun splitGenres(raw: String?): List<String> =
        raw?.split(',', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    private fun <T> groupByGenre(
        items: List<T>,
        genreOf: (T) -> String?,
        maxGenres: Int,
        perGenre: Int,
    ): List<GenreGroup<T>> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            for (genre in splitGenres(genreOf(item))) {
                buckets.getOrPut(genre) { mutableListOf() }.add(item)
            }
        }
        return buckets.entries
            .sortedByDescending { it.value.size }
            .take(maxGenres)
            .map { GenreGroup(it.key, it.value.take(perGenre)) }
    }

    /** How strongly a title matches a profile's genre affinity: sum of its genres' tallies. */
    private fun genreScore(genre: String?, affinity: Map<String, Int>): Int =
        splitGenres(genre).sumOf { affinity[it] ?: 0 }

    /** How many of a title's genres are in the wanted set — the More-Like-This overlap. */
    private fun sharedGenreCount(genre: String?, wanted: Set<String>): Int =
        splitGenres(genre).count { it in wanted }

    /** Extracts a movie's local id from a playback-position mediaKey ("movie:42" → 42). */
    private fun movieIdFromMediaKey(mediaKey: String): Long? {
        val parts = mediaKey.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "movie") parts[1].toLongOrNull() else null
    }

    suspend fun setChannelFavourite(id: Long, favourite: Boolean) =
        channelDao.setFavourite(id, favourite)

    suspend fun setChannelHidden(id: Long, hidden: Boolean) = channelDao.setHidden(id, hidden)

    /** See [ChannelDao.setHiddenForCategories]. */
    suspend fun setChannelsHiddenForCategories(categoryIds: List<String>, hidden: Boolean) =
        channelDao.setHiddenForCategories(categoryIds, hidden)

    /** Sets (or clears, on blank) a channel's manual rename. Trimmed; a blank name clears it. */
    suspend fun setChannelCustomName(id: Long, name: String?) =
        channelDao.setCustomName(id, name?.trim()?.takeIf { it.isNotBlank() })

    suspend fun setChannelSortIndex(id: Long, sortIndex: Int) = channelDao.setSortIndex(id, sortIndex)

    // --- Web channel manager: one-shot reads for the socket-thread server ------------------------
    // The manager server handles one HTTP request at a time off the UI thread and wants plain
    // lists, not Flows. These are read-only pass-throughs; every mutation still goes through the
    // suspend setters above (setChannelHidden/Favourite/CustomName/SortIndex).

    /** Enabled providers, for the manager's source selector. */
    suspend fun enabledSources(): List<Source> = sourceDao.enabled()

    /** Every live category (raw, un-folded), for the manager's category list. */
    suspend fun liveCategories(): List<Category> = categoryDao.allByKind(StreamKind.LIVE)

    /** Channel counts per (source, category) — the number shown next to each category. */
    suspend fun categoryChannelCounts(): List<app.tufaratv.data.db.CategoryChannelCount> =
        channelDao.channelCountsByCategory()

    /** One category's channels, hidden included, optionally scoped to a single source. */
    suspend fun channelsInCategoryForManager(sourceId: Long?, categoryId: String): List<Channel> =
        channelDao.channelsInCategoryIncludingHidden(sourceId, categoryId)

    suspend fun setMovieFavourite(id: Long, favourite: Boolean) =
        movieDao.setFavourite(id, favourite)

    // --- Sync helpers: read/apply curation by stable stream URL ---
    suspend fun favouriteChannelUrls(): List<String> = channelDao.favouriteUrls()
    suspend fun hiddenChannelUrls(): List<String> = channelDao.hiddenUrls()
    suspend fun markChannelFavouriteByUrl(url: String) = channelDao.markFavouriteByUrl(url)
    suspend fun markChannelHiddenByUrl(url: String) = channelDao.markHiddenByUrl(url)
    suspend fun favouriteMovieUrls(): List<String> = movieDao.favouriteUrls()
    suspend fun markMovieFavouriteByUrl(url: String) = movieDao.markFavouriteByUrl(url)
    suspend fun favouriteSeriesIds(): List<String> = seriesDao.favouriteSeriesIds()
    suspend fun markSeriesFavouriteBySeriesId(seriesId: String) =
        seriesDao.markFavouriteBySeriesId(seriesId)

    /**
     * Series episodes are fetched lazily — panels are slow and most series are never opened.
     *
     * [knownTmdbId], when supplied, is preferred over the per-source [Series.tmdbId] column for
     * the episode-metadata backfill below. A title like "Homeland" is ambiguous enough (dozens of
     * unrelated movies/shows share the name) that [CatalogRepository.seriesDetail]'s own title
     * search can legitimately fail to resolve it and leave that column null, even though the
     * screen the user is actually looking at already knows the real id — it came straight from a
     * TMDB browse/search card. Without this, episode names/synopsis/stills silently never
     * backfill for exactly the ambiguous-title series most likely to need it.
     */
    suspend fun ensureEpisodes(source: Source, seriesId: String, knownTmdbId: String? = null) {
        runCatching {
            when (source.kind) {
                SourceKind.XTREAM -> api.episodes(source, seriesId)
                SourceKind.STALKER -> stalkerApi.seriesEpisodes(source, seriesId)
                SourceKind.M3U -> emptyList()
            }
        }
            .onSuccess { episodes ->
                if (episodes.isEmpty()) return@onSuccess
                val stamped = stampedEpisodeQuality(episodes)
                episodeDao.upsertAll(stamped)
                val seriesRow = seriesDao.bySourceAndSeriesId(source.id, seriesId)
                val canonicalSeriesId = seriesRow?.canonicalId
                if (canonicalSeriesId != null) linkEpisodesToCanonical(source.id, seriesId, canonicalSeriesId)
                val tmdbId = knownTmdbId?.takeIf { it.isNotBlank() } ?: seriesRow?.tmdbId
                // Re-read rather than reusing `stamped`: those objects are always id=0 (freshly
                // parsed, never round-tripped through Room), and upsertAll's conflict fallback can
                // only match an existing row by id — handing it id=0 rows a second time here would
                // silently update nothing every single time this runs. See EpisodeDao.forSeries.
                val persisted = episodeDao.forSeries(source.id, seriesId)
                backfillEpisodeTmdbMeta(tmdbId, persisted)
            }
            .onFailure { Log.w(TAG, "Episode fetch failed for series $seriesId", it) }
    }

    /**
     * TMDB overrides a provider's per-episode synopsis/still once matched — same "TMDB wins, not
     * just fills gaps" rule as [movieDetail]/[seriesDetail]'s step 2, and for the same reason: a
     * provider's own `info.movie_image` is routinely the *series* cover art reused identically for
     * every episode rather than a real per-episode still, which "only fill blanks" can never catch
     * since the field isn't blank, just wrong. One TMDB call per *season* covers every one of that
     * season's episodes ([TmdbClient.seasonEpisodes]), so a whole series costs one request per
     * season, not one per episode — always run (not just for "bare" rows) is what actually fixes a
     * provider's reused-cover-art episodes, matching every other TMDB-first surface in this app.
     */
    private suspend fun backfillEpisodeTmdbMeta(seriesTmdbId: String?, episodes: List<Episode>) {
        if (seriesTmdbId.isNullOrBlank() || !tmdb.isConfigured()) return
        // TmdbClient blocks the calling thread by design (see its doc comment) — ensureEpisodes'
        // own caller isn't guaranteed to already be off the main thread the way movieDetail's is,
        // so this hop is what stands between a real device and a NetworkOnMainThreadException.
        val updated = withContext(Dispatchers.IO) {
            episodes.groupBy { it.season }.flatMap { (season, seasonEpisodes) ->
                val byNumber = runCatching { tmdb.seasonEpisodes(seriesTmdbId, season) }
                    .onFailure { Log.w(TAG, "TMDB season $season fetch failed for series $seriesTmdbId", it) }
                    .getOrDefault(emptyMap())
                if (byNumber.isEmpty()) return@flatMap emptyList()
                seasonEpisodes.mapNotNull { ep ->
                    val meta = byNumber[ep.episodeNumber] ?: return@mapNotNull null
                    val merged = ep.copy(
                        title = meta.name ?: ep.title,
                        plot = meta.overview ?: ep.plot,
                        stillUrl = meta.stillUrl ?: ep.stillUrl,
                    )
                    merged.takeIf { it != ep }
                }
            }
        }
        if (updated.isNotEmpty()) episodeDao.upsertAll(updated)
    }

    /** Every playable source behind a canonical movie — what [app.tufaratv.player.PlayerCoordinator]
     *  and the Source/Quality panels in `VodPlayerScreen` are built from. Empty for a canonicalId
     *  that doesn't exist (never thrown), so a caller can always fall back to a single flat URL. */
    suspend fun movieVariants(canonicalId: Long): List<SourceVariant> =
        withContext(Dispatchers.IO) { preferredLanguageMovieVariants(canonicalMovieDao.variantsFor(canonicalId)) }

    /** Movies: only ever offer a French, Italian or multi-language copy as a playable choice — a
     *  standing preference, not per-title, and deliberate about excluding rather than falling back
     *  to "show it anyway": a title that's only available in a language outside this list should
     *  read as unavailable, not hand over a source in a language nobody asked for. A variant whose
     *  [SourceVariant.language] wasn't recognised at all (most titles: providers rarely tag every
     *  one, only the ones worth calling out) is kept rather than dropped, since an unlabelled copy
     *  is just as likely to already be French as anything else — only an *explicitly* different
     *  language tag (`EN`, `ES`, `AR`, …) is what this actually filters out. */
    private fun preferredLanguageMovieVariants(all: List<SourceVariant>): List<SourceVariant> =
        all.filter { it.language == null || it.language.uppercase() in PREFERRED_MOVIE_LANGUAGES }

    /** See [movieVariants] — the per-episode equivalent, since a series has no stream URL of its
     *  own to play (only its episodes do). */
    suspend fun episodeVariants(canonicalEpisodeId: Long): List<SourceVariant> =
        withContext(Dispatchers.IO) { canonicalEpisodeDao.variantsFor(canonicalEpisodeId) }

    // ---- TMDB-catalog availability: local-only lookup, never a network call ----------------------
    // The browse catalog (Trending/Popular/Discover/Search, see TmdbClient) is TMDB's own — every
    // title it lists exists whether or not any synced playlist happens to carry it. What a synced
    // Xtream/M3U playlist supplies is treated as an *availability index* under that catalog: at
    // sync time CanonicalMatcher already links a provider's own row to a CanonicalContent by tmdbId
    // when the provider gave one, else by normalized title+year (see its own doc comment for the
    // full precedence) — the exact fallback chain a TMDB-first flow needs, already built and
    // already running on every sync. So resolving "what can I actually watch this as" is a plain,
    // already-indexed Room lookup, not a fresh Xtream request — critical for a TMDB browse screen,
    // where a person taps through many titles per minute and a per-tap network round trip (worse,
    // one to the *provider*, which has no bulk lookup) would be a very felt delay.

    /** Every local source behind a TMDB movie, by [tmdbId] first (exact — what a provider's own
     *  `tmdb_id` field, or an earlier enrichment pass, already recorded) then by this same
     *  title+year key [app.tufaratv.data.parser.CanonicalMatcher] itself matches on at sync time.
     *  [MovieAvailability.canonicalId] is null and [MovieAvailability.variants] empty — never a
     *  network call, never an exception — when nothing synced matches. The id travels with the
     *  variants because the player's Source/Quality panels key their remembered pick on it
     *  (`"movie:<canonicalId>"`), same as a movie opened the ordinary, already-synced way. */
    suspend fun movieAvailabilityForTmdb(tmdbId: String, title: String, year: Int?): MovieAvailability =
        withContext(Dispatchers.IO) {
            val canonical = canonicalMovieDao.findByTmdbId(tmdbId) ?: run {
                val titleKey = CanonicalMatcher.keyOf(title).titleKey
                canonicalMovieDao.findAllByTitleKey(titleKey)
                    .firstOrNull { it.year == null || year == null || it.year == year }
            }
            MovieAvailability(
                canonicalId = canonical?.id,
                variants = canonical?.let { preferredLanguageMovieVariants(canonicalMovieDao.variantsFor(it.id)) }.orEmpty(),
            )
        }

    /** The local per-source `Series` row id behind a TMDB show, by the same tmdbId-then-title+year
     *  chain as [movieAvailabilityForTmdb] — resolved one step further than the movie case, all
     *  the way to a real row id, because a series has no stream of its own to hand back (only its
     *  episodes do): this is what a TMDB series screen opens the *existing* [seriesDetail] /
     *  episode-list flow with, unmodified, rather than reimplementing it against a canonical id. */
    suspend fun localSeriesIdForTmdb(tmdbId: String, title: String, year: Int?): Long? =
        withContext(Dispatchers.IO) {
            val canonical = canonicalSeriesDao.findByTmdbId(tmdbId) ?: run {
                val titleKey = CanonicalMatcher.keyOf(title).titleKey
                canonicalSeriesDao.findAllByTitleKey(titleKey)
                    .firstOrNull { it.year == null || year == null || it.year == year }
            }
            canonical?.let { seriesDao.byCanonicalIds(listOf(it.id)).firstOrNull()?.id }
                ?: seriesDao.all().firstOrNull { local ->
                    val localKey = CanonicalMatcher.keyOf(local.name, local.year)
                    localKey.titleKey == CanonicalMatcher.keyOf(title, year).titleKey &&
                        (local.year == null || year == null || local.year == year)
                }?.id
        }

    /** Narrows a TMDB browse-list page down to titles this device can actually watch — every item's
     *  [movieAvailabilityForTmdb]/[localSeriesIdForTmdb] lookup run concurrently, still a purely
     *  local/indexed check. Home and Search rows use this so a poster is never a dead end. */
    suspend fun filterAvailable(items: List<TmdbListItem>): List<TmdbListItem> = withContext(Dispatchers.IO) {
        val movieItems = items.filter(TmdbListItem::isMovie)
        val seriesItems = items.filterNot(TmdbListItem::isMovie)
        val movies = movieItems.takeIf { it.isNotEmpty() }?.let { requested ->
            canonicalMovieDao.availabilityCandidates(
                tmdbIds = requested.map(TmdbListItem::tmdbId).distinct(),
                titleKeys = requested.map {
                    CanonicalMatcher.keyOf(it.title, it.year).titleKey
                }.distinct(),
            ).asAvailabilityCandidates()
        }
        val series = seriesItems.takeIf { it.isNotEmpty() }?.let { requested ->
            canonicalSeriesDao.availabilityCandidates(
                tmdbIds = requested.map(TmdbListItem::tmdbId).distinct(),
                titleKeys = requested.map {
                    CanonicalMatcher.keyOf(it.title, it.year).titleKey
                }.distinct(),
            ).asAvailabilityCandidates()
        }
        items.filter { item ->
            if (item.isMovie) movies?.contains(item) == true else series?.contains(item) == true
        }.distinctBy { it.tmdbId }
    }

    // ---- TMDB browse catalog: thin IO-dispatched pass-throughs to TmdbClient ----------------------

    suspend fun tmdbTrending(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) { runCatching { tmdb.trending(isMovie, page) }.getOrDefault(emptyList()) }

    suspend fun tmdbPopular(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) { runCatching { tmdb.popular(isMovie, page) }.getOrDefault(emptyList()) }

    suspend fun tmdbRecentlyReleased(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) { runCatching { tmdb.recentlyReleased(isMovie, page) }.getOrDefault(emptyList()) }

    suspend fun tmdbTopRated(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) { runCatching { tmdb.topRated(isMovie, page) }.getOrDefault(emptyList()) }

    suspend fun tmdbGenres(isMovie: Boolean): List<TmdbGenre> =
        withContext(Dispatchers.IO) { runCatching { tmdb.genres(isMovie) }.getOrDefault(emptyList()) }

    suspend fun tmdbDiscoverByGenre(isMovie: Boolean, genreId: Int, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) {
            runCatching { tmdb.discoverByGenre(isMovie, genreId, page) }.getOrDefault(emptyList())
        }

    suspend fun tmdbSearch(query: String, isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) { runCatching { tmdb.search(query, isMovie, page) }.getOrDefault(emptyList()) }

    suspend fun tmdbRecommendations(tmdbId: String, isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        withContext(Dispatchers.IO) {
            runCatching { tmdb.recommendations(tmdbId, isMovie, page) }.getOrDefault(emptyList())
        }

    /** One shelf per TMDB genre (Action, Comédie, Horreur…) — the Netflix-style genre rail. Genres
     *  fetched once, then every genre's discover page is requested concurrently rather than one
     *  request after another, so a dozen-genre row of shelves still lands in about one round trip's
     *  worth of time. A genre with no results (rare, but possible for a niche TV genre) is dropped
     *  rather than shown empty. */
    suspend fun tmdbGenreRows(isMovie: Boolean, maxGenres: Int = 12, perGenre: Int = 60): List<GenreGroup<TmdbListItem>> =
        withContext(Dispatchers.IO) {
            val genres = runCatching { tmdb.genres(isMovie) }.getOrDefault(emptyList()).take(maxGenres)
            coroutineScope {
                genres.map { genre ->
                    async {
                        // A provider title may sit beyond TMDB's first 20 global results. Scan a
                        // few pages so the strict provider-availability filter still leaves enough
                        // populated genre shelves to feel like a complete streaming catalogue.
                        val items = (1..2).flatMap { page ->
                            runCatching { tmdb.discoverByGenre(isMovie, genre.id, page) }
                                .getOrDefault(emptyList())
                        }.distinctBy { it.tmdbId }
                            .take(perGenre)
                        GenreGroup(genre.name, items)
                    }
                }.awaitAll()
            }.filter { it.items.isNotEmpty() }
        }

    /** Full detail for one TMDB item, by id — the same lookup [movieDetail]/[seriesDetail] use for
     *  enrichment, exposed directly for a TMDB-catalog detail screen that may have no local movie
     *  or series row at all to enrich. Null with no key configured or no such id. */
    suspend fun tmdbDetail(tmdbId: String, isMovie: Boolean): TmdbMeta? =
        withContext(Dispatchers.IO) {
            if (!tmdb.isConfigured()) return@withContext null
            runCatching {
                if (isMovie) tmdb.movieMeta("", null, tmdbId) else tmdb.seriesMeta("", null, tmdbId)
            }.getOrNull()
        }

    fun tmdbConfigured(): Boolean = tmdb.isConfigured()

    // ---- Preferred source/quality/engine, per title (PlayerCoordinator's three panels) -----------
    // One row per contentKey ("movie:<id>" / "series:<id>" / "episode:<id>" / "channel:<groupKey>"),
    // three independently-nullable axes. See PreferredVariant's own doc comment.

    suspend fun preferredVariant(contentKey: String): PreferredVariant? = preferredVariantDao.get(contentKey)

    fun observePreferredVariant(contentKey: String): Flow<PreferredVariant?> = preferredVariantDao.observe(contentKey)

    /** Null clears that axis back to Auto. Reads the existing row (if any) so the other two axes
     *  are left exactly as they were — see [PreferredVariantDao]'s doc comment for why this can't
     *  just be a raw single-column SQL upsert. */
    suspend fun setPreferredSource(contentKey: String, sourceId: Long?) =
        upsertPreferredAxis(contentKey) { it.copy(sourceKey = sourceId?.toString()) }

    suspend fun setPreferredQuality(contentKey: String, qualityLabel: String?) =
        upsertPreferredAxis(contentKey) { it.copy(qualityKey = qualityLabel) }

    suspend fun setPreferredEngine(contentKey: String, engineName: String?) =
        upsertPreferredAxis(contentKey) { it.copy(engineKey = engineName) }

    private suspend fun upsertPreferredAxis(contentKey: String, change: (PreferredVariant) -> PreferredVariant) {
        val current = preferredVariantDao.get(contentKey) ?: PreferredVariant(contentKey = contentKey)
        preferredVariantDao.upsert(change(current).copy(updatedAtMillis = System.currentTimeMillis()))
    }

    // ---- Canonical multi-source catalog: linking newly-synced variants ---------------------------
    // Runs right after each sync's upsert, entirely local (no TMDB calls) — see CanonicalMatcher's
    // doc comment for why. A row that fails to link here (e.g. the app crashes mid-sync) is simply
    // picked up by pendingCanonicalLink on the next sync; nothing is lost.

    /** Links every movie this sync just wrote (and hasn't linked yet) into the canonical catalog. */
    private suspend fun linkMoviesToCanonical(sourceId: Long) {
        movieDao.pendingCanonicalLink(sourceId).chunked(CANONICAL_LINK_BATCH_SIZE).forEach { batch ->
            database.withTransaction {
                for (movie in batch) {
            val candidate = CanonicalMatcher.Candidate(movie.name, movie.year, movie.tmdbId)
            val existingByTmdb = movie.tmdbId?.let { canonicalMovieDao.findByTmdbId(it) }
            val decision = CanonicalMatcher.decide(
                candidate = candidate,
                existingByTmdbId = existingByTmdb,
                existingByTitleKey = canonicalMovieDao.findAllByTitleKey(
                    CanonicalMatcher.keyOf(movie.name, movie.year).titleKey,
                ),
            )
            val canonicalId = when (decision.action) {
                CanonicalMatcher.Action.LINK_EXISTING -> {
                    val existing = canonicalMovieDao.byId(decision.existingId!!)
                    if (existing != null) mergeIntoExistingMovie(existing, decision, movie)
                    decision.existingId
                }
                CanonicalMatcher.Action.CREATE_NEW -> canonicalMovieDao.insert(newCanonicalFromMovie(decision, movie))
            }
            movieDao.setCanonical(movie.id, canonicalId, decision.matchKind)
                }
            }
        }
    }

    /** Links every series this sync just wrote (and hasn't linked yet) into the canonical catalog. */
    private suspend fun linkSeriesToCanonical(sourceId: Long) {
        seriesDao.pendingCanonicalLink(sourceId).chunked(CANONICAL_LINK_BATCH_SIZE).forEach { batch ->
            database.withTransaction {
                for (series in batch) {
            val candidate = CanonicalMatcher.Candidate(series.name, series.year, series.tmdbId)
            val existingByTmdb = series.tmdbId?.let { canonicalSeriesDao.findByTmdbId(it) }
            val decision = CanonicalMatcher.decide(
                candidate = candidate,
                existingByTmdbId = existingByTmdb,
                existingByTitleKey = canonicalSeriesDao.findAllByTitleKey(
                    CanonicalMatcher.keyOf(series.name, series.year).titleKey,
                ),
            )
            val canonicalId = when (decision.action) {
                CanonicalMatcher.Action.LINK_EXISTING -> {
                    val existing = canonicalSeriesDao.byId(decision.existingId!!)
                    if (existing != null) mergeIntoExistingSeries(existing, decision, series)
                    decision.existingId
                }
                CanonicalMatcher.Action.CREATE_NEW -> canonicalSeriesDao.insert(newCanonicalFromSeries(decision, series))
            }
            seriesDao.setCanonical(series.id, canonicalId, decision.matchKind)
                }
            }
        }
    }

    /**
     * Links a series' freshly-fetched episodes into its canonical (season, episode) slots — see
     * [CanonicalEpisode]. Matching here is exact ([season], [Episode.episodeNumber]) rather than
     * title-based: two providers numbering the same show's episodes identically is far more
     * reliable than their episode titles agreeing, which vary wildly (recaps, translated titles).
     */
    private suspend fun linkEpisodesToCanonical(sourceId: Long, seriesId: String, canonicalSeriesId: Long) {
        for (episode in episodeDao.pendingCanonicalLink(sourceId, seriesId)) {
            val existing = canonicalEpisodeDao.findBySlot(canonicalSeriesId, episode.season, episode.episodeNumber)
            val canonicalEpisodeId = existing?.id ?: canonicalEpisodeDao.insert(
                CanonicalEpisode(
                    canonicalSeriesId = canonicalSeriesId,
                    season = episode.season,
                    episodeNumber = episode.episodeNumber,
                    title = episode.title,
                    plot = episode.plot,
                    stillUrl = episode.stillUrl,
                ),
            )
            episodeDao.setCanonical(episode.id, canonicalEpisodeId, CanonicalMatchKind.TITLE_ONLY)
        }
    }

    private fun newCanonicalFromMovie(decision: CanonicalMatcher.Decision, movie: Movie) = CanonicalContent(
        kind = CanonicalKind.MOVIE,
        tmdbId = movie.tmdbId,
        title = decision.key.displayTitle,
        titleKey = decision.key.titleKey,
        year = decision.key.year,
        posterUrl = movie.posterUrl,
        backdropUrl = movie.backdropUrl,
        plot = movie.plot,
        rating = movie.rating,
        genre = movie.genre,
        cast = movie.cast,
        director = movie.director,
        addedMillis = System.currentTimeMillis(),
    )

    private fun newCanonicalFromSeries(decision: CanonicalMatcher.Decision, series: Series) = CanonicalContent(
        kind = CanonicalKind.SERIES,
        tmdbId = series.tmdbId,
        title = decision.key.displayTitle,
        titleKey = decision.key.titleKey,
        year = decision.key.year,
        posterUrl = series.posterUrl,
        backdropUrl = series.backdropUrl,
        plot = series.plot,
        rating = series.rating,
        genre = series.genre,
        cast = series.cast,
        addedMillis = System.currentTimeMillis(),
    )

    /** Backfills a yearless canonical entry's year, and tracks this variant's own wording in
     *  [CanonicalContent.alsoKnownAs] if it differs from the canonical title — see [CanonicalMatcher]. */
    private suspend fun mergeIntoExistingMovie(existing: CanonicalContent, decision: CanonicalMatcher.Decision, movie: Movie) {
        val updated = existing.copy(
            tmdbId = existing.tmdbId ?: movie.tmdbId.takeIf { decision.matchKind == CanonicalMatchKind.TMDB },
            year = existing.year ?: decision.key.year,
            posterUrl = existing.posterUrl ?: movie.posterUrl,
            backdropUrl = existing.backdropUrl ?: movie.backdropUrl,
            plot = existing.plot ?: movie.plot,
            rating = existing.rating ?: movie.rating,
            genre = existing.genre ?: movie.genre,
            cast = existing.cast ?: movie.cast,
            director = existing.director ?: movie.director,
            alsoKnownAs = mergeAlsoKnownAs(existing.alsoKnownAs, existing.title, decision.key.displayTitle),
        )
        if (updated != existing) canonicalMovieDao.update(updated)
    }

    private suspend fun mergeIntoExistingSeries(existing: CanonicalContent, decision: CanonicalMatcher.Decision, series: Series) {
        val updated = existing.copy(
            tmdbId = existing.tmdbId ?: series.tmdbId.takeIf { decision.matchKind == CanonicalMatchKind.TMDB },
            year = existing.year ?: decision.key.year,
            posterUrl = existing.posterUrl ?: series.posterUrl,
            backdropUrl = existing.backdropUrl ?: series.backdropUrl,
            plot = existing.plot ?: series.plot,
            rating = existing.rating ?: series.rating,
            genre = existing.genre ?: series.genre,
            cast = existing.cast ?: series.cast,
            alsoKnownAs = mergeAlsoKnownAs(existing.alsoKnownAs, existing.title, decision.key.displayTitle),
        )
        if (updated != existing) canonicalSeriesDao.update(updated)
    }

    /** `" | "`-joined, deduped (case-insensitive) — see [CanonicalContent.alsoKnownAs]. */
    private fun mergeAlsoKnownAs(existing: String?, canonicalTitle: String, candidateTitle: String): String? {
        if (candidateTitle.isBlank() || candidateTitle.equals(canonicalTitle, ignoreCase = true)) return existing
        val names = existing?.split(" | ")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (names.none { it.equals(candidateTitle, ignoreCase = true) }) names.add(candidateTitle)
        return names.joinToString(" | ")
    }

    /**
     * Full catalogue: live channels, then movies and series. Used by the periodic worker and by a
     * manual refresh, where there's no user staring at a spinner. The initial add takes the faster
     * [syncLive] + background [syncVod] path instead, so the guide appears without waiting for a
     * 40,000-title VOD list.
     */
    suspend fun sync(source: Source, nowUtcMillis: Long): SyncResult = withContext(Dispatchers.IO) {
        // Skip fetching a content type the user has switched off — that's the whole speed-up.
        // Live is gated here (not in syncLive) so onboarding's direct syncLive still loads channels.
        // Already-synced rows are left untouched: turning a type back on and refreshing restores it.
        val live =
            if (settings.liveEnabled.value) syncLive(source, nowUtcMillis)
            else SyncResult.Success(0, 0, 0)
        if (live is SyncResult.Success) {
            // Keep refresh semantics identical across provider protocols. Xtream, Stalker/Ministra
            // and M3U+ all get the same VOD refresh hook; protocol-specific behavior lives inside
            // syncVod rather than leaking into the scheduler.
            syncVod(source, nowUtcMillis)
        }
        live
    }

    /** Live channels only — the fast path so the guide can show before VOD and the guide load. */
    suspend fun syncLive(source: Source, nowUtcMillis: Long): SyncResult = withContext(Dispatchers.IO) {
        try {
            when (source.kind) {
                SourceKind.XTREAM -> syncXtreamLive(source, nowUtcMillis)
                SourceKind.M3U -> syncM3u(source, nowUtcMillis)
                SourceKind.STALKER -> syncStalkerLive(source, nowUtcMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Catalogue sync failed for source ${source.id}", e)
            SyncResult.Failed(e.message ?: "Impossible de télécharger le catalogue.", e)
        }
    }

    /** Movies + series — best-effort, meant to run in the background so a huge VOD list never
     * blocks live TV. Silent on failure: an account with no VOD is normal, not an error.
     * [onProgress], when supplied, is called with the running (movies, series) counts as batches
     * land — a large Stalker catalogue streams in over many small pages, so this is what lets a
     * caller show real, moving progress instead of one static "please wait" message for the whole
     * multi-minute sync. */
    suspend fun syncVod(
        source: Source,
        nowUtcMillis: Long,
        includeMovies: Boolean = settings.moviesEnabled.value,
        includeSeries: Boolean = settings.seriesEnabled.value,
        onProgress: ((movies: Int, series: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                SourceKind.XTREAM -> syncXtreamVod(source, nowUtcMillis, includeMovies, includeSeries, onProgress)
                SourceKind.STALKER -> syncStalkerVod(source, includeMovies, includeSeries, onProgress)
                SourceKind.M3U -> {}
            }
        }.onFailure { Log.w(TAG, "VOD sync failed for source ${source.id}", it) }
            .also {
            }
    }

    private suspend fun syncXtreamLive(source: Source, nowUtcMillis: Long): SyncResult {
        // Authenticate first so a wrong password produces a clear message rather than
        // four separate confusing failures further down.
        api.authenticate(source)

        val liveCategories = api.liveCategories(source)
        val channels = api.liveStreams(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "Le serveur n’a renvoyé aucune chaîne. Aucun forfait n’est peut-être associé au compte.",
                null,
            )
        }

        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * Stalker/Ministra live sync. Mirrors [syncXtreamLive] but the channels it stores carry a
     * [Channel.cmd] instead of a directly-playable URL — [resolvePlaybackUrl] mints the real URL at
     * play time. The handshake happens inside [StalkerApi] on first call; a bad MAC or URL throws
     * here with a clear message, caught by [syncLive].
     */
    private suspend fun syncStalkerLive(source: Source, nowUtcMillis: Long): SyncResult {
        val liveCategories = stalkerApi.liveCategories(source)
        val channels = stalkerApi.liveChannels(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "Le portail n’a renvoyé aucune chaîne. L’adresse MAC n’est peut-être pas autorisée ou son forfait est vide.",
                null,
            )
        }
        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * The URL to actually feed the player for [channel]. Xtream/M3U channels already carry a playable
     * [Channel.streamUrl]; a Stalker channel's real URL is short-lived, so it's minted now via
     * create_link from the channel's [Channel.cmd]. Falls back to the stored streamUrl if resolution
     * fails, so the player surfaces an error rather than silently doing nothing.
     */
    suspend fun resolvePlaybackUrl(channel: Channel, source: Source?): String {
        if (source?.kind != SourceKind.STALKER) return channel.streamUrl
        val cmd = channel.cmd?.takeIf { it.isNotBlank() } ?: return channel.streamUrl
        return runCatching { stalkerApi.createLink(source, cmd) }.getOrNull() ?: channel.streamUrl
    }

    private suspend fun syncXtreamVod(
        source: Source,
        nowUtcMillis: Long,
        includeMovies: Boolean,
        includeSeries: Boolean,
        onProgress: ((movies: Int, series: Int) -> Unit)? = null,
    ) {
        // VOD is optional: plenty of accounts have live TV only, and a 404 on get_vod_streams
        // must not cost the user their channel list. Movies and series are gated independently so
        // a user who only turned off, say, Series still gets their movie library refreshed.
        val moviesOn = settings.moviesEnabled.value && includeMovies
        val seriesOn = settings.seriesEnabled.value && includeSeries
        if (!moviesOn && !seriesOn) return

        val movieCategories =
            if (moviesOn) runCatching { api.movieCategories(source) }.getOrDefault(emptyList())
            else emptyList()
        val movies =
            if (moviesOn) runCatching { api.movies(source) }.getOrDefault(emptyList())
            else emptyList()
        val seriesCategories =
            if (seriesOn) runCatching { api.seriesCategories(source) }.getOrDefault(emptyList())
            else emptyList()
        val series =
            if (seriesOn) runCatching { api.series(source) }.getOrDefault(emptyList())
            else emptyList()

        if (movieCategories.isNotEmpty() || seriesCategories.isNotEmpty()) {
            categoryDao.upsertAll(movieCategories + seriesCategories)
        }
        if (movies.isNotEmpty()) movieDao.upsertAll(stampedMovieQuality(movies))
        if (series.isNotEmpty()) seriesDao.upsertAll(stampedSeriesQuality(series))
        onProgress?.invoke(movies.size, series.size)
        if (movies.isNotEmpty()) linkMoviesToCanonical(source.id)
        if (series.isNotEmpty()) linkSeriesToCanonical(source.id)
    }

    /**
     * Stalker/Ministra VOD sync — movies, and series as top-level catalogue entries (episodes
     * fetched separately and lazily by [ensureEpisodes] once a series is opened). Movies/series
     * are upserted in small batches as [StalkerApi.vodMovies]/[StalkerApi.seriesList] stream them
     * page by page, not collected into one big in-memory list first — confirmed necessary against
     * a real 33,802-movie catalogue that ran the app out of memory and crashed it mid-sync when
     * this held everything at once (see those functions' own doc comments). The canonical-linking
     * pass still runs once at the end, after every batch has landed.
     */
    private suspend fun syncStalkerVod(
        source: Source,
        includeMovies: Boolean,
        includeSeries: Boolean,
        onProgress: ((movies: Int, series: Int) -> Unit)? = null,
    ) {
        val moviesOn = settings.moviesEnabled.value && includeMovies
        val seriesOn = settings.seriesEnabled.value && includeSeries
        if (!moviesOn && !seriesOn) return

        if (moviesOn) {
            val movieCategories = runCatching { stalkerApi.vodCategories(source) }.getOrDefault(emptyList())
            if (movieCategories.isNotEmpty()) categoryDao.upsertAll(movieCategories)
        }
        if (seriesOn) {
            val seriesCategories = runCatching { stalkerApi.seriesCategories(source) }.getOrDefault(emptyList())
            if (seriesCategories.isNotEmpty()) categoryDao.upsertAll(seriesCategories)
        }

        var movieCount = 0
        var seriesCount = 0
        // Series first: a provider can expose tens of thousands of movies. Making shows wait for
        // that entire crawl left their categories visible but their catalogue empty whenever the
        // app was closed before the movie pass finished.
        var sawSeries = false
        if (seriesOn) {
            val writeMutex = Mutex()
            val pending = ArrayList<Series>(STALKER_DB_BATCH_SIZE)
            runCatching {
                stalkerApi.seriesList(source) { batch ->
                    writeMutex.withLock {
                        pending += batch
                        if (pending.size >= STALKER_DB_BATCH_SIZE) {
                            val chunk = pending.toList()
                            pending.clear()
                            sawSeries = true
                            seriesDao.upsertAll(stampedSeriesQuality(chunk))
                            seriesCount += chunk.size
                            onProgress?.invoke(movieCount, seriesCount)
                        }
                    }
                }
                writeMutex.withLock {
                    if (pending.isNotEmpty()) {
                        val chunk = pending.toList()
                        pending.clear()
                        sawSeries = true
                        seriesDao.upsertAll(stampedSeriesQuality(chunk))
                        seriesCount += chunk.size
                        onProgress?.invoke(movieCount, seriesCount)
                    }
                }
            }.onFailure { Log.w(TAG, "Stalker series sync failed for source ${source.id}", it) }
        }
        if (sawSeries) linkSeriesToCanonical(source.id)

        var sawMovies = false
        if (moviesOn) {
            val writeMutex = Mutex()
            val pending = ArrayList<Movie>(STALKER_DB_BATCH_SIZE)
            runCatching {
                stalkerApi.vodMovies(source) { batch ->
                    writeMutex.withLock {
                        pending += batch
                        if (pending.size >= STALKER_DB_BATCH_SIZE) {
                            val chunk = pending.toList()
                            pending.clear()
                            sawMovies = true
                            movieDao.upsertAll(stampedMovieQuality(chunk))
                            movieCount += chunk.size
                            onProgress?.invoke(movieCount, seriesCount)
                        }
                    }
                }
                writeMutex.withLock {
                    if (pending.isNotEmpty()) {
                        val chunk = pending.toList()
                        pending.clear()
                        sawMovies = true
                        movieDao.upsertAll(stampedMovieQuality(chunk))
                        movieCount += chunk.size
                        onProgress?.invoke(movieCount, seriesCount)
                    }
                }
            }.onFailure { Log.w(TAG, "Stalker movie sync failed for source ${source.id}", it) }
        }
        if (sawMovies) linkMoviesToCanonical(source.id)
    }

    /**
     * The URL to actually feed the player for a [SourceVariant] — the VOD analogue of
     * [resolvePlaybackUrl]. A Stalker movie/episode's [SourceVariant.cmd] is short-lived, same
     * reasoning as a Stalker channel, so it's resolved now, at play time, not when the variant
     * list was built. Xtream/M3U variants carry no `cmd` and just return their already-playable
     * [SourceVariant.streamUrl] unchanged. An episode's cmd carries an extra `|<episodeNumber>`
     * suffix (see [StalkerApi.seriesEpisodes]) that a movie's never does — base64 never contains
     * `|`, so splitting on it can't misfire on a real movie cmd.
     */
    suspend fun resolveVariantPlaybackUrl(variant: SourceVariant): String {
        val cmd = variant.cmd?.takeIf { it.isNotBlank() } ?: return variant.streamUrl
        val source = sourceDao.byId(variant.sourceId) ?: return variant.streamUrl
        val delimiter = cmd.lastIndexOf('|')
        return runCatching {
            if (delimiter > 0) {
                val seasonCmd = cmd.substring(0, delimiter)
                val episodeNum = cmd.substring(delimiter + 1).toIntOrNull()
                stalkerApi.createLink(source, seasonCmd, type = "vod", seriesEpisode = episodeNum)
            } else {
                stalkerApi.createLink(source, cmd, type = "vod")
            }
        }.getOrNull() ?: variant.streamUrl
    }

    /**
     * Stamps quality/language/codec onto freshly-fetched movies/series before they are stored —
     * the VOD analogue of [normalized] for channels. [VodTitleCleaner.parse] does the actual
     * parsing; this is just the per-row `copy()`.
     */
    private fun stampedMovieQuality(movies: List<Movie>): List<Movie> = movies.map { movie ->
        val parsed = VodTitleCleaner.parse(movie.name)
        movie.copy(
            qualityRank = parsed.qualityRank,
            qualityLabel = parsed.qualityLabel,
            language = parsed.language,
            codec = parsed.codec,
        )
    }

    private fun stampedSeriesQuality(series: List<Series>): List<Series> = series.map { s ->
        val parsed = VodTitleCleaner.parse(s.name)
        s.copy(
            qualityRank = parsed.qualityRank,
            qualityLabel = parsed.qualityLabel,
            language = parsed.language,
            codec = parsed.codec,
        )
    }

    private fun stampedEpisodeQuality(episodes: List<Episode>): List<Episode> = episodes.map { ep ->
        val parsed = VodTitleCleaner.parse(ep.title)
        ep.copy(
            qualityRank = parsed.qualityRank,
            qualityLabel = parsed.qualityLabel,
            language = parsed.language,
            codec = parsed.codec,
        )
    }


    private suspend fun syncM3u(source: Source, nowUtcMillis: Long): SyncResult {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent)
            .build()

        val parsed = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SyncResult.Failed("Échec du téléchargement de la liste (HTTP ${response.code}).", null)
            }
            val stream = response.body?.byteStream()
                ?: return SyncResult.Failed("La liste de lecture est vide.", null)
            M3uParser.parse(stream, source.id)
        }

        if (parsed.channels.isEmpty() && parsed.movies.isEmpty() && parsed.series.isEmpty()) {
            return SyncResult.Failed(
                "Aucune entrée lisible dans cette liste. Vérifiez que l’adresse pointe vers un fichier M3U/M3U+.",
                null,
            )
        }

        fun categoriesOf(kind: StreamKind, ids: List<String?>): List<Category> =
            ids.mapNotNull { it }.distinct().sorted().mapIndexed { index, name ->
                Category(
                    id = name,
                    sourceId = source.id,
                    name = name,
                    kind = kind,
                    sortIndex = index,
                )
            }

        // One m3u_plus download can contain all three libraries. Split them here, then feed the
        // same Room/canonical pipeline the native Xtream and Stalker adapters use.
        val liveCategories = categoriesOf(StreamKind.LIVE, parsed.channels.map { it.categoryId })
        val movieCategories = categoriesOf(StreamKind.MOVIE, parsed.movies.map { it.categoryId })
        val seriesCategories = categoriesOf(StreamKind.SERIES, parsed.series.map { it.categoryId })
        val allCategories = buildList {
            addAll(liveCategories)
            if (settings.moviesEnabled.value) addAll(movieCategories)
            if (settings.seriesEnabled.value) addAll(seriesCategories)
        }
        if (allCategories.isNotEmpty()) categoryDao.upsertAll(allCategories)

        val liveCategoryNames = liveCategories.associate { it.id to it.name }
        if (parsed.channels.isNotEmpty()) {
            channelDao.replaceCatalogue(
                source.id,
                normalized(parsed.channels, liveCategoryNames),
                nowUtcMillis,
            )
        }

        val movies = if (settings.moviesEnabled.value) stampedMovieQuality(parsed.movies) else emptyList()
        if (movies.isNotEmpty()) {
            movieDao.upsertAll(movies)
            linkMoviesToCanonical(source.id)
        }

        val series = if (settings.seriesEnabled.value) stampedSeriesQuality(parsed.series) else emptyList()
        val episodes = if (settings.seriesEnabled.value) stampedEpisodeQuality(parsed.episodes) else emptyList()
        if (series.isNotEmpty()) {
            seriesDao.upsertAll(series)
            linkSeriesToCanonical(source.id)
        }
        if (episodes.isNotEmpty()) {
            episodeDao.upsertAll(episodes)
            // The series rows are canonical-linked first. Now every episode can be linked by its
            // exact season/episode slot, same as Xtream/Stalker lazy episode imports.
            episodes.groupBy { it.seriesId }.forEach { (seriesId, _) ->
                val seriesRow = seriesDao.bySourceAndSeriesId(source.id, seriesId)
                val canonicalSeriesId = seriesRow?.canonicalId
                if (canonicalSeriesId != null) {
                    linkEpisodesToCanonical(source.id, seriesId, canonicalSeriesId)
                }
            }
        }

        if (source.epgUrl.isNullOrBlank() && !parsed.declaredEpgUrl.isNullOrBlank()) {
            sourceDao.update(source.copy(epgUrl = parsed.declaredEpgUrl))
        }

        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(
            channelCount = parsed.channels.size,
            movieCount = movies.size,
            seriesCount = series.size,
        )
    }

    /**
     * Stamps every channel with its normalised identity before it is stored.
     *
     * This one pass powers both headline features: [Channel.groupKey] folds quality
     * variants of a channel into a single row and is the join key for EPG matching, and
     * [Channel.displayName] is the cleaned name the UI shows instead of `UK| BBC ONE FHD`.
     */
    private fun normalized(
        channels: List<Channel>,
        categoryNames: Map<String, String>,
    ): List<Channel> {
        val stamped = channels.map { channel ->
            val n = ChannelNameNormalizer.normalize(channel.name)
            var rank = n.qualityRank
            var label = n.qualityLabel
            if (label.isEmpty() || rank == 0) {
                // Some providers put the quality in the CATEGORY, not the channel:
                // 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc' holding identically named
                // channels. Without this, the player's switch is four buttons all
                // reading 'Standard' — grouped correctly, labelled uselessly.
                val categoryName = channel.categoryId?.let { categoryNames[it] }
                if (categoryName != null) {
                    val c = ChannelNameNormalizer.normalize(categoryName)
                    if (label.isEmpty()) label = c.qualityLabel
                    if (rank == 0) rank = c.qualityRank
                }
            }
            channel.copy(
                displayName = n.baseName,
                groupKey = n.groupKey,
                qualityRank = rank,
                qualityLabel = label,
                // Providers ship decorative separator rows ('#### UK GENERAL ####') as
                // channels. They are headings, not channels — hide them on import.
                hidden = channel.hidden || isSeparatorRow(channel.name),
            )
        }

        return stamped
    }

    /** Decorative list headings: starts AND ends with a run of banner characters. */
    private fun isSeparatorRow(rawName: String): Boolean {
        val t = rawName.trim()
        return t.length >= 6 &&
            t.take(3).all { it in SEPARATOR_CHARS } &&
            t.takeLast(3).all { it in SEPARATOR_CHARS }
    }

    /**
     * Re-cleans every stored channel with the current normaliser, no network needed.
     *
     * displayName, groupKey, quality and separator-hiding are computed at import time, so a
     * change to the normaliser (a new superscript char, a new junk pattern) does not reach
     * channels already in the database until the next full catalogue sync — which can be
     * hours away. This runs the same pass over existing rows locally, so a code fix shows up
     * on the next launch instead of the next sync. Bump [NORMALIZER_VERSION] to trigger it.
     */
    suspend fun renormalizeAll(): Int = withContext(Dispatchers.IO) {
        val existing = channelDao.allForMatching()
        if (existing.isEmpty()) return@withContext 0

        val bySource = existing.groupBy { it.sourceId }
        var changed = 0
        for ((_, channels) in bySource) {
            val names = channelCategoryNames(channels)
            val renamed = normalized(channels, names)
            // Only write rows that actually changed, to keep the write small.
            val diff = renamed.filterIndexed { i, c ->
                val old = channels[i]
                c.displayName != old.displayName || c.groupKey != old.groupKey ||
                    c.qualityLabel != old.qualityLabel || c.qualityRank != old.qualityRank ||
                    c.hidden != old.hidden
            }
            if (diff.isNotEmpty()) {
                diff.chunked(500).forEach { channelDao.upsertAll(it) }
                changed += diff.size
            }
        }
        Log.i(TAG, "Re-normalised $changed channels with the current normaliser")
        changed
    }

    /** Best-effort category-id → name map for a set of channels. */
    private suspend fun channelCategoryNames(channels: List<Channel>): Map<String, String> {
        val ids = channels.mapNotNull { it.categoryId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return categoryDao.namesFor(ids).associate { it.id to it.name }
    }

    /**
     * The switchable quality variants of a channel, best first, ONE per distinct quality.
     *
     * A provider often lists the same stream in several categories — PRIME American Crimes
     * shows up twice, both RAW. Those are not "qualities"; offering a switch between two
     * identical (and sometimes one dead) feeds is worse than useless. So variants are
     * de-duplicated by their quality signature: only genuinely different qualities survive,
     * and a channel that is really single-quality gets no switch at all.
     */
    suspend fun variants(channel: Channel): List<Channel> {
        if (channel.groupKey.isEmpty()) return listOf(channel)
        val all = channelDao.variantsInGroup(channel.groupKey)
        return distinctByQuality(all).ifEmpty { listOf(channel) }
    }

    /**
     * The record menu's "record from which provider" options: one channel per source that carries
     * this logical channel, each that source's best-quality copy, the current source first. With two
     * providers this lets a recording run on one account while the user keeps watching on the other —
     * the only real way around a provider's single-connection limit (which even TiviMate can't dodge).
     */
    suspend fun recordSourceOptions(channel: Channel): List<Channel> = withContext(Dispatchers.IO) {
        if (channel.groupKey.isEmpty()) return@withContext listOf(channel)
        channelDao.variantsInGroup(channel.groupKey)
            .groupBy { it.sourceId }
            .map { (_, chans) -> chans.maxByOrNull { it.qualityRank } ?: chans.first() }
            .sortedByDescending { it.sourceId == channel.sourceId }
    }

    /**
     * Switches an Xtream source's live-stream container (HLS ↔ MPEG-TS) and rewrites its live
     * channels' playback URLs in place, so the change takes effect immediately without a re-sync.
     *
     * Xtream only: an M3U source's channel URLs come straight from its playlist and must not be
     * rebuilt from credentials. The `channels` table is live-only (movies and series have their own
     * tables), so every row here is a live channel whose URL derives from [XtreamApi.liveStreamUrl].
     */
    suspend fun setLiveFormat(sourceId: Long, format: LiveStreamFormat) = withContext(Dispatchers.IO) {
        val source = sourceDao.byId(sourceId) ?: return@withContext
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return@withContext
        val updated = source.copy(liveFormat = format)
        sourceDao.update(updated)
        // Rebuild each live channel's URL for the new container.
        channelDao.forSource(sourceId).forEach { channel ->
            val newUrl = api.liveStreamUrl(updated, channel.streamId)
            if (newUrl != channel.streamUrl) channelDao.updateStreamUrl(channel.id, newUrl)
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        channelDao.deleteForSource(sourceId)
        categoryDao.deleteForSource(sourceId)
        movieDao.deleteForSource(sourceId)
        seriesDao.deleteForSource(sourceId)
        episodeDao.deleteForSource(sourceId)
        sourceDao.delete(sourceId)
    }

    companion object {
        private const val TAG = "CatalogRepository"
        /** Coalesce tiny Stalker pages so Room invalidates VOD screens tens, not thousands, of times. */
        private const val STALKER_DB_BATCH_SIZE = 500
        private const val CANONICAL_LINK_BATCH_SIZE = 250
        val SEPARATOR_CHARS = setOf('#', '*', '=', '~', '-', '_', '•', '█', '▓', '|')

        /**
         * Bump this whenever the normaliser changes in a way that should re-process
         * already-imported channels. The app compares it against a stored value on launch
         * and runs [renormalizeAll] once when it moves.
         */
        const val NORMALIZER_VERSION = 2
    }
}
