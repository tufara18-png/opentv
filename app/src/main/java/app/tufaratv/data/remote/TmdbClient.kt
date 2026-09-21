/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.remote

import android.util.Log
import app.tufaratv.core.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * The metadata TMDB can supply to fill gaps a provider left blank. Every field is nullable — TMDB
 * is only ever a *fallback*, so a caller takes what it has and only where its own data is missing.
 */
data class TmdbMeta(
    val tmdbId: String? = null,
    /** TMDB's own title (`title` for a movie, `name` for a show) — the canonical, un-mangled name,
     *  as opposed to whatever a provider decorated its listing with. */
    val title: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
)

/** Per-episode fallback: TMDB's synopsis and still image for one episode of a season. */
data class TmdbEpisodeMeta(val name: String? = null, val overview: String? = null, val stillUrl: String? = null)

/**
 * One row of a TMDB browse list (Trending/Popular/Discover) — just enough to draw a poster card.
 * The full [TmdbMeta] (synopsis, cast, backdrop…) is a separate, later call, made only once the
 * person actually opens this title — see [TmdbClient]'s class doc for why a browse list is never
 * itself the trigger for a details fetch.
 */
data class TmdbListItem(
    val tmdbId: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val isMovie: Boolean,
)

/** A TMDB genre id + its display name, for a Discover-by-genre rail. */
data class TmdbGenre(val id: Int, val name: String)

/**
 * A thin, read-only TMDB v3 client — both the "match Plex" back-fill for a provider's thin
 * metadata (posters, backdrops, synopsis, cast, director, genre) *and*, via [trending]/[popular]/
 * [discoverByGenre]/[search], the source of the browse catalog itself. TMDB is the catalog; a
 * provider's own listing is only ever consulted afterwards, locally, to answer "is this playable
 * and from where" — see [app.tufaratv.data.repo.CatalogRepository.availabilityForTmdb].
 *
 * ## Per-user key, on device only
 * There is no bundled key. Each user pastes their own free TMDB API key in Settings; it lives in
 * [AppSettings] (SharedPreferences) exactly like a provider credential and never leaves the box.
 * With no key set [isConfigured] is false and every lookup short-circuits to null, so the whole
 * feature is simply inert until the user opts in — no traffic, nothing to leak.
 *
 * ## Blocking on purpose
 * The methods block on the calling thread. The one caller ([app.tufaratv.data.repo.CatalogRepository]
 * detail back-fill) is already inside `withContext(Dispatchers.IO)`, matching how the Xtream client
 * is used, so there is no value in another coroutine hop here.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val settings: AppSettings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** True once the user has saved a key. Callers gate on this so nothing runs without one. */
    fun isConfigured(): Boolean = settings.tmdbApiKey.value.isNotBlank()

    /** Metadata for a film: by [tmdbId] when the provider already gave one, else a title(+year) search. */
    fun movieMeta(title: String, year: Int?, tmdbId: String?): TmdbMeta? =
        lookup(isMovie = true, title = title, year = year, tmdbId = tmdbId)

    /** Metadata for a show. Same strategy as [movieMeta]; TMDB has no director for TV, so that stays null. */
    fun seriesMeta(title: String, year: Int?, tmdbId: String?): TmdbMeta? =
        lookup(isMovie = false, title = title, year = year, tmdbId = tmdbId)

    /**
     * The IMDb id (`tt…`) for a title, for handing to Stremio add-ons. Uses the provider's TMDB id
     * when it gave one, otherwise a title(+year) search, then reads `imdb_id` from the movie details
     * (top-level) or the TV `external_ids` endpoint. Null with no key, no match, or no IMDb mapping.
     */
    fun imdbId(title: String, year: Int?, isMovie: Boolean, tmdbId: String?): String? {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return null
        val id = tmdbId?.takeIf { it.isNotBlank() }
            ?: searchId(isMovie, searchTitle(title), year, key)
            ?: return null
        val builder = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
        if (!isMovie) builder.addPathSegment("external_ids")
        builder.addQueryParameter("api_key", key)
        val o = get(builder.build())?.jsonObject ?: return null
        return o["imdb_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
    }

    private fun lookup(isMovie: Boolean, title: String, year: Int?, tmdbId: String?): TmdbMeta? {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return null
        val query = searchTitle(title)
        if (query.isBlank() && tmdbId.isNullOrBlank()) return null
        val id = tmdbId?.takeIf { it.isNotBlank() } ?: searchId(isMovie, query, year, key) ?: return null
        return runCatching { details(isMovie, id, key) }
            .onFailure { Log.w(TAG, "TMDB details failed for $id", it) }
            .getOrNull()
    }

    /**
     * Resolves a title against TMDB without ever trusting "result #1" blindly.
     *
     * IPTV playlists are noisy: language/service prefixes, remakes with the same name, incorrect
     * years and translated titles are routine. Search therefore fetches a candidate set and scores
     * every candidate against both TMDB's localized and original title. A weak/ambiguous result is
     * deliberately left unmatched instead of poisoning the canonical catalogue with confident-looking
     * wrong artwork. A provider-supplied TMDB id still bypasses this path entirely.
     */
    private fun searchId(isMovie: Boolean, query: String, year: Int?, key: String): String? {
        val b = TMDB_BASE.newBuilder()
            .addPathSegment("search")
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", query)
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("language", uiLanguage())

        val results = get(b.build())?.jsonObject?.get("results")?.jsonArray ?: return null
        val wanted = matchKey(query)
        if (wanted.isBlank()) return null

        data class Candidate(val id: String, val score: Double)

        val ranked = results.take(12).mapNotNull { element ->
            val o = element.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localized = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val original = (o["original_title"] ?: o["original_name"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val date = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
            val candidateYear = date?.take(4)?.toIntOrNull()
            val popularity = o["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            val titleSimilarity = maxOf(
                titleSimilarity(wanted, matchKey(localized)),
                titleSimilarity(wanted, matchKey(original)),
            )
            var score = titleSimilarity * 70.0

            if (year != null && candidateYear != null) {
                score += when (kotlin.math.abs(year - candidateYear)) {
                    0 -> 25.0
                    1 -> 10.0
                    else -> -25.0
                }
            } else if (year != null) {
                score -= 4.0
            }

            // Popularity only breaks close ties. It must never overpower identity.
            score += (kotlin.math.ln(popularity + 1.0) / 3.0).coerceAtMost(5.0)
            Candidate(id, score)
        }.sortedByDescending { it.score }

        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        val minimum = if (year != null) 67.0 else 76.0
        val ambiguous = second != null && best.score - second.score < 4.0 && best.score < 92.0
        return best.id.takeIf { best.score >= minimum && !ambiguous }
    }

    private fun matchKey(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    /** Blend token overlap with prefix-aware character similarity; exact matches remain 1.0. */
    private fun titleSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val at = a.split(' ').filter { it.isNotBlank() }.toSet()
        val bt = b.split(' ').filter { it.isNotBlank() }.toSet()
        val union = (at union bt).size.coerceAtLeast(1)
        val jaccard = (at intersect bt).size.toDouble() / union
        val prefix = when {
            a.startsWith(b) || b.startsWith(a) -> 0.88
            else -> 0.0
        }
        return maxOf(jaccard, prefix)
    }

    private fun details(isMovie: Boolean, id: String, key: String): TmdbMeta? {
        val url = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
            .addQueryParameter("api_key", key)
            .addQueryParameter("append_to_response", "credits")
            .addQueryParameter("language", uiLanguage())
            .build()
        val o = get(url)?.jsonObject ?: return null

        val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull
        val backdrop = o["backdrop_path"]?.jsonPrimitive?.contentOrNull
        val overview = o["overview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val genre = o["genres"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }

        val credits = o["credits"]?.jsonObject
        val cast = credits?.get("cast")?.jsonArray
            ?.take(TOP_CAST)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val director = if (!isMovie) null else credits?.get("crew")?.jsonArray
            ?.firstOrNull { it.jsonObject["job"]?.jsonPrimitive?.contentOrNull == "Director" }
            ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
        val dateStr = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
        val year = dateStr?.take(4)?.toIntOrNull()

        return TmdbMeta(
            tmdbId = id,
            title = title,
            posterUrl = poster?.let { IMG_POSTER + it },
            backdropUrl = backdrop?.let { IMG_BACKDROP + it },
            overview = overview,
            cast = cast,
            director = director,
            genre = genre,
            rating = rating,
            year = year,
        )
    }

    /**
     * One TMDB `season` call returns every episode's synopsis and still image at once, so a whole
     * season's worth of per-episode gaps is one request, not one per episode. Keyed by
     * `episode_number` so the caller can match it straight against the provider's own numbering.
     * Empty (not null) on any failure, so a caller can loop over it unconditionally.
     */
    fun seasonEpisodes(seriesTmdbId: String, season: Int): Map<Int, TmdbEpisodeMeta> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return emptyMap()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("tv")
            .addPathSegment(seriesTmdbId)
            .addPathSegment("season")
            .addPathSegment(season.toString())
            .addQueryParameter("api_key", key)
            .addQueryParameter("language", uiLanguage())
            .build()
        val episodes = get(url)?.jsonObject?.get("episodes")?.jsonArray ?: return emptyMap()
        return episodes.mapNotNull { element ->
            val o = element.jsonObject
            val number = o["episode_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val overview = o["overview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val still = o["still_path"]?.jsonPrimitive?.contentOrNull?.let { IMG_STILL + it }
            number to TmdbEpisodeMeta(name = name, overview = overview, stillUrl = still)
        }.toMap()
    }

    /** This week's trending titles — TMDB's own "what's hot right now", not derived from anything
     *  in a provider's catalog. */
    fun trending(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        list(listOf("trending", if (isMovie) "movie" else "tv", "week"), isMovie, page)

    /** TMDB's all-time-popularity ranking — a steadier, less news-driven list than [trending]. */
    fun popular(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        list(listOf(if (isMovie) "movie" else "tv", "popular"), isMovie, page)

    /** Recently released movies / currently-airing shows — TMDB's "new" rail. */
    fun recentlyReleased(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        list(listOf(if (isMovie) "movie" else "tv", if (isMovie) "now_playing" else "on_the_air"), isMovie, page)

    /** Highest-rated titles overall. */
    fun topRated(isMovie: Boolean, page: Int = 1): List<TmdbListItem> =
        list(listOf(if (isMovie) "movie" else "tv", "top_rated"), isMovie, page)

    /** One genre rail — `genreId` from [genres]. */
    fun discoverByGenre(isMovie: Boolean, genreId: Int, page: Int = 1): List<TmdbListItem> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return emptyList()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("discover")
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addQueryParameter("api_key", key)
            .addQueryParameter("with_genres", genreId.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("sort_by", "popularity.desc")
            .addQueryParameter("language", uiLanguage())
            .build()
        return parseListResults(get(url), isMovie)
    }

    /** The genre list a Discover rail picker offers — small, static per media type, safe to cache
     *  by the caller for the session. */
    fun genres(isMovie: Boolean): List<TmdbGenre> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return emptyList()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("genre")
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment("list")
            .addQueryParameter("api_key", key)
            .addQueryParameter("language", uiLanguage())
            .build()
        val results = get(url)?.jsonObject?.get("genres")?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val o = element.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TmdbGenre(id, name)
        }
    }

    /** TMDB's own related-title graph. Local availability is resolved separately by the repository. */
    fun recommendations(tmdbId: String, isMovie: Boolean, page: Int = 1): List<TmdbListItem> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty() || tmdbId.isBlank()) return emptyList()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(tmdbId)
            .addPathSegment("recommendations")
            .addQueryParameter("api_key", key)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("language", uiLanguage())
            .build()
        return parseListResults(get(url), isMovie)
    }

    /** A title search against TMDB directly — the catalog's own search, not a provider's. */
    fun search(query: String, isMovie: Boolean, page: Int = 1): List<TmdbListItem> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty() || query.isBlank()) return emptyList()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("search")
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("language", uiLanguage())
            .build()
        return parseListResults(get(url), isMovie)
    }

    private fun list(pathSegments: List<String>, isMovie: Boolean, page: Int): List<TmdbListItem> {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return emptyList()
        val builder = TMDB_BASE.newBuilder()
        pathSegments.forEach { builder.addPathSegment(it) }
        val url = builder
            .addQueryParameter("api_key", key)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("language", uiLanguage())
            .build()
        return parseListResults(get(url), isMovie)
    }

    private fun parseListResults(root: JsonElement?, isMovie: Boolean): List<TmdbListItem> {
        val results = root?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val o = element.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull
            val rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
            val dateStr = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
            val year = dateStr?.take(4)?.toIntOrNull()
            TmdbListItem(
                tmdbId = id,
                title = title,
                posterUrl = poster?.let { IMG_POSTER + it },
                year = year,
                rating = rating,
                isMovie = isMovie,
            )
        }
    }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "TufaraTV").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        }.onFailure { Log.w(TAG, "TMDB request failed", it) }.getOrNull()
    }

    /** The device/app UI language as a TMDB `language` tag, so titles, overviews and genre names
     *  come back already in the language the person picked — not always English. */
    private fun uiLanguage(): String = Locale.getDefault().toLanguageTag()

    /** Strips a trailing "(2023)"/year and collapses whitespace so the title searches cleanly. */
    private fun searchTitle(raw: String): String =
        raw.replace(Regex("""\(?(19|20)\d{2}\)?\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private companion object {
        const val TAG = "TmdbClient"
        val TMDB_BASE: HttpUrl = "https://api.themoviedb.org/3".toHttpUrl()
        const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val IMG_STILL = "https://image.tmdb.org/t/p/w300"
        const val TOP_CAST = 12
    }
}
