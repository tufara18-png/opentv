/*
 * Telly data adapter backed by OpenTV's catalogue.
 *
 * Telly owns the TV UI. OpenTV remains the source of truth for channels and
 * user catalogue state. No telly.db channel rows are required for this path.
 */
package app.tufaratv.telly

import app.tufaratv.data.db.CategoryDao as OpenTvCategoryDao
import app.tufaratv.data.db.ChannelDao as OpenTvChannelDao
import app.tufaratv.data.model.Channel as OpenTvChannel
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.model.shownName
import com.johncorser.telly.features.playlist.db.ChannelCatchup
import com.johncorser.telly.features.playlist.db.ChannelDao
import com.johncorser.telly.features.playlist.db.ChannelEntity
import com.johncorser.telly.features.playlist.db.ChannelFlags
import com.johncorser.telly.features.playlist.db.ChannelGroupCount
import com.johncorser.telly.features.playlist.db.ChannelOverrides
import com.johncorser.telly.features.playlist.db.ChannelSource
import com.johncorser.telly.features.playlist.db.TvgOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Makes OpenTV's live catalogue look like Telly's [ChannelDao].
 *
 * OpenTV source ids are used as Telly playlist ids. That keeps ids stable and
 * lets Telly keep its existing playlist/group navigation unchanged.
 */
class OpenTvChannelDaoAdapter(
    private val channels: OpenTvChannelDao,
    private val categories: OpenTvCategoryDao,
) : ChannelDao {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mappedVisible: Flow<List<ChannelEntity>> =
        channels.observeVisibleCount()
            .coalescedCatalogueCounts()
            .mapLatest {
                val rows = channels.visibleSnapshot()
                val groups = categories.allByKind(StreamKind.LIVE)
                val names = groups.associate { categoryKey(it.sourceId, it.id) to it.name }
                rows.map { row -> row.toTelly(names) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun observeForPlaylist(playlistId: Long): Flow<List<ChannelEntity>> =
        channels.observeForSource(playlistId)
            .combine(categories.observe(StreamKind.LIVE)) { rows, groups ->
                val names = groups
                    .asSequence()
                    .filter { it.sourceId == playlistId }
                    .associate { categoryKey(it.sourceId, it.id) to it.name }
                rows.map { it.toTelly(names) }
            }
            .flowOn(Dispatchers.Default)

    override fun observeByGroup(playlistId: Long, groupTitle: String): Flow<List<ChannelEntity>> =
        categories.observe(StreamKind.LIVE)
            .map { groups ->
                groups
                    .asSequence()
                    .filter { it.sourceId == playlistId && it.name == groupTitle }
                    .map { it.id }
                    .toList()
            }
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    channels.observeInCategoriesForSource(playlistId, ids)
                        .map { rows -> rows.map { it.toTellyChannel(groupTitle) } }
                }
            }
            .flowOn(Dispatchers.Default)

    override fun observeGroups(playlistId: Long): Flow<List<ChannelGroupCount>> =
        channels.observeChannelCountsByCategory(playlistId)
            .combine(categories.observe(StreamKind.LIVE)) { counts, groups ->
                val names = groups
                    .asSequence()
                    .filter { it.sourceId == playlistId }
                    .associateBy({ it.id }, { it.name })
                counts.mapNotNull { row ->
                    val title = row.categoryId?.let(names::get) ?: return@mapNotNull null
                    ChannelGroupCount(title, row.count)
                }
            }
            .flowOn(Dispatchers.Default)

    override fun observeVisible(): Flow<List<ChannelEntity>> = mappedVisible

    /*
     * OpenTV's public reactive catalogue query intentionally excludes hidden rows.
     * Until the manager adapter lands, Telly's bulk editor sees the visible set.
     * Normal guide/playback behavior is already fully backed by OpenTV here.
     */
    override fun observeAll(): Flow<List<ChannelEntity>> = mappedVisible

    override suspend fun totalCount(): Int = channels.totalVisibleCount()

    override suspend fun forPlaylist(playlistId: Long): List<ChannelEntity> {
        val names = categories.allByKind(StreamKind.LIVE)
            .associate { categoryKey(it.sourceId, it.id) to it.name }
        return channels.forSource(playlistId).map { it.toTelly(names) }
    }

    override suspend fun deleteForPlaylist(playlistId: Long) {
        channels.deleteForSource(playlistId)
    }

    override suspend fun insertAll(channels: List<ChannelEntity>) = readOnly()

    override suspend fun byId(id: Long): ChannelEntity? = channels.byId(id)?.toTelly()

    override fun observeById(id: Long): Flow<ChannelEntity?> =
        channels.observeById(id).map { it?.toTelly() }

    override fun observeEpgOffsets(): Flow<List<TvgOffset>> =
        flowOf(emptyList())

    override suspend fun update(channel: ChannelEntity) {
        val current = channels.byId(channel.id) ?: return
        if (current.favourite != channel.flags.favorite) {
            channels.setFavourite(current.id, channel.flags.favorite)
        }
        if (current.hidden != channel.flags.hidden) {
            channels.setHidden(current.id, channel.flags.hidden)
        }
        if (current.customName != channel.overrides.customName) {
            channels.setCustomName(current.id, channel.overrides.customName)
        }
        if (current.epgOverrideId != channel.overrides.epgOverride) {
            channels.setEpgOverride(current.id, channel.overrides.epgOverride)
        }
        if (current.sortIndex != channel.sortIndex) {
            channels.setSortIndex(current.id, channel.sortIndex)
        }
    }

    private fun OpenTvChannel.toTelly(categoryNames: Map<String, String> = emptyMap()): ChannelEntity =
        toTellyChannel(categoryId?.let { categoryNames[categoryKey(sourceId, it)] ?: it })

    private fun readOnly(): Nothing =
        error("OpenTV owns channel writes; Telly is connected as a frontend")

    private fun categoryKey(sourceId: Long, categoryId: String): String = "$sourceId:$categoryId"
}


internal fun OpenTvChannel.toTellyChannel(groupTitle: String? = categoryId): ChannelEntity {
    val effectiveTvgId = epgOverrideId ?: matchedEpgId ?: epgChannelId
    return ChannelEntity(
        id = id,
        playlistId = sourceId,
        number = number ?: (sortIndex + 1),
        sortIndex = sortIndex,
        source = ChannelSource(
            name = shownName,
            groupTitle = groupTitle,
            logoUrl = logoUrl,
            streamUrl = streamUrl,
            tvgId = effectiveTvgId,
        ),
        flags = ChannelFlags(
            favorite = favourite,
            hidden = hidden,
        ),
        catchup = ChannelCatchup(
            catchupType = if (tvArchive) "xtream-codes" else null,
            catchupDays = tvArchiveDays.takeIf { it > 0 },
        ),
        overrides = ChannelOverrides(
            customName = customName,
            epgOverride = epgOverrideId,
        ),
    )
}


@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Int>.coalescedCatalogueCounts(delayMillis: Long = 5_000L): Flow<Int> {
    var emittedFirstPositive = false
    return transformLatest { count ->
        if (count > 0 && !emittedFirstPositive) {
            emittedFirstPositive = true
            emit(count)
        } else {
            if (count > 0) kotlinx.coroutines.delay(delayMillis)
            emit(count)
        }
    }
}
