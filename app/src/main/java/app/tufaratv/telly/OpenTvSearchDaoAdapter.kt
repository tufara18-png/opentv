package app.tufaratv.telly

import app.tufaratv.data.db.CategoryDao as OpenTvCategoryDao
import app.tufaratv.data.db.ChannelDao as OpenTvChannelDao
import app.tufaratv.data.db.ProgrammeDao as OpenTvProgrammeDao
import app.tufaratv.data.model.StreamKind
import com.johncorser.telly.features.epg.db.ProgramDetails
import com.johncorser.telly.features.epg.db.ProgramEntity
import com.johncorser.telly.features.playlist.db.ChannelEntity
import com.johncorser.telly.features.search.db.SearchDao

/**
 * Search bridge that keeps Telly's search UI while querying OpenTV's catalogue.
 */
class OpenTvSearchDaoAdapter(
    private val channels: OpenTvChannelDao,
    private val programmes: OpenTvProgrammeDao,
    private val categories: OpenTvCategoryDao,
) : SearchDao {
    override suspend fun channels(nameLike: String, numberLike: String): List<ChannelEntity> {
        val groups = categories.allByKind(StreamKind.LIVE)
            .associate { "${it.sourceId}:${it.id}" to it.name }

        return channels.searchForTelly(nameLike, numberLike)
            .map { row ->
                row.toTellyChannel(
                    row.categoryId?.let { groups["${row.sourceId}:$it"] ?: it },
                )
            }
    }

    override suspend fun programs(
        titleLike: String,
        atMs: Long,
        limit: Int,
    ): List<ProgramEntity> {
        if (decodeLike(titleLike).isBlank()) return emptyList()
        return programmes
            .searchFutureForTelly(titleLike, atMs, limit)
            .map {
                ProgramEntity(
                    id = it.id,
                    channelTvgId = it.epgChannelId,
                    startMs = it.startUtcMillis,
                    endMs = it.endUtcMillis,
                    details =
                        ProgramDetails(
                            title = it.title,
                            description = it.description,
                            category = it.category,
                        ),
                )
            }
            .toList()
    }

    private fun decodeLike(pattern: String): String =
        pattern
            .removePrefix("% ")
            .removeSuffix("%")
            .replace("\\_", "_")
            .replace("\\%", "%")
            .replace("\\\\", "\\")
}
