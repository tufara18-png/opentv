/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.tufaratv.data.model.CanonicalContent
import app.tufaratv.data.model.CanonicalEpisode
import app.tufaratv.data.model.CanonicalMatchKind
import app.tufaratv.data.model.Category
import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.EpgChannelAlias
import app.tufaratv.data.model.EpgFeed
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.PlaybackPosition
import app.tufaratv.data.model.PreferredVariant
import app.tufaratv.data.model.Profile
import app.tufaratv.data.model.Programme
import app.tufaratv.data.model.Recording
import app.tufaratv.data.model.RecordingStatus
import app.tufaratv.data.model.Reminder
import app.tufaratv.data.model.Series
import app.tufaratv.data.model.SeriesRule
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.repo.SourceVariant
import kotlinx.coroutines.flow.Flow

/** Rows per upsert statement. Keeps a 40,000-channel playlist from building one huge query. */
private const val UPSERT_CHUNK = 500

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastCatalogSyncMillis = :millis WHERE id = :id")
    suspend fun markCatalogSynced(id: Long, millis: Long)
}

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortIndex, displayName
        """
    )
    fun observe(sourceId: Long?, categoryId: String?): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortIndex, displayName")
    fun observeFavourites(): Flow<List<Channel>>

    /** Every category id that still has at least one visible channel — what the guide's rail
     *  filters its groups against, so a group hidden whole (every channel in it switched off in
     *  the manager) drops off the rail instead of lingering as a shelf that opens onto nothing. */
    @Query("SELECT DISTINCT categoryId FROM channels WHERE hidden = 0")
    fun observeVisibleCategoryIds(): Flow<List<String>>

    /**
     * Channels across a SET of provider categories. Needed because one logical category
     * ("General") is often shipped as several codec-split ones ("UK| GENERAL HD/RAW",
     * "UK| GENERAL hevc") — the UI merges them into a single rail entry. The list is a
     * handful of ids, nowhere near SQLite's bound-variable cap.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategories(categoryIds: List<String>): Flow<List<Channel>>

    /**
     * Channels across a set of categories INCLUDING hidden ones, optionally scoped to one source
     * — the channel manager's browse query.
     *
     * The guide's [observe]/[observeInCategories] filter `hidden = 0`; the manager can't, because
     * its whole job is finding a hidden channel and switching it back on. Still scoped to a
     * category set (never the whole table) so the list stays a size a remote can actually scroll.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>>

    /**
     * One-shot (non-Flow) read of ONE category's channels INCLUDING hidden, optionally scoped to a
     * single source — the web channel manager's per-category fetch. That server handles one HTTP
     * request at a time on a socket thread and wants a plain list, not a subscription, so this is
     * the suspend `List` sibling of [observeInCategoriesIncludingHidden], narrowed to one category.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId = :categoryId
        ORDER BY sortIndex, displayName
        """
    )
    suspend fun channelsInCategoryIncludingHidden(sourceId: Long?, categoryId: String): List<Channel>

    /**
     * Channel count per (source, category), hidden rows included — the web manager's category list
     * shows how many channels each holds. Grouped by (sourceId, categoryId) because a category id
     * is only unique within a source.
     */
    @Query(
        """
        SELECT sourceId, categoryId, COUNT(*) AS count
        FROM channels
        WHERE categoryId IS NOT NULL
        GROUP BY sourceId, categoryId
        """
    )
    suspend fun channelCountsByCategory(): List<CategoryChannelCount>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int = 200): Flow<List<Channel>>

    /** Like [search] but keeps hidden channels in — the channel manager needs them to unhide. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY hidden, favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun searchIncludingHidden(query: String, limit: Int = 200): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): Channel?

    /** Every quality variant of one logical channel, best first. */
    @Query(
        """
        SELECT * FROM channels
        WHERE groupKey = :groupKey AND groupKey != '' AND hidden = 0
        ORDER BY qualityRank DESC, displayName
        """
    )
    suspend fun variantsInGroup(groupKey: String): List<Channel>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    /**
     * Reactive count of channels the guide can actually show (hidden rows excluded). Lets the home
     * screen tell "the guide is still building from channels on disk" apart from "nothing loaded",
     * so a failed or empty sync surfaces an error instead of spinning forever.
     */
    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query("UPDATE channels SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE channels SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String?)

    @Query("UPDATE channels SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    /** Every channel for a source. The channels table is live-only, so these are its live channels. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Channel>

    /** Rewrites one channel's resolved playback URL — used when a source's live stream format changes. */
    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: Long, url: String)

    // --- Sync: identify favourites/hidden by stream URL (stable across devices) ---
    @Query("SELECT streamUrl FROM channels WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    @Query("SELECT streamUrl FROM channels WHERE hidden = 1")
    suspend fun hiddenUrls(): List<String>

    @Query("UPDATE channels SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    @Query("UPDATE channels SET hidden = 1 WHERE streamUrl = :url")
    suspend fun markHiddenByUrl(url: String)

    @Query("UPDATE channels SET matchedEpgId = :epgId WHERE id = :id")
    suspend fun setMatchedEpgId(id: Long, epgId: String?)

    @Query("UPDATE channels SET epgOverrideId = :epgId WHERE id = :id")
    suspend fun setEpgOverride(id: Long, epgId: String?)

    /** The matcher's working set: id, group key, and what is already known. */
    @Query("SELECT * FROM channels")
    suspend fun allForMatching(): List<Channel>

    @Upsert
    suspend fun upsertAll(channels: List<Channel>)

    /**
     * Deletes channels the provider no longer lists.
     *
     * Compares a sync stamp rather than using `streamId NOT IN (:allCurrentIds)`. The latter
     * is the obvious implementation and it breaks on exactly the providers where it matters:
     * SQLite caps bound variables (999 on older Android, 32766 on newer) and a large playlist
     * has 40,000 channels, so the query throws and the catalogue never converges.
     *
     * Note also the deliberate absence of any "delete everything then insert" method. Wiping
     * first means a sync that fails halfway leaves the user with an empty app — which is how
     * a player ends up telling people to reinstall to get their channels back.
     */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND lastSeenMillis < :syncStamp")
    suspend fun deleteStale(sourceId: Long, syncStamp: Long)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Preserves user state (favourites, hidden, manual order, custom names, EPG overrides) across a
     * catalogue refresh. Upsert would otherwise overwrite them with the defaults from the
     * freshly parsed rows.
     */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Channel>, syncStamp: Long) {
        if (incoming.isEmpty()) return

        val existing = userStateForSource(sourceId).associateBy { it.streamId }
        val merged = incoming.map { channel ->
            val previous = existing[channel.streamId]
            if (previous == null) {
                channel.copy(lastSeenMillis = syncStamp)
            } else {
                channel.copy(
                    id = previous.id,
                    favourite = previous.favourite,
                    // OR, not overwrite: the importer hides separator rows ('### UK ###')
                    // at parse time, and that decision must stick even for rows that
                    // predate the rule. User hides are preserved the same way.
                    hidden = previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex,
                    // A user's rename must outlive the provider's fresh name on every refresh.
                    customName = previous.customName,
                    epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId,
                    lastSeenMillis = syncStamp,
                )
            }
        }
        // Chunked so a 40,000-channel playlist does not build one enormous statement.
        merged.chunked(UPSERT_CHUNK).forEach { upsertAll(it) }
        deleteStale(sourceId, syncStamp)
    }

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Channel>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    fun observe(kind: StreamKind): Flow<List<Category>>

    /** One-shot list of every category of a kind, ordered like [observe] — the web manager reads
     * the live categories once per request rather than subscribing to a Flow. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    suspend fun allByKind(kind: StreamKind): List<Category>

    /** Minimal id+name rows for the re-normalise pass. */
    @Query("SELECT id, name FROM categories WHERE id IN (:ids)")
    suspend fun namesFor(ids: Set<String>): List<CategoryName>

    @Upsert
    suspend fun upsertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Projection for [CategoryDao.namesFor]. */
data class CategoryName(val id: String, val name: String)

/** Projection for [ChannelDao.channelCountsByCategory]. */
data class CategoryChannelCount(val sourceId: Long, val categoryId: String, val count: Int)

@Dao
interface EpgFeedDao {
    @Query("SELECT * FROM epg_feeds ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<EpgFeed>>

    @Query("SELECT * FROM epg_feeds")
    suspend fun all(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE enabled = 1")
    suspend fun enabled(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE providerSourceId = :sourceId")
    suspend fun forProvider(sourceId: Long): EpgFeed?

    @Query("SELECT * FROM epg_feeds WHERE url = :url")
    suspend fun byUrl(url: String): EpgFeed?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: EpgFeed): Long

    @Update
    suspend fun update(feed: EpgFeed)

    @Query("UPDATE epg_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_feeds SET lastSyncMillis = :millis, lastResult = :result WHERE id = :id")
    suspend fun markSynced(id: Long, millis: Long, result: String)

    @Query("DELETE FROM epg_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpgChannelAliasDao {
    @Query("SELECT * FROM epg_channels")
    suspend fun all(): List<EpgChannelAlias>

    @Query("SELECT COUNT(*) FROM epg_channels")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(aliases: List<EpgChannelAlias>)

    @Query("DELETE FROM epg_channels WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface ProgrammeDao {
    /**
     * The guide grid: everything overlapping the visible window.
     *
     * Filtered by time only, never by a list of channel ids. `epgChannelId IN (:ids)` is the
     * natural way to write this and it breaks on any provider large enough to matter — SQLite
     * caps bound variables at 999 on older Android, and a real catalogue has thousands of
     * channels. Callers group the result by channel in memory instead.
     */
    @Query(
        """
        SELECT * FROM programmes
        WHERE endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """
    )
    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>>

    /** What is on right now, for the channel list's "now playing" line. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis
        """
    )
    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>>

    @Query(
        """
        SELECT * FROM programmes
        WHERE epgChannelId = :channelId AND endUtcMillis > :nowUtcMillis
        ORDER BY startUtcMillis LIMIT :limit
        """
    )
    suspend fun upcoming(channelId: String, nowUtcMillis: Long, limit: Int): List<Programme>

    @Query("SELECT COUNT(*) FROM programmes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    /** Distinct guide channels that actually have programmes — the match report's baseline. */
    @Query("SELECT DISTINCT epgChannelId FROM programmes")
    suspend fun channelIdsWithProgrammes(): List<String>

    @Upsert
    suspend fun upsertAll(programmes: List<Programme>)

    /** Housekeeping: drop anything that finished before the retention cut-off. */
    @Query("DELETE FROM programmes WHERE endUtcMillis < :beforeUtcMillis")
    suspend fun deleteEndedBefore(beforeUtcMillis: Long)

    @Query("DELETE FROM programmes WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface MovieDao {
    @Query(
        """
        SELECT * FROM movies
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE favourite = 1 ORDER BY name")
    fun observeFavourites(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Movie>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Movie>>

    /**
     * Every movie, newest first — the working set for the Kotlin-side feeds (by-genre grouping,
     * genre-affinity recommendations, more-like-this). One pass over a few thousand rows is cheap;
     * per-genre `LIKE` queries would multiply round-trips and risk substring false positives.
     */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC")
    suspend fun all(): List<Movie>

    /** How many movies are on disk. A cheap COUNT the home feeds use to tell "the library grew"
     *  from "same as last time" without loading every row (see VodViewModel.loadHomeFeeds). */
    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int

    /**
     * Fallback for More-Like-This when a movie has no genre to match on: other titles from the same
     * source (and category, when it has one), best-rated first. Never returns the movie itself.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Movie>

    /**
     * Movies a person is billed in — the Plex-style "click an actor, see everything with them".
     * A substring `LIKE` over the comma-separated `cast` string: cheap and correct enough for a few
     * thousand VOD rows, where a full credits table would be over-engineering. Blank/absent cast is
     * excluded so an empty name never matches every row. Best-rated, then newest, first.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesWithActor(name: String, limit: Int): List<Movie>

    /** Movies a person directed. See [moviesWithActor]; same shape over the `director` field. */
    @Query(
        """
        SELECT * FROM movies
        WHERE director IS NOT NULL AND director != '' AND director LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesByDirector(name: String, limit: Int): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun byId(id: Long): Movie?

    /** Stream URL is the cross-device key: the same film has the same URL on the same provider. */
    @Query("SELECT * FROM movies WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Movie?

    @Query("UPDATE movies SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    // --- Sync: favourites by stream URL ---
    @Query("SELECT streamUrl FROM movies WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    @Query("UPDATE movies SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    @Upsert
    suspend fun upsertAll(movies: List<Movie>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** Every variant row behind any of [canonicalIds] — the join `CatalogRepository.searchMovies`
     *  uses to turn a canonical-content FTS match back into a displayable [Movie] row. */
    @Query("SELECT * FROM movies WHERE canonicalId IN (:canonicalIds)")
    suspend fun byCanonicalIds(canonicalIds: List<Long>): List<Movie>

    /** Variants this source's sync just wrote that `CanonicalMatcher` has not linked yet — either
     *  genuinely new, or left over from an interrupted previous sync. */
    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND canonicalId IS NULL")
    suspend fun pendingCanonicalLink(sourceId: Long): List<Movie>

    @Query("UPDATE movies SET canonicalId = :canonicalId, canonicalMatchKind = :matchKind WHERE id = :id")
    suspend fun setCanonical(id: Long, canonicalId: Long, matchKind: CanonicalMatchKind)
}

@Dao
interface SeriesDao {
    @Query(
        """
        SELECT * FROM series
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Series>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Series>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Series>>

    /** Every series, newest first — working set for the Kotlin-side by-genre / more-like-this feeds. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC")
    suspend fun all(): List<Series>

    /** How many series are on disk — the cheap "did the library grow" check for the home feeds. */
    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int

    /** Fallback for More-Like-This when a series has no genre: same source/category, best-rated first. */
    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Series>

    /** Series a person is billed in — the show half of "click an actor, see everything". See
     *  [MovieDao.moviesWithActor]. */
    @Query(
        """
        SELECT * FROM series
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun seriesWithActor(name: String, limit: Int): List<Series>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): Series?

    /** Looks a series row up by its provider identity rather than the local Room id — used to
     *  find the canonical link for a series whose episodes were just fetched. */
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId LIMIT 1")
    suspend fun bySourceAndSeriesId(sourceId: Long, seriesId: String): Series?

    @Query("UPDATE series SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    // --- Sync: favourites by provider series id (series carry no stream URL) ---
    @Query("SELECT seriesId FROM series WHERE favourite = 1")
    suspend fun favouriteSeriesIds(): List<String>

    @Query("UPDATE series SET favourite = 1 WHERE seriesId = :seriesId")
    suspend fun markFavouriteBySeriesId(seriesId: String)

    @Upsert
    suspend fun upsertAll(items: List<Series>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** See [MovieDao.byCanonicalIds]. */
    @Query("SELECT * FROM series WHERE canonicalId IN (:canonicalIds)")
    suspend fun byCanonicalIds(canonicalIds: List<Long>): List<Series>

    /** See [MovieDao.pendingCanonicalLink]. */
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND canonicalId IS NULL")
    suspend fun pendingCanonicalLink(sourceId: Long): List<Series>

    @Query("UPDATE series SET canonicalId = :canonicalId, canonicalMatchKind = :matchKind WHERE id = :id")
    suspend fun setCanonical(id: Long, canonicalId: Long, matchKind: CanonicalMatchKind)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeForSeries(sourceId: Long, seriesId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Episode?

    @Upsert
    suspend fun upsertAll(episodes: List<Episode>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** See [MovieDao.pendingCanonicalLink]. Scoped to one series' episodes, since episodes are
     *  fetched (and therefore linked) lazily per series, not in the bulk catalogue sync. */
    @Query(
        "SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId " +
            "AND canonicalEpisodeId IS NULL",
    )
    suspend fun pendingCanonicalLink(sourceId: Long, seriesId: String): List<Episode>

    @Query("UPDATE episodes SET canonicalEpisodeId = :canonicalEpisodeId, canonicalMatchKind = :matchKind WHERE id = :id")
    suspend fun setCanonical(id: Long, canonicalEpisodeId: Long, matchKind: CanonicalMatchKind)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun get(profileId: Long, key: String): PlaybackPosition?

    @Query(
        "SELECT * FROM playback_positions WHERE profileId = :profileId " +
            "ORDER BY updatedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(profileId: Long, limit: Int = 30): Flow<List<PlaybackPosition>>

    @Query("SELECT * FROM playback_positions")
    suspend fun all(): List<PlaybackPosition>

    /** One profile's whole watch history — the input to genre-affinity recommendations. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<PlaybackPosition>

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun delete(profileId: Long, key: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<Profile>

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Profile?

    @Insert
    suspend fun insert(profile: Profile): Long

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removing a profile takes its watch history with it. */
    @Query("DELETE FROM playback_positions WHERE profileId = :id")
    suspend fun deletePositions(id: Long)
}

@Dao
interface RecordingDao {
    /** The library, newest first — scheduled and in-progress float to the top by recency. */
    @Query("SELECT * FROM recordings ORDER BY COALESCE(NULLIF(startedAtMillis, 0), scheduledStartMillis) DESC, id DESC")
    fun observeAll(): Flow<List<Recording>>

    /** Just the ones capturing right now — the player watches this to light its Record button. */
    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    fun observeActive(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    suspend fun active(): List<Recording>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY scheduledStartMillis")
    suspend fun scheduled(): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    /** Snapshot of every recording — the NAS sync reads this to gather the smb:// ones to share. */
    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    /** De-dup for NAS sync: a recording's smb:// locator is its stable cross-device identity. */
    @Query("SELECT * FROM recordings WHERE filePath = :filePath LIMIT 1")
    suspend fun byFilePath(filePath: String): Recording?

    /** For series-link de-dup: has this rule already booked this programme window on this channel? */
    @Query(
        """
        SELECT COUNT(*) FROM recordings
        WHERE seriesRuleId = :ruleId AND channelId = :channelId AND scheduledStartMillis = :startMillis
        """
    )
    suspend fun countForRuleAt(ruleId: Long, channelId: Long, startMillis: Long): Int

    /** Any live/scheduled booking for this exact airing (same channel + start) — for manual de-dup. */
    @Query(
        """
        SELECT * FROM recordings
        WHERE channelId = :channelId AND scheduledStartMillis = :startMillis
          AND status IN ('SCHEDULED', 'RECORDING') LIMIT 1
        """
    )
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null)

    @Query("UPDATE recordings SET sizeBytes = :bytes WHERE id = :id")
    suspend fun setSize(id: Long, bytes: Long)

    /** Repoint a row at its real locator — used when a USB (SAF) capture learns its content:// URI. */
    @Query("UPDATE recordings SET filePath = :filePath WHERE id = :id")
    suspend fun setFilePath(id: Long, filePath: String)

    @Query(
        "UPDATE recordings SET status = :status, startedAtMillis = :startedAt WHERE id = :id"
    )
    suspend fun markStarted(id: Long, status: RecordingStatus, startedAt: Long)

    @Query(
        "UPDATE recordings SET status = :status, endedAtMillis = :endedAt, sizeBytes = :bytes, error = :error WHERE id = :id"
    )
    suspend fun markFinished(id: Long, status: RecordingStatus, endedAt: Long, bytes: Long, error: String?)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * On a cold start, any row still marked RECORDING is a leftover from a process that was
     * killed mid-capture — reconcile it to FAILED so the UI never shows a phantom "recording".
     */
    @Query("UPDATE recordings SET status = 'FAILED', error = 'Interrupted' WHERE status = 'RECORDING'")
    suspend fun failInterrupted()
}

@Dao
interface SeriesRuleDao {
    @Query("SELECT * FROM series_rules ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<SeriesRule>>

    @Query("SELECT * FROM series_rules WHERE enabled = 1")
    suspend fun enabled(): List<SeriesRule>

    @Query("SELECT * FROM series_rules WHERE channelId = :channelId AND titleKey = :titleKey LIMIT 1")
    suspend fun forChannelTitle(channelId: Long, titleKey: String): SeriesRule?

    @Insert
    suspend fun insert(rule: SeriesRule): Long

    @Query("DELETE FROM series_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    /** All reminders, soonest first — the reminders list and boot re-arm both read this. */
    @Query("SELECT * FROM reminders ORDER BY startUtcMillis")
    fun observeAll(): Flow<List<Reminder>>

    /** Still-future, not-yet-fired reminders — the set to re-arm after a reboot. */
    @Query("SELECT * FROM reminders WHERE fired = 0 AND startUtcMillis > :nowMillis ORDER BY startUtcMillis")
    suspend fun upcoming(nowMillis: Long): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** De-dup: is there already a reminder for this (channel, slot)? */
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis LIMIT 1")
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder?

    /** The guide grid asks: which upcoming slots already have a bell? Keyed by start time. */
    @Query("SELECT startUtcMillis FROM reminders WHERE channelId = :channelId AND fired = 0")
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis")
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long)

    /** Housekeeping: drop reminders whose programme has already ended. */
    @Query("DELETE FROM reminders WHERE endUtcMillis < :beforeMillis")
    suspend fun deleteEndedBefore(beforeMillis: Long)
}

/**
 * The canonical, cross-provider movie catalog — what Search/Home/detail pages actually read.
 * A `Movie` row is a source variant behind one of these; see [CanonicalContent]. Matching logic
 * (which row a new [Movie] links to, if any) lives in `CanonicalMatcher`, not here — this DAO
 * only exposes the primitives it and the UI need.
 */
@Dao
interface CanonicalMovieDao {
    @Query("SELECT * FROM canonical_content WHERE kind = 'MOVIE' ORDER BY addedMillis DESC")
    fun observeAll(): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE kind = 'MOVIE' AND favourite = 1 ORDER BY title")
    fun observeFavourites(): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE kind = 'MOVIE' ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<CanonicalContent>>

    /** [ftsQuery] is FTS4 `MATCH` syntax — build it with [FtsQuery.prefixMatch], never pass raw
     *  user input straight through. */
    @Query(
        """
        SELECT canonical_content.* FROM canonical_content
        JOIN canonical_content_fts ON canonical_content.id = canonical_content_fts.rowid
        WHERE canonical_content_fts MATCH :ftsQuery AND canonical_content.kind = 'MOVIE'
        LIMIT :limit
        """
    )
    fun search(ftsQuery: String, limit: Int = 200): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE id = :id")
    suspend fun byId(id: Long): CanonicalContent?

    @Query("SELECT * FROM canonical_content WHERE kind = 'MOVIE' AND tmdbId = :tmdbId LIMIT 1")
    suspend fun findByTmdbId(tmdbId: String): CanonicalContent?

    /** Every canonical movie sharing [titleKey], any year (including none) — `CanonicalMatcher`
     *  decides year compatibility itself rather than encoding that ambiguity rule in SQL. */
    @Query("SELECT * FROM canonical_content WHERE kind = 'MOVIE' AND titleKey = :titleKey")
    suspend fun findAllByTitleKey(titleKey: String): List<CanonicalContent>

    @Insert
    suspend fun insert(content: CanonicalContent): Long

    @Update
    suspend fun update(content: CanonicalContent)

    @Query("UPDATE canonical_content SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE canonical_content SET lastViewedMillis = :atMillis WHERE id = :id")
    suspend fun touchViewed(id: Long, atMillis: Long)

    /** Every playable source behind this canonical movie — see [SourceVariant]. */
    @Query(
        """
        SELECT movies.sourceId AS sourceId, sources.name AS sourceName, movies.streamId AS streamId,
               movies.qualityLabel AS quality, movies.qualityRank AS qualityRank,
               movies.language AS language, movies.codec AS codec,
               movies.streamUrl AS streamUrl, sources.userAgent AS userAgent, NULL AS cmd
        FROM movies JOIN sources ON sources.id = movies.sourceId
        WHERE movies.canonicalId = :canonicalId
        ORDER BY movies.qualityRank DESC
        """
    )
    suspend fun variantsFor(canonicalId: Long): List<SourceVariant>

    /** Merge-on-collision support (TMDB enrichment resolving two guessed entries to one real
     *  film): repoints every variant from the losing id to the surviving one. */
    @Query("UPDATE movies SET canonicalId = :newId WHERE canonicalId = :oldId")
    suspend fun repointVariants(oldId: Long, newId: Long)

    @Query("DELETE FROM canonical_content WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * The canonical, cross-provider series catalog. A `Series` row is one provider's copy of the
 * show's metadata/episode-list entry point; actual playback variants live one level down, per
 * episode — see [CanonicalEpisodeDao.variantsFor]. Mirrors [CanonicalMovieDao]; see it for the
 * shared-query rationale.
 */
@Dao
interface CanonicalSeriesDao {
    @Query("SELECT * FROM canonical_content WHERE kind = 'SERIES' ORDER BY addedMillis DESC")
    fun observeAll(): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE kind = 'SERIES' AND favourite = 1 ORDER BY title")
    fun observeFavourites(): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE kind = 'SERIES' ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<CanonicalContent>>

    @Query(
        """
        SELECT canonical_content.* FROM canonical_content
        JOIN canonical_content_fts ON canonical_content.id = canonical_content_fts.rowid
        WHERE canonical_content_fts MATCH :ftsQuery AND canonical_content.kind = 'SERIES'
        LIMIT :limit
        """
    )
    fun search(ftsQuery: String, limit: Int = 200): Flow<List<CanonicalContent>>

    @Query("SELECT * FROM canonical_content WHERE id = :id")
    suspend fun byId(id: Long): CanonicalContent?

    @Query("SELECT * FROM canonical_content WHERE kind = 'SERIES' AND tmdbId = :tmdbId LIMIT 1")
    suspend fun findByTmdbId(tmdbId: String): CanonicalContent?

    @Query("SELECT * FROM canonical_content WHERE kind = 'SERIES' AND titleKey = :titleKey")
    suspend fun findAllByTitleKey(titleKey: String): List<CanonicalContent>

    @Insert
    suspend fun insert(content: CanonicalContent): Long

    @Update
    suspend fun update(content: CanonicalContent)

    @Query("UPDATE canonical_content SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE canonical_content SET lastViewedMillis = :atMillis WHERE id = :id")
    suspend fun touchViewed(id: Long, atMillis: Long)

    /** Every source's copy of this show's own metadata/episode-list row — not directly playable
     *  (a `Series` row carries no stream URL; see [CanonicalEpisodeDao.variantsFor] for that). */
    @Query("SELECT * FROM series WHERE canonicalId = :canonicalId")
    suspend fun variantRowsFor(canonicalId: Long): List<Series>

    @Query("UPDATE series SET canonicalId = :newId WHERE canonicalId = :oldId")
    suspend fun repointVariants(oldId: Long, newId: Long)

    @Query("DELETE FROM canonical_content WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * A canonical show's (season, episode) slots — see [CanonicalEpisode]. Each episode's playable
 * sources are [variantsFor], joined the same way [CanonicalMovieDao.variantsFor] is.
 */
@Dao
interface CanonicalEpisodeDao {
    @Query(
        "SELECT * FROM canonical_episodes WHERE canonicalSeriesId = :canonicalSeriesId " +
            "ORDER BY season, episodeNumber",
    )
    fun observeForSeries(canonicalSeriesId: Long): Flow<List<CanonicalEpisode>>

    /** Non-reactive form of [observeForSeries] — for the Kotlin-side merge pass in
     *  `CanonicalEnrichmentWorker` when two canonical series turn out to be the same show. */
    @Query(
        "SELECT * FROM canonical_episodes WHERE canonicalSeriesId = :canonicalSeriesId " +
            "ORDER BY season, episodeNumber",
    )
    suspend fun forSeries(canonicalSeriesId: Long): List<CanonicalEpisode>

    @Query("SELECT * FROM canonical_episodes WHERE id = :id")
    suspend fun byId(id: Long): CanonicalEpisode?

    @Query(
        "SELECT * FROM canonical_episodes WHERE canonicalSeriesId = :canonicalSeriesId " +
            "AND season = :season AND episodeNumber = :episodeNumber LIMIT 1",
    )
    suspend fun findBySlot(canonicalSeriesId: Long, season: Int, episodeNumber: Int): CanonicalEpisode?

    @Insert
    suspend fun insert(episode: CanonicalEpisode): Long

    @Update
    suspend fun update(episode: CanonicalEpisode)

    @Query(
        """
        SELECT episodes.sourceId AS sourceId, sources.name AS sourceName, episodes.episodeId AS streamId,
               episodes.qualityLabel AS quality, episodes.qualityRank AS qualityRank,
               episodes.language AS language, episodes.codec AS codec,
               episodes.streamUrl AS streamUrl, sources.userAgent AS userAgent, NULL AS cmd
        FROM episodes JOIN sources ON sources.id = episodes.sourceId
        WHERE episodes.canonicalEpisodeId = :canonicalEpisodeId
        ORDER BY episodes.qualityRank DESC
        """
    )
    suspend fun variantsFor(canonicalEpisodeId: Long): List<SourceVariant>

    @Query("UPDATE episodes SET canonicalEpisodeId = :newId WHERE canonicalEpisodeId = :oldId")
    suspend fun repointVariants(oldId: Long, newId: Long)

    @Query("DELETE FROM canonical_episodes WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * The user's remembered per-title choice on each of the three independent player axes — see
 * [PreferredVariant]. Deliberately no "upsert one column" query: SQLite's `ON CONFLICT DO UPDATE`
 * needs a newer SQLite than this app's `minSdk` guarantees, so a caller wanting to change just one
 * axis reads the current row (or treats a missing one as all-null), copies it with that one field
 * changed, and [upsert]s the result — the same merge-in-Kotlin pattern `ChannelDao.replaceCatalogue`
 * already uses elsewhere in this file.
 */
@Dao
interface PreferredVariantDao {
    @Query("SELECT * FROM preferred_variants WHERE contentKey = :contentKey")
    suspend fun get(contentKey: String): PreferredVariant?

    @Query("SELECT * FROM preferred_variants WHERE contentKey = :contentKey")
    fun observe(contentKey: String): Flow<PreferredVariant?>

    @Upsert
    suspend fun upsert(row: PreferredVariant)

    @Query("DELETE FROM preferred_variants WHERE contentKey = :contentKey")
    suspend fun clear(contentKey: String)
}
