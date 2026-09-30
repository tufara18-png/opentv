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

    private val epgSegments = LinkedHashMap<Int, Map<String, List<Programme>>>()
    private val epgLoadingSegments = mutableSetOf<Int>()
    private var epgCacheLoadedAt = 0L

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
            channels = rows
            val preferred = settings.lastChannelId.takeIf { id -> rows.any { it.id == id } }
                ?: rows.firstOrNull()?.id
            val center = rows.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
            loadEpgSegmentInternal(center)
            onReady(rows, programmes)
            preferred?.let(::tuneChannel)
        }
    }

    fun refreshGuide(onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) =
        loadGuide(onReady)

    fun loadEpgSegment(centerRow: Int, onReady: (Map<String, List<Programme>>) -> Unit) {
        val segment = centerRow.coerceAtLeast(0) / EPG_SEGMENT_SIZE
        if (epgSegments.containsKey(segment)) {
            onReady(programmes)
            return
        }
        if (!epgLoadingSegments.add(segment)) return
        scope.launch {
            try {
                loadEpgSegmentInternal(centerRow)
                onReady(programmes)
            } finally {
                epgLoadingSegments.remove(segment)
            }
        }
    }

    private suspend fun loadEpgSegmentInternal(centerRow: Int) {
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - epgCacheLoadedAt > EPG_CACHE_TTL_MS) {
            epgSegments.clear()
            epgLoadingSegments.clear()
            programmes = emptyMap()
            epgCacheLoadedAt = now
        }

        val segment = centerRow.coerceAtLeast(0) / EPG_SEGMENT_SIZE
        if (epgSegments.containsKey(segment)) return

        val first = (segment * EPG_SEGMENT_SIZE - EPG_SEGMENT_PREFETCH).coerceAtLeast(0)
        val last = (first + EPG_SEGMENT_SIZE + EPG_SEGMENT_PREFETCH * 2).coerceAtMost(channels.size)
        val ids = channels.subList(first, last)
            .flatMap { listOfNotNull(it.epgOverrideId, it.epgChannelId, it.matchedEpgId) }
            .distinct()

        val loaded = if (ids.isEmpty()) {
            emptyList()
        } else {
            graph.database.programmes()
                .observeWindowForChannels(ids, now - 7_200_000L, now + 28_800_000L)
                .first()
        }
        epgSegments[segment] = loaded.groupBy { it.epgChannelId }
        while (epgSegments.size > EPG_MAX_SEGMENTS) {
            epgSegments.remove(epgSegments.entries.first().key)
        }
        programmes = buildMap {
            epgSegments.values.forEach { segmentMap ->
                segmentMap.forEach { (key, value) -> put(key, value) }
            }
        }
    }

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
            settings.recordRecentChannel(base.id)
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

    suspend fun movies(onProgress: ((Int) -> Unit)? = null): List<Movie> {
        if (graph.database.movies().count() == 0) {
            val now = System.currentTimeMillis()
            graph.sourceRepository.enabled().forEach { source ->
                graph.catalogRepository.syncVod(
                    source = source,
                    nowUtcMillis = now,
                    includeMovies = true,
                    includeSeries = false,
                    onProgress = { movies, _ -> onProgress?.invoke(movies) },
                )
            }
        }
        return graph.database.movies().all()
    }

    suspend fun series(onProgress: ((Int) -> Unit)? = null): List<Series> {
        if (graph.database.series().count() == 0) {
            val now = System.currentTimeMillis()
            graph.sourceRepository.enabled().forEach { source ->
                graph.catalogRepository.syncVod(
                    source = source,
                    nowUtcMillis = now,
                    includeMovies = false,
                    includeSeries = true,
                    onProgress = { _, series -> onProgress?.invoke(series) },
                )
            }
        }
        return graph.database.series().all()
    }

    suspend fun episodes(series: Series): List<app.tufaratv.data.model.Episode> {
        val source = graph.sourceRepository.byId(series.sourceId)
        if (source != null) {
            graph.catalogRepository.ensureEpisodes(source, series.seriesId, series.tmdbId)
        }
        return graph.database.episodes().forSeries(series.sourceId, series.seriesId)
    }

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

    private companion object {
        const val EPG_SEGMENT_SIZE = 160
        const val EPG_SEGMENT_PREFETCH = 40
        const val EPG_MAX_SEGMENTS = 6
        const val EPG_CACHE_TTL_MS = 90L * 60 * 1000
    }
}
