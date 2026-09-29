package app.tufaratv.telly

import app.tufaratv.data.db.CategoryDao as OpenTvCategoryDao
import app.tufaratv.data.db.ChannelDao as OpenTvChannelDao
import app.tufaratv.data.db.ProgrammeDao as OpenTvProgrammeDao
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.model.shownName
import com.johncorser.telly.features.epg.db.ProgramDetails
import com.johncorser.telly.features.epg.db.ProgramEntity
import com.johncorser.telly.features.playlist.db.ChannelEntity
import com.johncorser.telly.features.search.db.SearchDao
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Search bridge that keeps Telly's search UI while querying OpenTV's catalogue.
 */
class OpenTvSearchDaoAdapter(
    private val channels: OpenTvChannelDao,
    private val programmes: OpenTvProgrammeDao,
    private val categories: OpenTvCategoryDao,
) : SearchDao {
    override suspend fun channels(nameLike: String, numberLike: String): List<ChannelEntity> {
        val query = decodeLike(nameLike)
        val numberPrefix = numberLike.removeSuffix("%").takeIf { it.isNotBlank() }
        val groups = categories.allByKind(StreamKind.LIVE)
            .associate { "${it.sourceId}:${it.id}" to it.name }

        return channels.allForMatching()
            .asSequence()
            .filterNot { it.hidden }
            .filter { row ->
                val wordMatch = query.isNotBlank() && hasWordPrefix(row.shownName, query)
                val numberMatch = numberPrefix != null && row.number?.toString()?.startsWith(numberPrefix) == true
                wordMatch || numberMatch
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownName })
            .map { row ->
                row.toTellyChannel(
                    row.categoryId?.let { groups["${row.sourceId}:$it"] ?: it },
                )
            }
            .toList()
    }

    override suspend fun programs(
        titleLike: String,
        atMs: Long,
        limit: Int,
    ): List<ProgramEntity> {
        val query = decodeLike(titleLike)
        if (query.isBlank()) return emptyList()
        return programmes
            .observeWindow(atMs, Long.MAX_VALUE)
            .first()
            .asSequence()
            .filter { it.endUtcMillis > atMs && hasWordPrefix(it.title, query) }
            .sortedWith(compareBy({ it.startUtcMillis }, { it.epgChannelId }))
            .take(limit)
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

    private fun hasWordPrefix(value: String, query: String): Boolean {
        val needle = query.lowercase(Locale.ROOT)
        return value
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .any { it.startsWith(needle) }
    }

    private fun decodeLike(pattern: String): String =
        pattern
            .removePrefix("% ")
            .removeSuffix("%")
            .replace("\\_", "_")
            .replace("\\%", "%")
            .replace("\\\\", "\\")
}
