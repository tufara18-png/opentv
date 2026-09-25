/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

import android.util.Log
import app.tufaratv.data.db.ChannelDao
import app.tufaratv.data.db.CategoryDao
import app.tufaratv.data.db.EpgChannelAliasDao
import app.tufaratv.data.db.EpgFeedDao
import app.tufaratv.data.db.ProgrammeDao
import app.tufaratv.data.db.SourceDao
import app.tufaratv.data.model.EpgChannelAlias
import app.tufaratv.data.model.EpgFeed
import app.tufaratv.data.model.Programme
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.SourceKind
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.parser.ChannelNameNormalizer
import app.tufaratv.data.parser.CountryResolver
import app.tufaratv.data.parser.XmltvParser
import app.tufaratv.data.remote.XtreamApi
import app.tufaratv.core.AppSettings
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Programmes are **upserted in batches** as they stream out of the parser. The unique index
 *   on `(feedId, epgChannelId, startUtcMillis)` makes a re-run idempotent, so a sync that
 *   dies at 60% leaves 60% of a fresher guide behind — strictly better than before.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed leaves that feed's previous data intact and records the reason on the
 *   feed row, where the settings screen shows it — no silent failure.
 */
class EpgRepository(
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeWindowForChannels(
        epgChannelIds: List<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Flow<List<Programme>> = programmeDao.observeWindowForChannels(epgChannelIds, fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = feedDao.setEnabled(id, enabled)

    suspend fun removeFeed(feed: EpgFeed) {
        // A removed feed takes its guide data with it — orphaned programmes would otherwise
        // keep matching channels forever with content that never refreshes.
        programmeDao.deleteForFeed(feed.id)
        aliasDao.deleteForFeed(feed.id)
        feedDao.delete(feed.id)
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        for ((name, url) in BUILT_IN_FEEDS) {
            if (feedDao.byUrl(url) == null) {
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(
        nowUtcMillis: Long,
        force: Boolean = false,
        includeStalker: Boolean = true,
    ): SyncSummary =
        withContext(Dispatchers.IO) {
            ensureFeeds()
            maybeAutoEnableRegionalFeed()

            var succeeded = 0
            var failed = 0
            var written = 0
            var refreshed = 0
            var successfulRefreshes = 0

            for (feed in feedDao.enabled()) {
                val provider = feed.providerSourceId?.let { sourceDao.byId(it) }
                if (!includeStalker && provider?.kind == SourceKind.STALKER) continue
                if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) {
                    succeeded++
                    continue
                }
                refreshed++
                when (val result = syncFeed(feed, nowUtcMillis)) {
                    is FeedResult.Success -> {
                        succeeded++
                        successfulRefreshes++
                        written += result.programmes
                        feedDao.markSynced(
                            feed.id,
                            nowUtcMillis,
                            "${result.programmes} programmes, ${result.channels} channels",
                        )
                    }
                    is FeedResult.Failed -> {
                        failed++
                        // The stamp is NOT advanced on failure, so the next sync retries
                        // rather than waiting out the interval on a feed that never landed.
                        feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                        Log.w(TAG, "Feed '${feed.name}' failed: ${result.reason}")
                    }
                }
            }

            // A warm start with fresh feeds is a pure cache hit. Do no deletes and, crucially, do
            // not rebuild the fuzzy matcher across tens of thousands of channels. This was one of
            // the largest cold-start CPU/Room spikes on low-end TV hardware.
            if (refreshed == 0 || successfulRefreshes == 0) {
                // Failed stale feeds used to fall through here whenever another feed was merely
                // fresh. That rebuilt the matcher for 12k channels after a DNS failure even though
                // not one guide row had changed — a large, pointless allocation spike at launch.
                return@withContext SyncSummary(succeeded, failed, written, 0, 0)
            }

            if (succeeded > 0) {
                programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
            }

            val (matched, total) = runMatcher()
            SyncSummary(succeeded, failed, written, matched, total)
        }

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: String) : FeedResult
    }

    private suspend fun syncFeed(feed: EpgFeed, nowUtcMillis: Long): FeedResult {
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = ArrayList<EpgChannelAlias>(256)
        var written = 0

        try {
            openFeedStream(feed).use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = feed.id,
                    onChannelAlias = { id, displayName ->
                        aliases += EpgChannelAlias(
                            feedId = feed.id,
                            epgId = id,
                            displayName = displayName ?: id,
                            normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                        )
                    },
                    onProgramme = { programme ->
                        // Skip anything that finished before the retention cut-off; no point
                        // writing rows we are about to prune.
                        if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS) {
                            batch += programme
                            if (batch.size >= BATCH_SIZE) {
                                programmeDao.upsertAll(batch)
                                written += batch.size
                                batch.clear()
                            }
                        }
                    },
                )
                if (batch.isNotEmpty()) {
                    programmeDao.upsertAll(batch)
                    written += batch.size
                }
                if (aliases.isNotEmpty()) {
                    aliases.chunked(BATCH_SIZE).forEach { aliasDao.upsertAll(it) }
                }

                if (written == 0 && stats.programmeCount == 0) {
                    return FeedResult.Failed("Downloaded, but contained no programmes.")
                }
                return FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately no cleanup. Whatever was written is newer than what was there,
            // and what was there is still there.
            return FeedResult.Failed(e.message ?: "Download failed.")
        }
    }

    /**
     * Opens a feed as a decompressed XML stream.
     *
     * Several of the free guide sources publish `.gz` files (a national guide compresses
     * roughly 10:1), and providers occasionally gzip xmltv.php without saying so. Sniffing
     * the first two bytes for the gzip magic number handles both, regardless of what the
     * URL or the headers claim.
     */
    private suspend fun openFeedStream(feed: EpgFeed): InputStream {
        val raw: InputStream = when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                api.openEpgStream(source)
            }
            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                response.body?.byteStream()
                    ?: throw IllegalStateException("The server returned an empty guide.")
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val buffered = BufferedInputStream(raw, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(): Pair<Int, Int> {
        val aliases = aliasDao.all()
        // Only ids that actually have programmes count as "working" — a match against a
        // channel the guide lists but never fills is a guide that looks broken.
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()
        val index = EpgMatcher.buildIndex(
            aliases.asSequence()
                .filter { it.epgId in populated }
                .map { it.epgId to it.displayName }
                .asIterable(),
        )

        val channels = channelDao.allForMatching()
        var matched = 0

        for (channel in channels) {
            // A provider-supplied XMLTV id is stronger than a fuzzy channel-name match. Public
            // feeds frequently decorate that same id, so try the compact id bridge first.
            val newMatch = index.matchProviderId(channel.epgChannelId)
                ?: index.match(channel.groupKey)
            if (newMatch != channel.matchedEpgId) {
                channelDao.setMatchedEpgId(channel.id, newMatch)
            }
            val works = channel.epgCandidates.any { it in populated } ||
                (newMatch != null && newMatch in populated)
            if (works) matched++
        }

        Log.i(
            TAG,
            "Matcher: $matched of ${channels.size} channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels)",
        )
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return

        // Existing installs that already synced a guide have necessarily completed region setup.
        // Mark the migration locally and skip the all-channel scan immediately.
        if (!settings.epgAutoRegionInitialized && all.any { it.lastSyncMillis > 0L }) {
            settings.epgAutoRegionInitialized = true
        }
        if (settings.epgAutoRegionInitialized) return

        val regionCounts = HashMap<String, Int>()
        for (channel in channelDao.allForMatching()) {
            val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
            regionCounts.merge(region, 1, Int::plus)
        }
        val locale = Locale.getDefault()
        val regions = regionCounts.filterValues { it >= MIN_CHANNELS_FOR_AUTO_REGION }.keys.toMutableSet()

        // Normalisation deliberately removes country prefixes before channels are stored, so a
        // later guide refresh cannot reliably recover every country from channel.name. Live
        // category names retain that information ("FRENCH", "UNITED KINGDOM", "BELGIUM"...).
        // Scan this small cached table once; never inspect or query channels during playback.
        for (category in categoryDao.allByKind(StreamKind.LIVE)) {
            val normalized = ChannelNameNormalizer.normalize(category.name)
            val country = normalized.region?.let(CountryResolver::resolve)
                ?: CountryResolver.resolve(normalized.baseName)
                ?: CountryResolver.resolveAnyToken(normalized.baseName)?.first
            country?.code?.let { regions += it }
        }
        locale.country.takeIf { it.isNotBlank() }?.let { regions += it }
        if (locale.language == "fr") regions += "CA"

        for (region in regions) {
            val feed = regionalFeed(region) ?: continue
            val existing = all.firstOrNull { it.url == feed.second }
            if (existing != null) {
                if (!existing.enabled) feedDao.setEnabled(existing.id, true)
                continue
            }
            val feedId = feedDao.insert(
                EpgFeed(
                    name = feed.first,
                    url = feed.second,
                    builtIn = true,
                    enabled = true,
                ),
            )
            Log.i(TAG, "Auto-enabled regional EPG '$region' (feed $feedId)")
        }
        settings.epgAutoRegionInitialized = true
    }

    private fun regionalFeed(region: String): Pair<String, String>? = when (region.uppercase()) {
        "UK", "GB" -> "UK — epgshare01" to epgShareUrl("UK1")
        "US", "USA" -> "USA — epgshare01" to epgShareUrl("US2")
        "CA" -> "Canada — epgshare01" to epgShareUrl("CA2")
        "AU", "AUS" -> "Australia — epgshare01" to epgShareUrl("AU1")
        "FR" -> "France — epgshare01" to epgShareUrl("FR1")
        "DE" -> "Germany — epgshare01" to epgShareUrl("DE1")
        "ES" -> "Spain — epgshare01" to epgShareUrl("ES1")
        "IT" -> "Italy — epgshare01" to epgShareUrl("IT1")
        "BE" -> "Belgium — epgshare01" to epgShareUrl("BE2")
        "CH" -> "Switzerland — epgshare01" to epgShareUrl("CH1")
        else -> null
    }

    private fun epgShareUrl(fileCode: String): String =
        "https://epgshare01.online/epgshare01/epg_ripper_${fileCode}.xml.gz"

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        // Room wraps each list insert in one transaction. Larger batches drastically reduce
        // invalidation notifications (and therefore live/dashboard EPG re-queries) during an
        // explicit refresh, without retaining the complete XMLTV in memory.
        const val BATCH_SIZE = 5_000

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            "USA — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            "Canada — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
            "Australia — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
            "France — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
            "Germany — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
            "Spain — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz",
            "Italy — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
            "Belgium — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_BE2.xml.gz",
            "Switzerland — epgshare01" to "https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz",
        )
    }
}
