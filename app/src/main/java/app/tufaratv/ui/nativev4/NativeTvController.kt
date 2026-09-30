package app.tufaratv.ui.nativev4

import android.content.Context
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.PlaybackPosition
import app.tufaratv.data.model.Programme
import app.tufaratv.data.model.Series
import app.tufaratv.data.repo.QualitySelector
import app.tufaratv.data.repo.SourceVariant
import app.tufaratv.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeTvController(context: Context, private val scope: CoroutineScope) {
    private val graph = ServiceLocator.get(context)
    private val settings = graph.settings

    val playerController = PlayerController(
        context = context,
        scope = scope,
        httpClient = graph.streamingHttpClient,
        subtitlesEnabled = settings.subtitlesEnabled.value,
        dvr = settings.livePauseEnabled.value,
        sharedLive = true,
    )
    val player get() = playerController.player

    var channels: List<TellyChannelRow> = emptyList()
        private set
    var programmes: Map<String, List<Programme>> = emptyMap()
        private set

    private var resumeJob: Job? = null
    private var activeVodKey: String? = null
    private var activeVodDuration = 0L

    fun loadGuide(onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) {
        scope.launch {
            val rows = if (settings.deduplicateChannels.value) {
                graph.database.channels().visibleLogicalSnapshot()
            } else {
                graph.database.channels().visibleSnapshot()
            }
            val now = System.currentTimeMillis()
            val allPrograms = graph.database.programmes()
                .observeWindow(now - 7_200_000L, now + 28_800_000L)
                .first()
            channels = rows
            programmes = allPrograms.groupBy { it.epgChannelId }
            onReady(rows, programmes)
            val preferred = settings.lastChannelId.takeIf { id -> rows.any { it.id == id } }
                ?: rows.firstOrNull()?.id
            preferred?.let(::tuneChannel)
        }
    }

    fun refreshGuide(onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) =
        loadGuide(onReady)

    fun tuneChannel(channelId: Long) {
        stopVodCheckpointing()
        scope.launch {
            val base = graph.database.channels().byId(channelId) ?: return@launch
            val variants = if (base.groupKey.isBlank()) listOf(base) else {
                graph.database.channels().variantsInGroup(base.groupKey).ifEmpty { listOf(base) }
            }
            val sourceVariants = variants.mapNotNull { channel ->
                val source = graph.sourceRepository.byId(channel.sourceId) ?: return@mapNotNull null
                SourceVariant(
                    sourceId = channel.sourceId,
                    sourceName = source.name,
                    streamId = channel.streamId,
                    quality = channel.qualityLabel,
                    qualityRank = channel.qualityRank,
                    language = null,
                    codec = null,
                    streamUrl = channel.streamUrl,
                    userAgent = source.userAgent,
                    cmd = channel.cmd,
                )
            }
            val picked = QualitySelector.pick(sourceVariants, settings.qualityPreferenceOrder.value)
                ?: sourceVariants.firstOrNull()
                ?: return@launch
            val actual = variants.firstOrNull {
                it.sourceId == picked.sourceId && it.streamId == picked.streamId
            } ?: base
            val source = graph.sourceRepository.byId(actual.sourceId)
            val url = graph.catalogRepository.resolvePlaybackUrl(actual, source)
            playerController.play(
                PlayerController.Request(
                    url = url,
                    title = actual.customName?.takeIf(String::isNotBlank) ?: actual.displayName,
                    userAgent = source?.userAgent ?: "",
                    isLive = true,
                ),
                debounce = false,
            )
            settings.recordRecentChannel(actual.id)
        }
    }

    fun zap(delta: Int) {
        val list = channels
        if (list.isEmpty()) return
        val current = settings.lastChannelId
        val index = list.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: 0
        tuneChannel(list[(index + delta).mod(list.size)].id)
    }

    fun playMovie(movie: Movie) {
        scope.launch {
            val source = graph.sourceRepository.byId(movie.sourceId)
            val key = "movie:${movie.canonicalId ?: movie.id}"
            val position = graph.playbackPositions.get(settings.activeProfileId.value, key)
                ?.takeUnless { it.isFinished }?.positionMillis ?: 0L
            val variant = SourceVariant(
                movie.sourceId, source?.name.orEmpty(), movie.streamId, movie.qualityLabel,
                movie.qualityRank, movie.language, movie.codec, movie.streamUrl,
                source?.userAgent.orEmpty(), movie.cmd,
            )
            val url = graph.catalogRepository.resolveVariantPlaybackUrl(variant)
            playerController.play(
                PlayerController.Request(
                    url = url,
                    title = movie.name,
                    userAgent = source?.userAgent.orEmpty(),
                    startPositionMillis = position,
                    isLive = false,
                ),
                debounce = false,
            )
            startVodCheckpointing(key)
        }
    }

    fun playEpisode(episodeId: Long) {
        scope.launch {
            val episode = graph.database.episodes().byId(episodeId) ?: return@launch
            val source = graph.sourceRepository.byId(episode.sourceId)
            val key = "episode:${episode.canonicalEpisodeId ?: episode.id}"
            val position = graph.playbackPositions.get(settings.activeProfileId.value, key)
                ?.takeUnless { it.isFinished }?.positionMillis ?: 0L
            val variant = SourceVariant(
                episode.sourceId, source?.name.orEmpty(), episode.episodeId, episode.qualityLabel,
                episode.qualityRank, episode.language, episode.codec, episode.streamUrl,
                source?.userAgent.orEmpty(), episode.cmd,
            )
            val url = graph.catalogRepository.resolveVariantPlaybackUrl(variant)
            playerController.play(
                PlayerController.Request(
                    url = url,
                    title = episode.title,
                    userAgent = source?.userAgent.orEmpty(),
                    startPositionMillis = position,
                    isLive = false,
                ),
                debounce = false,
            )
            startVodCheckpointing(key)
        }
    }

    suspend fun movies(): List<Movie> = graph.database.movies().all()
    suspend fun series(): List<Series> = graph.database.series().all()
    suspend fun episodes(series: Series) =
        graph.database.episodes().forSeries(series.sourceId, series.seriesId)

    fun setSubtitles(enabled: Boolean) {
        settings.setSubtitlesEnabled(enabled)
        playerController.setSubtitlesEnabled(enabled)
    }

    private fun startVodCheckpointing(key: String) {
        stopVodCheckpointing()
        activeVodKey = key
        resumeJob = scope.launch {
            while (isActive) {
                delay(10_000)
                checkpointVod()
            }
        }
    }

    fun checkpointVod() {
        val key = activeVodKey ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0 } ?: activeVodDuration
        activeVodDuration = duration
        scope.launch {
            if (duration > 0 && position.toDouble() / duration > 0.95) {
                graph.playbackPositions.delete(settings.activeProfileId.value, key)
            } else if (position >= 10_000) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = settings.activeProfileId.value,
                        mediaKey = key,
                        positionMillis = position,
                        durationMillis = duration,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun stopVodCheckpointing() {
        checkpointVod()
        resumeJob?.cancel()
        resumeJob = null
        activeVodKey = null
        activeVodDuration = 0L
    }

    fun release() {
        stopVodCheckpointing()
        playerController.release()
    }
}
