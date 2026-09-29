/*
 * Telly EPG read adapter backed by OpenTV's programme store.
 */
package app.tufaratv.telly

import app.tufaratv.data.db.ProgrammeDao as OpenTvProgrammeDao
import app.tufaratv.data.model.Programme
import com.johncorser.telly.features.epg.db.ProgramDao
import com.johncorser.telly.features.epg.db.ProgramDetails
import com.johncorser.telly.features.epg.db.ProgramEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Exposes OpenTV's merged/matched EPG through the DAO shape Telly already uses.
 *
 * OpenTV keeps ownership of EPG downloads and writes. The write methods are
 * deliberately blocked so a Telly settings action can never wipe or fork the
 * OpenTV guide database by accident.
 */
class OpenTvProgramDaoAdapter(
    private val programmes: OpenTvProgrammeDao,
) : ProgramDao {
    override fun observeWindow(
        tvgIds: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<ProgramEntity>> =
        programmes.observeWindowForChannels(tvgIds, fromMs, toMs)
            .map { rows -> rows.map { it.toTelly() } }

    override fun observeAiringOrUpcoming(
        tvgIds: List<String>,
        atMs: Long,
    ): Flow<List<ProgramEntity>> =
        programmes.observeWindowForChannels(tvgIds, atMs, Long.MAX_VALUE)
            .map { rows -> rows.map(Programme::toTelly) }

    override suspend fun upsertAll(programs: List<ProgramEntity>) = readOnly()

    override suspend fun deleteFor(tvgIds: List<String>) = readOnly()

    override suspend fun deleteEndedBefore(beforeMs: Long) {
        programmes.deleteEndedBefore(beforeMs)
    }

    override suspend fun count(): Int =
        programmes.channelIdsWithProgrammes().size

    override fun observeChannelIds(): Flow<List<String>> =
        programmes.observeChannelIdsWithProgrammes()

    private fun Programme.toTelly(): ProgramEntity =
        ProgramEntity(
            id = id,
            channelTvgId = epgChannelId,
            startMs = startUtcMillis,
            endMs = endUtcMillis,
            details = ProgramDetails(
                title = title,
                description = description,
                category = category,
                episode = episodeLabel(),
            ),
        )

    private fun Programme.episodeLabel(): String? {
        if (season == null && episode == null) return null
        return buildString {
            season?.let { append("S").append(it) }
            if (season != null && episode != null) append(" ")
            episode?.let { append("E").append(it) }
        }
    }

    private fun readOnly(): Nothing =
        error("OpenTV owns EPG writes; Telly is connected as a read-only frontend")
}
