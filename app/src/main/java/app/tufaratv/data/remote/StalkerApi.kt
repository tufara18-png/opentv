/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.remote

import android.util.Log
import app.tufaratv.data.model.Category
import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A client for the Stalker / Ministra portal protocol (the "MAG box" middleware many panels speak).
 *
 * ## How it differs from Xtream
 * There is no username/password. A box identifies itself by a **MAC address** and does a **handshake**
 * that returns a short-lived **token**; every later call carries that token as a bearer. A channel's
 * real stream URL is not stable — the catalogue gives a `cmd` string, and the playable URL is minted
 * on demand by [createLink] at tune time. So [liveChannels] stores each channel's `cmd`; the playback
 * path resolves it just-in-time (see [app.tufaratv.data.repo.CatalogRepository.resolvePlaybackUrl]).
 *
 * ## Portal-path and MAC quirks
 * Portals expose the API under different paths (`/portal.php`, `/server/load.php`, `/stalker_portal/...`);
 * [endpoints] tries the common ones and the first that hands back a token wins, cached per source. The
 * MAC goes in a Cookie, URL-encoded, alongside a MAG-style STB User-Agent — the shape real boxes send.
 *
 * Blocking OkHttp calls wrapped in `withContext(Dispatchers.IO)`, matching [XtreamApi].
 */
class StalkerApi(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    class StalkerException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Session(val token: String, val endpoint: HttpUrl, val expiresAt: Long)

    /** One live token per source, so zapping doesn't re-handshake on every channel. */
    private val sessions = ConcurrentHashMap<Long, Session>()


    /**
     * Stalker/Ministra is a protocol family, not a stable JSON schema. Depending on middleware
     * version/proxy, payloads arrive as:
     *   {"js": ...}, {"data": ...}, {"result": ...}, {"response": ...}, or already-unwrapped.
     * Normalize those transport envelopes before any feature-specific parser sees them.
     */
    private fun unwrapPayload(element: JsonElement?): JsonElement? {
        var current = element ?: return null
        repeat(4) {
            val obj = current as? JsonObject ?: return current
            val next = listOf("js", "result", "response")
                .firstNotNullOfOrNull { key -> obj[key]?.takeUnless { it is JsonPrimitive && it.contentOrNull == "null" } }
                ?: return current
            current = next
        }
        return current
    }

    /** Accept the common list shapes without baking one portal's wrapper into the mapper. */
    private fun listPayload(element: JsonElement?): JsonArray {
        val unwrapped = unwrapPayload(element)
        return when (unwrapped) {
            is JsonArray -> unwrapped
            is JsonObject -> {
                listOf("data", "items", "channels", "genres", "categories", "results")
                    .firstNotNullOfOrNull { key -> unwrapPayload(unwrapped[key]) as? JsonArray }
                    ?: JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }
    }

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> str(key) }

    /** Add-source test: proves the portal + MAC produce a token. Throws [StalkerException] if not. */
    suspend fun handshakeTest(source: Source) = withContext(Dispatchers.IO) {
        session(source, force = true)
        Unit
    }

    /** Live categories ("genres") for the guide's rail. */
    suspend fun liveCategories(source: Source): List<Category> = withContext(Dispatchers.IO) {
        val arr = listPayload(callRetrying(source, type = "itv", action = "get_genres"))
        arr.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.firstString("id", "genre_id", "category_id") ?: return@mapIndexedNotNull null
            Category(
                id = id,
                sourceId = source.id,
                name = o.firstString("title", "name", "genre_name", "category_name") ?: id,
                kind = StreamKind.LIVE,
                sortIndex = index,
            )
        }
    }

    /** Every live channel. Each carries its `cmd`; the real URL is minted by [createLink] at play time. */
    suspend fun liveChannels(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        val s = session(source)
        val data = listPayload(callRetrying(source, type = "itv", action = "get_all_channels"))
        data.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.firstString("id", "channel_id", "stream_id") ?: return@mapIndexedNotNull null
            val name = o.firstString("name", "title", "channel_name") ?: return@mapIndexedNotNull null
            val number = o.firstString("number", "num", "channel_number")?.toIntOrNull()
            Channel(
                sourceId = source.id,
                streamId = id,
                name = name,
                categoryId = o.firstString("tv_genre_id", "genre_id", "category_id"),
                logoUrl = o.firstString("logo", "logo_uri", "screenshot_uri")?.takeIf { it.isNotBlank() }?.let { absoluteLogo(s.endpoint, it) },
                epgChannelId = o.firstString("xmltv_id", "epg_id", "tvg_id")?.takeIf { it.isNotBlank() },
                number = number,
                // Never played directly — a marker so nothing mistakes it for a real URL; [cmd] is
                // what gets resolved. Distinct per channel so de-dup/quality-grouping still works.
                streamUrl = "stalker://${source.id}/$id",
                cmd = o.firstString("cmd", "command", "stream_cmd"),
                sortIndex = number ?: index,
            )
        }
    }

    /** Mint the real, short-lived stream URL for a channel's [cmd]. Null if the portal declines.
     *  [type] is `"itv"` for a live channel, `"vod"` for a movie (and, confirmed against a real
     *  portal, for a series episode too — see [seriesEpisodes]) — a VOD source resolved as itv
     *  (or vice versa) is a common reason a portal silently declines. [seriesEpisode], when set,
     *  is the extra `series=<N>` parameter that picks one specific episode out of a season-level
     *  `cmd` template — a season's `cmd` alone resolves nothing playable on its own. */
    suspend fun createLink(source: Source, cmd: String, type: String = "itv", seriesEpisode: Int? = null): String? =
        withContext(Dispatchers.IO) {
            val js = callRetrying(source, type = type, action = "create_link") { b ->
                b.addQueryParameter("cmd", cmd)
                b.addQueryParameter("forced_storage", "0")
                b.addQueryParameter("disable_ad", "0")
                if (seriesEpisode != null) b.addQueryParameter("series", seriesEpisode.toString())
            }
            val unwrapped = unwrapPayload(js)
            val linkCmd = when (unwrapped) {
                is JsonObject -> unwrapped.firstString("cmd", "url", "link", "stream_url")
                is JsonPrimitive -> unwrapped.contentOrNull
                else -> null
            } ?: return@withContext null
            stripCmdPrefix(linkCmd)
        }

    /** VOD (movie) categories — same shape as [liveCategories], under the `vod` type. */
    suspend fun vodCategories(source: Source): List<Category> = withContext(Dispatchers.IO) {
        val arr = listPayload(callRetrying(source, type = "vod", action = "get_categories"))
        arr.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.firstString("id", "category_id", "genre_id") ?: return@mapIndexedNotNull null
            Category(id = id, sourceId = source.id, name = o.firstString("title", "name", "category_name") ?: id, kind = StreamKind.MOVIE, sortIndex = index)
        }
    }

    /**
     * Every VOD movie across every category, walking `get_ordered_list`'s pagination
     * (`category=*` is the Ministra convention for "every category in one paginated sweep",
     * avoiding one round trip per category). Each item's `cmd` is stored, not resolved here —
     * [createLink] mints the real URL at play time, same reasoning as [liveChannels].
     *
     * Streamed via [onBatch] (called once per fetched page, ~14 movies at a time) rather than
     * building one `List<Movie>` for the whole catalogue — confirmed necessary against a real
     * 33,802-movie catalogue, where holding every page's parsed JSON and every mapped `Movie` in
     * memory at once ran the app out of heap and crashed it mid-sync (visible in logcat as
     * repeated blocking GCs then a plain process death, not any kind of matching bug — series,
     * synced right after movies in [app.tufaratv.data.repo.CatalogRepository.syncStalkerVod],
     * simply never got a chance to run). A batch is garbage-collectable the moment its caller is
     * done with it.
     */
    suspend fun vodMovies(source: Source, onBatch: suspend (List<Movie>) -> Unit) = withContext(Dispatchers.IO) {
        val s = session(source)
        fetchAllPagesStreaming(source, type = "vod") { batch ->
            val movies = batch.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.firstString("id", "movie_id", "series_id", "stream_id") ?: return@mapNotNull null
                val name = o.firstString("name", "title") ?: return@mapNotNull null
                val cmd = o.firstString("cmd", "command", "stream_cmd") ?: return@mapNotNull null
                Movie(
                    sourceId = source.id,
                    streamId = id,
                    name = name,
                    categoryId = o.str("category_id"),
                    posterUrl = o.str("screenshot_uri")?.takeIf { it.isNotBlank() }
                        ?.let { absoluteLogo(s.endpoint, it) },
                    rating = o.str("rating_imdb")?.toDoubleOrNull() ?: o.str("rating")?.toDoubleOrNull(),
                    year = leadingYear(o.str("year")),
                    plot = o.str("description"),
                    durationSeconds = o.str("time")?.toIntOrNull(),
                    containerExtension = null,
                    // Never played directly — see [liveChannels]'s identical marker for channels.
                    streamUrl = "stalker://${source.id}/vod/$id",
                    cast = o.str("actors"),
                    genre = o.str("genres_str"),
                    director = o.str("director"),
                    // The portal supplies its own TMDB id directly — confirmed in real data
                    // ("tmdb_id":"1245347") — an exact match CanonicalMatcher always prefers over
                    // fuzzy title+year, and without it a provider's own decorated title/off year is
                    // exactly why so many real matches were silently missed.
                    tmdbId = o.str("tmdb_id") ?: o.str("tmdb"),
                    cmd = cmd,
                )
            }
            if (movies.isNotEmpty()) onBatch(movies)
        }
    }

    /** Series categories — same shape as [vodCategories], under the `series` type. */
    suspend fun seriesCategories(source: Source): List<Category> = withContext(Dispatchers.IO) {
        val arr = listPayload(callRetrying(source, type = "series", action = "get_categories"))
        arr.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.firstString("id", "category_id", "genre_id") ?: return@mapIndexedNotNull null
            Category(id = id, sourceId = source.id, name = o.firstString("title", "name", "category_name") ?: id, kind = StreamKind.SERIES, sortIndex = index)
        }
    }

    /**
     * Every series across every category — top-level catalogue entries only, same reasoning and
     * shape as [vodMovies], streamed via [onBatch] for the same real, confirmed OOM-crash reason.
     * Episodes are fetched separately and lazily by [seriesEpisodes], same as Xtream's
     * [app.tufaratv.data.repo.CatalogRepository.ensureEpisodes] pattern.
     */
    suspend fun seriesList(source: Source, onBatch: suspend (List<Series>) -> Unit) = withContext(Dispatchers.IO) {
        val s = session(source)
        fetchAllPagesStreaming(source, type = "series") { batch ->
            val series = batch.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.firstString("id", "movie_id", "series_id", "stream_id") ?: return@mapNotNull null
                val name = o.firstString("name", "title") ?: return@mapNotNull null
                Series(
                    sourceId = source.id,
                    seriesId = id,
                    name = name,
                    categoryId = o.str("category_id"),
                    posterUrl = o.str("screenshot_uri")?.takeIf { it.isNotBlank() }
                        ?.let { absoluteLogo(s.endpoint, it) },
                    rating = o.str("rating_imdb")?.toDoubleOrNull() ?: o.str("rating")?.toDoubleOrNull(),
                    year = leadingYear(o.str("year")),
                    plot = o.str("description"),
                    cast = o.str("actors"),
                    genre = o.str("genres_str"),
                    // See vodMovies' identical field — the portal supplies its own TMDB id directly.
                    tmdbId = o.str("tmdb_id") ?: o.str("tmdb"),
                )
            }
            if (series.isNotEmpty()) onBatch(series)
        }
    }

    /**
     * Every episode across every season for [seriesId] (the compound `"showId:showId"` id
     * [seriesList] stores as [Series.seriesId]). Confirmed real Ministra shape by fetching a live
     * portal directly: `get_ordered_list&movie_id=<showId>` returns one entry per SEASON, each
     * carrying that season's available episode numbers (its `series` array) and a season-level
     * `cmd` template — there is no per-episode `cmd`; [createLink]'s `series=<N>` parameter is
     * what actually picks one episode out of it, confirmed against the same live portal (`type=
     * vod`, not `type=series`, is what resolves it). [Episode.cmd] stores `"<season
     * cmd>|<episodeNumber>"` (base64 never contains `|`, so this can't collide with a real cmd);
     * [app.tufaratv.data.repo.CatalogRepository.resolveVariantPlaybackUrl] splits it back apart
     * at play time, same "resolve now, not when the list was built" reasoning as everywhere else
     * a Stalker cmd shows up. Title/plot are left as a numbered placeholder — TMDB backfill
     * ([app.tufaratv.data.repo.CatalogRepository.backfillEpisodeTmdbMeta], already generic) is
     * what supplies the real name/synopsis/still once the series' tmdbId is known, exactly the
     * path an Xtream episode already goes through.
     */
    suspend fun seriesEpisodes(source: Source, seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        val showId = seriesId.substringBefore(':')
        val raw = callRetrying(source, type = "series", action = "get_ordered_list") { b ->
            b.addQueryParameter("movie_id", showId)
            b.addQueryParameter("category", "*")
        }
        val seasons = listPayload(raw)

        seasons.flatMapIndexed seasonLoop@{ index, element ->
            val o = element as? JsonObject ?: return@seasonLoop emptyList()
            val seasonId = o.firstString("id", "season_id", "season")
            val seasonNum = o.firstString("season", "season_number", "number")?.toIntOrNull()
                ?: seasonId?.substringAfterLast(':')?.toIntOrNull()
                ?: (index + 1)

            val cmd = o.firstString("cmd", "command", "stream_cmd")
                ?.takeIf { it.isNotBlank() } ?: return@seasonLoop emptyList()

            val episodeNode = o["series"] ?: o["episodes"] ?: o["episode_numbers"]
            val episodeNumbers = when (episodeNode) {
                is JsonArray -> episodeNode.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull?.toIntOrNull()
                        is JsonObject -> item.firstString("episode", "episode_num", "number", "id")?.toIntOrNull()
                        else -> null
                    }
                }
                is JsonPrimitive -> episodeNode.contentOrNull
                    ?.split(',', ';', ' ')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    .orEmpty()
                else -> emptyList()
            }

            episodeNumbers.distinct().map { epNum ->
                Episode(
                    sourceId = source.id,
                    seriesId = seriesId,
                    episodeId = "$showId:$seasonNum:$epNum",
                    season = seasonNum,
                    episodeNumber = epNum,
                    title = "Episode $epNum",
                    plot = null,
                    durationSeconds = null,
                    stillUrl = null,
                    streamUrl = "stalker://${source.id}/series/$showId/$seasonNum/$epNum",
                    cmd = "$cmd|$epNum",
                )
            }
        }
    }

    /** A `year` field the portal often ships as a full date (`"2025-09-04"`) rather than a bare
     *  year — `toIntOrNull()` alone silently fails on that and CanonicalMatcher loses its year
     *  disambiguation entirely. Takes the leading 4 digits, whichever form the portal sent. */
    private fun leadingYear(raw: String?): Int? =
        raw?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    /**
     * Walks every page of a `get_ordered_list` call (`category=*`, all categories), handing each
     * page's raw items to [onBatch] as soon as they arrive instead of accumulating the whole
     * catalogue into one list first. A real catalogue can report `total_items` in the tens of
     * thousands at 14 items/page — over two thousand pages — and holding every page's parsed JSON
     * (and, upstream, every mapped `Movie`/`Series`) in memory at once is exactly what crashed the
     * app with an out-of-memory error partway through a real 33,802-item sync (confirmed in
     * logcat: repeated blocking GCs, then a plain process death — not a matching bug, the sync
     * just never finished). [onBatch] is called concurrently, [PAGE_FETCH_CONCURRENCY] pages in
     * flight at a time — enough to cut the sequential wall-clock time by roughly the same factor
     * without hammering the portal hard enough to risk it throttling or dropping the connection.
     * Capped at [MAX_PAGES] as a last-resort safety net against a portal that never reports an
     * accurate total — better an incomplete catalogue than a runaway fetch.
     */
    private suspend fun fetchAllPagesStreaming(
        source: Source,
        type: String,
        onBatch: suspend (JsonArray) -> Unit,
    ): Unit = coroutineScope {
        fun page(p: Int): JsonElement? =
            callRetrying(source, type = type, action = "get_ordered_list") { b ->
                b.addQueryParameter("category", "*")
                b.addQueryParameter("p", p.toString())
            }

        val firstRaw = page(1) ?: return@coroutineScope
        val firstData = listPayload(firstRaw)
        if (firstData.isEmpty()) return@coroutineScope
        onBatch(firstData)

        val firstMeta = when (val u = unwrapPayload(firstRaw)) {
            is JsonObject -> u
            else -> firstRaw as? JsonObject
        }
        val pageSize = firstMeta?.firstString("max_page_items", "items_per_page", "per_page", "page_size")
            ?.toIntOrNull()?.takeIf { it > 0 } ?: firstData.size
        val totalItems = firstMeta?.firstString("total_items", "total", "count", "records")
            ?.toIntOrNull() ?: firstData.size
        val totalPages = ((totalItems + pageSize - 1) / pageSize).coerceIn(1, MAX_PAGES)
        if (totalPages <= 1) return@coroutineScope

        val semaphore = Semaphore(PAGE_FETCH_CONCURRENCY)
        (2..totalPages).map { p ->
            async {
                semaphore.withPermit {
                    val data = listPayload(page(p))
                    if (data.isNotEmpty()) onBatch(data)
                }
            }
        }.awaitAll()
        Unit
    }

    // ---- Session / handshake -----------------------------------------------------------------

    private fun session(source: Source, force: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!force) sessions[source.id]?.let { if (it.expiresAt > now) return it }
        val mac = source.macAddress?.trim().orEmpty()
        if (mac.isEmpty()) throw StalkerException("Ce portail exige une adresse MAC (ex. 00:1A:79:xx:xx:xx).")
        var lastError: Throwable? = null
        for (endpoint in endpoints(source)) {
            val hs = runCatching { handshake(source, endpoint) }
                .onFailure { lastError = it }
                .getOrNull()
            if (hs != null) {
                // Many portals won't serve itv data until get_profile is called with a full MAG
                // identity (device_id/signature) — that's what unlocks the ones that authenticate the
                // box rather than just accepting any MAC.
                runCatching { getProfile(source, endpoint, hs.token, hs.random) }
                return Session(hs.token, endpoint, now + TOKEN_TTL_MILLIS).also { sessions[source.id] = it }
            }
        }
        throw StalkerException(
            "Le portail a refusé cette adresse MAC ou l’adresse du portail est incorrecte.",
            lastError,
        )
    }

    private data class Handshake(val token: String, val random: String)

    private fun handshake(source: Source, endpoint: HttpUrl): Handshake? {
        val url = endpoint.newBuilder()
            .addQueryParameter("type", "stb")
            .addQueryParameter("action", "handshake")
            .addQueryParameter("token", "")
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        val payload = unwrapPayload(execute(source, url, token = null)) as? JsonObject ?: return null
        val token = payload.firstString("token", "access_token", "auth_token")
            ?.takeIf { it.isNotBlank() } ?: return null
        return Handshake(token, payload.firstString("random", "challenge", "nonce").orEmpty())
    }

    /**
     * MAG-box emulation. A lot of real Stalker/Ministra portals won't hand over channels to a client
     * that just presents a MAC — they expect the box's full identity: a serial, a `device_id` and a
     * `signature`, all derived from the MAC, plus a `metrics` blob. The apps that "just work" against
     * those portals send exactly this; sending only the MAC is the usual reason a portal that plays
     * elsewhere returns nothing here. Everything is derived deterministically from the MAC so it's
     * stable across sessions, and portals that don't check any of it simply ignore it.
     */
    private fun getProfile(source: Source, endpoint: HttpUrl, token: String, random: String) {
        val mac = source.macAddress?.trim().orEmpty()
        val id = stbIdentity(mac)
        val metrics = "{\"mac\":\"$mac\",\"sn\":\"${id.sn}\",\"model\":\"MAG250\",\"type\":\"STB\"," +
            "\"uid\":\"${id.deviceId}\",\"random\":\"$random\"}"
        val url = endpoint.newBuilder()
            .addQueryParameter("type", "stb")
            .addQueryParameter("action", "get_profile")
            .addQueryParameter("hd", "1")
            .addQueryParameter("ver", STB_VER)
            .addQueryParameter("num_banks", "2")
            .addQueryParameter("sn", id.sn)
            .addQueryParameter("stb_type", "MAG250")
            .addQueryParameter("client_type", "STB")
            .addQueryParameter("image_version", "218")
            .addQueryParameter("video_out", "hdmi")
            .addQueryParameter("device_id", id.deviceId)
            .addQueryParameter("device_id2", id.deviceId2)
            .addQueryParameter("signature", sha256Upper(id.sn + mac + random))
            .addQueryParameter("auth_second_step", "0")
            .addQueryParameter("hw_version", "1.7-BD-00")
            .addQueryParameter("hw_version_2", sha1Upper(mac))
            .addQueryParameter("not_valid_token", "0")
            .addQueryParameter("api_signature", "262")
            .addQueryParameter("metrics", metrics)
            .addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())
            .addQueryParameter("prehash", "")
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        execute(source, url, token)
    }

    private data class StbIdentity(val sn: String, val deviceId: String, val deviceId2: String)

    /** The MAG identity a portal expects, derived deterministically from the MAC. */
    private fun stbIdentity(mac: String): StbIdentity {
        val deviceId = sha256Upper(mac.uppercase())
        return StbIdentity(sn = md5Upper(mac).take(13), deviceId = deviceId, deviceId2 = deviceId)
    }

    private fun sha256Upper(s: String): String = hashUpper("SHA-256", s)
    private fun sha1Upper(s: String): String = hashUpper("SHA-1", s)
    private fun md5Upper(s: String): String = hashUpper("MD5", s)
    private fun hashUpper(algo: String, s: String): String =
        java.security.MessageDigest.getInstance(algo).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase()

    // ---- HTTP --------------------------------------------------------------------------------

    private fun call(
        session: Session,
        source: Source,
        type: String,
        action: String,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement? {
        val builder = session.endpoint.newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
        extra(builder)
        val body = execute(source, builder.build(), session.token) ?: return null
        return unwrapPayload(body)
    }

    /**
     * An itv call that survives a dropped session. Real portals hand out short-lived tokens and now
     * and then refuse a call mid-session (server load, a token invalidated early); the first attempt
     * then comes back null. Re-handshake once for a fresh token and try again, so an intermittent
     * "sometimes just rejected" becomes a silent retry instead of an empty channel list or a channel
     * that won't tune.
     */
    private fun callRetrying(
        source: Source,
        type: String,
        action: String,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement? {
        call(session(source), source, type, action, extra)?.let { return it }
        return call(session(source, force = true), source, type, action, extra)
    }

    private fun execute(source: Source, url: HttpUrl, token: String?): JsonElement? {
        val mac = source.macAddress?.trim().orEmpty()
        // adid (the lower-case device id) rides in the cookie the way a real box sends it; some
        // portals key off it alongside the MAC.
        val cookie = buildString {
            append("mac=").append(URLEncoder.encode(mac, "UTF-8"))
            append("; stb_lang=en; timezone=Europe/London")
            append("; adid=").append(stbIdentity(mac).deviceId2.lowercase())
            // Some Ministra backends read the token from the cookie, not the Authorization header —
            // send it in both so those portals recognise the session and return the channel list.
            if (token != null) append("; token=").append(token)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", stbUserAgent(source))
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", "${url.scheme}://${url.host}:${url.port}/c/")
            .header("Cookie", cookie)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Stalker $url -> HTTP ${response.code} ${response.message}")
                    return null
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    Log.w(TAG, "Stalker $url -> HTTP ${response.code} but blank body")
                    return null
                }
                json.parseToJsonElement(text)
            }
        }.onFailure { Log.w(TAG, "Stalker request failed: $url", it) }.getOrNull()
    }

    /** Candidate API endpoints for a portal URL, most common first. First that handshakes wins. */
    private fun endpoints(source: Source): List<HttpUrl> {
        val raw = source.url.trim().trimEnd('/')
        // A portal URL is often given as ".../c" (the web-client path); the API lives at the root.
        // Just as often, though, it's given as the literal script URL a provider's panel prints —
        // ".../portal.php" or one of the other candidates below — since that's exactly what many
        // providers hand a subscriber to copy-paste. Strip that too, or every candidate below
        // doubles up into "...portal.php/portal.php" and 404s across the board (confirmed live).
        val root = raw.removeSuffix("/c")
            .removeSuffix("/portal.php")
            .removeSuffix("/c/portal.php")
            .removeSuffix("/server/load.php")
            .removeSuffix("/stalker_portal/server/load.php")
            .removeSuffix("/magLoad.php")
            .trimEnd('/')
        return listOf(
            "$root/portal.php",
            "$root/c/portal.php",
            "$root/server/load.php",
            "$root/stalker_portal/server/load.php",
            "$root/magLoad.php",
        ).mapNotNull { it.toHttpUrlOrNull() }
    }

    /** A MAG-box style STB User-Agent when the source hasn't set a specific one. */
    private fun stbUserAgent(source: Source): String {
        val ua = source.userAgent
        return if (ua.isBlank() || ua == Source.DEFAULT_USER_AGENT) DEFAULT_STB_UA else ua
    }

    /** Provider logos are often relative to the portal host. */
    private fun absoluteLogo(endpoint: HttpUrl, logo: String): String =
        if (logo.startsWith("http")) logo
        else "${endpoint.scheme}://${endpoint.host}:${endpoint.port}/${logo.trimStart('/')}"

    /** create_link returns e.g. "ffmpeg http://…" / "auto http://…" / a bare URL; keep just the URL. */
    private fun stripCmdPrefix(cmd: String): String {
        val trimmed = cmd.trim()
        val httpIdx = trimmed.indexOf("http")
        return if (httpIdx > 0) trimmed.substring(httpIdx).trim() else trimmed
    }

    private companion object {
        const val TAG = "StalkerApi"
        const val TOKEN_TTL_MILLIS = 4 * 60 * 1000L // handshake tokens are short-lived; re-mint often
        const val MAX_PAGES = 4000 // safety cap for fetchAllPages — see its doc comment
        const val PAGE_FETCH_CONCURRENCY = 8 // concurrent page requests — see fetchAllPages
        const val DEFAULT_STB_UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
                "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        /** A realistic MAG firmware version string, sent in get_profile like a real box would. */
        const val STB_VER =
            "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; " +
                "PORTAL version: 5.6.9; API Version: JS API version: 343; STB API version: 146; " +
                "Player Engine version: 0x58c"
    }
}

// ---- Minimal JSON helpers (self-contained; the Xtream accessors are private to that file) --------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
