package app.tufaratv.telly

import app.tufaratv.data.db.ChannelDao as OpenTvChannelDao
import app.tufaratv.data.db.SourceDao
import app.tufaratv.data.model.shownName
import com.johncorser.telly.features.playlist.M3uChannel
import com.johncorser.telly.features.playlist.M3uPlaylist
import com.johncorser.telly.features.playlist.PlaylistRepository
import com.johncorser.telly.features.playlist.StoredPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * Presents OpenTV sources as Telly playlists without moving source ownership.
 */
class OpenTvPlaylistRepository(
    private val sources: SourceDao,
    private val channels: OpenTvChannelDao,
    private val addSource: suspend (sourceUrl: String, playlist: M3uPlaylist, name: String?) -> Unit,
    private val changeSourceUrl: suspend (sourceId: Long, newUrl: String) -> Boolean,
    private val deleteSource: suspend (sourceId: Long) -> Unit,
) : PlaylistRepository {
    override val playlists: Flow<List<StoredPlaylist>> =
        sources.observeAll()
            .combine(channels.observe(null, null)) { sourceRows, channelRows ->
                sourceRows.map { source ->
                    StoredPlaylist(
                    sourceUrl = sourceKey(source.id),
                    name = source.name,
                    playlist =
                        M3uPlaylist(
                            epgUrl = source.epgUrl,
                            channels =
                                channelRows
                                    .asSequence()
                                    .filter { it.sourceId == source.id }
                                    .map {
                                        M3uChannel(
                                            title = it.shownName,
                                            streamUrl = it.streamUrl,
                                            tvgId = it.epgOverrideId ?: it.matchedEpgId ?: it.epgChannelId,
                                            tvgName = it.shownName,
                                            tvgLogo = it.logoUrl,
                                            groupTitle = it.categoryId,
                                            catchup = if (it.tvArchive) "xtream-codes" else null,
                                            catchupDays = it.tvArchiveDays.takeIf { days -> days > 0 },
                                        )
                                    }
                                    .toList(),
                        ),
                )
                }
            }
            .flowOn(Dispatchers.Default)

    override suspend fun add(sourceUrl: String, playlist: M3uPlaylist, name: String?) {
        addSource(sourceUrl, playlist, name)
    }

    override suspend fun rename(sourceUrl: String, name: String) {
        val id = sourceId(sourceUrl) ?: return
        val source = sources.byId(id) ?: return
        sources.update(source.copy(name = name.trim().ifBlank { source.name }))
    }

    override suspend fun changeUrl(oldUrl: String, newUrl: String): Boolean {
        val id = sourceId(oldUrl) ?: return false
        return changeSourceUrl(id, newUrl)
    }

    override suspend fun delete(sourceUrl: String) {
        val id = sourceId(sourceUrl) ?: return
        deleteSource(id)
    }

    private fun sourceId(value: String): Long? = value.removePrefix(PREFIX).toLongOrNull()

    private fun sourceKey(id: Long): String = PREFIX + id

    private companion object {
        const val PREFIX = "opentv://source/"
    }
}
