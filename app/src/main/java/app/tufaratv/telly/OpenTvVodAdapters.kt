package app.tufaratv.telly

import app.tufaratv.core.AppSettings
import app.tufaratv.data.db.CategoryDao as OpenTvCategoryDao
import app.tufaratv.data.db.MovieDao as OpenTvMovieDao
import app.tufaratv.data.db.PlaybackPositionDao as OpenTvPlaybackPositionDao
import app.tufaratv.data.model.PlaybackPosition
import app.tufaratv.data.model.StreamKind
import com.johncorser.telly.features.vod.db.VodItemDao
import com.johncorser.telly.features.vod.db.VodItemEntity
import com.johncorser.telly.features.vod.db.VodPositionDao
import com.johncorser.telly.features.vod.db.VodPositionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class OpenTvVodItemDaoAdapter(
    private val movies: OpenTvMovieDao,
    private val categories: OpenTvCategoryDao,
) : VodItemDao {
    override fun observeAll(): Flow<List<VodItemEntity>> =
        movies.observe(null).combine(categories.observe(StreamKind.MOVIE)) { rows, groups ->
            val names = groups.associate { "${it.sourceId}:${it.id}" to it.name }
            rows.mapIndexed { index, movie ->
                VodItemEntity(
                    id = movie.id,
                    playlistId = movie.sourceId,
                    sortIndex = index,
                    itemKey = key(movie.id),
                    name = movie.name,
                    groupTitle = movie.categoryId?.let { names["${movie.sourceId}:$it"] ?: it },
                    logoUrl = movie.posterUrl,
                    streamUrl = movie.streamUrl,
                )
            }
        }

    override suspend fun byKey(itemKey: String): VodItemEntity? {
        val id = itemKey.removePrefix(PREFIX).toLongOrNull() ?: return null
        val movie = movies.byId(id) ?: return null
        return VodItemEntity(
            id = movie.id,
            playlistId = movie.sourceId,
            sortIndex = 0,
            itemKey = key(movie.id),
            name = movie.name,
            groupTitle = movie.categoryId,
            logoUrl = movie.posterUrl,
            streamUrl = movie.streamUrl,
        )
    }

    override suspend fun totalCount(): Int = movies.count()

    override suspend fun deleteForPlaylist(playlistId: Long) =
        error("OpenTV owns VOD writes; Telly is connected as a frontend")

    override suspend fun insertAll(items: List<VodItemEntity>) =
        error("OpenTV owns VOD writes; Telly is connected as a frontend")

    private fun key(id: Long): String = PREFIX + id

    private companion object {
        const val PREFIX = "movie:"
    }
}

class OpenTvVodPositionDaoAdapter(
    private val positions: OpenTvPlaybackPositionDao,
    private val settings: AppSettings,
) : VodPositionDao {
    override fun observeAll(): Flow<List<VodPositionEntity>> =
        settings.activeProfileId.flatMapRecent(positions)

    override suspend fun byKey(itemKey: String): VodPositionEntity? {
        val row = positions.get(settings.activeProfileId.value, itemKey) ?: return null
        return row.toTelly()
    }

    override suspend fun upsert(position: VodPositionEntity) {
        positions.upsert(
            PlaybackPosition(
                profileId = settings.activeProfileId.value,
                mediaKey = position.itemKey,
                positionMillis = position.positionMs,
                durationMillis = position.durationMs,
                updatedAtMillis = position.updatedAtMs,
            ),
        )
    }

    override suspend fun delete(itemKey: String) {
        positions.delete(settings.activeProfileId.value, itemKey)
    }

    override suspend fun clearAll() {
        positions.forProfile(settings.activeProfileId.value)
            .forEach { positions.delete(it.profileId, it.mediaKey) }
    }

    private fun PlaybackPosition.toTelly(): VodPositionEntity =
        VodPositionEntity(
            itemKey = mediaKey,
            positionMs = positionMillis,
            durationMs = durationMillis,
            updatedAtMs = updatedAtMillis,
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.flow.StateFlow<Long>.flatMapRecent(
    positions: OpenTvPlaybackPositionDao,
): Flow<List<VodPositionEntity>> =
    flatMapLatest { profileId ->
        positions.observeRecent(profileId, 200).map { rows ->
            rows.map {
                VodPositionEntity(
                    itemKey = it.mediaKey,
                    positionMs = it.positionMillis,
                    durationMs = it.durationMillis,
                    updatedAtMs = it.updatedAtMillis,
                )
            }
        }
    }
