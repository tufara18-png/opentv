package app.tufaratv.ui.nativev4

import android.content.Context
import app.tufaratv.core.ServiceLocator
import app.tufaratv.data.db.TellyChannelRow
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Category
import app.tufaratv.data.model.StreamKind
import app.tufaratv.data.model.PlaybackPosition
import app.tufaratv.data.model.Programme
import app.tufaratv.data.model.Series
import app.tufaratv.data.model.Recording
import app.tufaratv.data.model.RecordingStatus
import app.tufaratv.data.repo.QualitySelector
import app.tufaratv.data.repo.SourceVariant
import app.tufaratv.player.PlayerController
import app.tufaratv.player.SmbDataSource
import app.tufaratv.player.GrowingRecordingDataSource
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
        smbDataSourceFactory = SmbDataSource.Factory(settings),
        growingDataSourceFactory = GrowingRecordingDataSource.Factory(context),
    )
    val player get() = playerController.player

    var channels: List<TellyChannelRow> = emptyList()
        private set
    var programmes: Map<String, List<Programme>> = emptyMap()
        private set
    var groups: List<Category> = emptyList()
        private set
    var currentGroup: Category? = null
        private set

    private val epgSegments = LinkedHashMap<Int, Map<String, List<Programme>>>()
    private val epgLoadingSegments = mutableSetOf<Int>()
    private var epgCacheLoadedAt = 0L

    private var resumeJob: Job? = null
    private var historyJob: Job? = null
    private var activeVodKey: String? = null
    private var activeVodDuration = 0L

    fun loadGuide(onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) {
        scope.launch {
            // Zero-query boot path: start the exact cached stream before touching Room.
            val cachedPlayback = settings.lastLivePlayback()
            if (cachedPlayback != null) {
                playerController.play(
                    PlayerController.Request(
                        url = cachedPlayback.url,
                        title = cachedPlayback.title,
                        userAgent = cachedPlayback.userAgent,
                        isLive = true,
                    ),
                    debounce = false,
                )
            }

            val savedId = settings.lastChannelId.takeIf { it > 0L }
            val startupId = savedId?.takeIf { graph.database.channels().byId(it) != null }
                ?: graph.database.channels().firstVisibleTelly()?.id
            if (cachedPlayback == null && startupId != null) tuneChannelNow(startupId)

            val startupChannel = startupId?.let { graph.database.channels().byId(it) }
            val sourceId = startupChannel?.sourceId
                ?: graph.sourceRepository.enabled().firstOrNull()?.id

            groups = if (sourceId != null) {
                graph.database.categories().allByKind(StreamKind.LIVE)
                    .filter { it.sourceId == sourceId }
            } else {
                emptyList()
            }
            currentGroup = groups.firstOrNull { it.id == startupChannel?.categoryId }
                ?: groups.firstOrNull()

            val rows = when {
                sourceId == null -> emptyList()
                currentGroup != null && settings.deduplicateChannels.value ->
                    graph.database.channels().visibleLogicalSnapshotInCategory(sourceId, currentGroup!!.id)
                currentGroup != null ->
                    graph.database.channels().visibleSnapshotInCategory(sourceId, currentGroup!!.id)
                settings.deduplicateChannels.value ->
                    graph.database.channels().visibleLogicalSnapshotForSource(sourceId)
                else ->
                    graph.database.channels().visibleSnapshotForSource(sourceId)
            }
            channels = rows
            val preferred = startupId?.takeIf { id -> rows.any { it.id == id } }
                ?: rows.firstOrNull()?.id
            val center = rows.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
            resetEpgCache()
            loadEpgSegmentInternal(center)
            onReady(rows, programmes)
        }
    }

    fun refreshGuide(onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) =
        loadGuide(onReady)

    fun loadGroup(group: Category, onReady: (List<TellyChannelRow>, Map<String, List<Programme>>) -> Unit) {
        scope.launch {
            currentGroup = group
            val rows = if (settings.deduplicateChannels.value) {
                graph.database.channels().visibleLogicalSnapshotInCategory(group.sourceId, group.id)
            } else {
                graph.database.channels().visibleSnapshotInCategory(group.sourceId, group.id)
            }
            channels = rows
            resetEpgCache()
            if (rows.isNotEmpty()) loadEpgSegmentInternal(0)
            onReady(rows, programmes)
        }
    }

    private fun resetEpgCache() {
        epgSegments.clear()
        epgLoadingSegments.clear()
        programmes = emptyMap()
        epgCacheLoadedAt = 0L
    }

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

    suspend fun requestForChannel(channelId: Long): PlayerController.Request? {
        val base = graph.database.channels().byId(channelId) ?: return null
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
            ?: return null
        val actual = variants.firstOrNull {
            it.sourceId == picked.sourceId && it.streamId == picked.streamId
        } ?: base
        val source = graph.sourceRepository.byId(actual.sourceId)
        val url = graph.catalogRepository.resolvePlaybackUrl(actual, source)
        return PlayerController.Request(
            url = url,
            title = actual.customName?.takeIf(String::isNotBlank) ?: actual.displayName,
            userAgent = source?.userAgent ?: "",
            isLive = true,
        )
    }

    private suspend fun tuneChannelNow(channelId: Long): Boolean {
        stopVodCheckpointing()
        val request = requestForChannel(channelId) ?: return false
        playerController.play(request, debounce = false)
        settings.recordLastLivePlayback(channelId, request.url, request.title, request.userAgent)

        // Surfing should not pollute history. Commit only if the user stays on this channel.
        historyJob?.cancel()
        historyJob = scope.launch {
            delay(5_000)
            if (settings.lastChannelId == channelId) settings.recordRecentChannel(channelId)
        }
        return true
    }

    fun tuneChannel(channelId: Long) {
        scope.launch { tuneChannelNow(channelId) }
    }

    suspend fun recentChannels(): List<TellyChannelRow> {
        val ids = settings.recentChannelIds.value
        if (ids.isEmpty()) return emptyList()
        val byId = graph.database.channels().rowsByIdsTelly(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    fun resyncCurrent() {
        val id = settings.lastChannelId
        if (id > 0L) tuneChannel(id)
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

    suspend fun vodCategoryLabels(kind: StreamKind): Map<String, String> =
        graph.database.categories().allByKind(kind).associate { category ->
            "${category.sourceId}|${category.id}" to category.name
        }

    fun loadMovies(
        onProgress: (Int) -> Unit = {},
        onUpdate: (List<Movie>, Boolean) -> Unit,
    ) {
        scope.launch {
            val cached = graph.database.movies().all()
            if (cached.isNotEmpty()) {
                onUpdate(cached, false)
                return@launch
            }

            var lastEmitAt = 0L
            val now = System.currentTimeMillis()
            graph.sourceRepository.enabled().forEach { source ->
                graph.catalogRepository.syncVod(
                    source = source,
                    nowUtcMillis = now,
                    includeMovies = true,
                    includeSeries = false,
                    onProgress = { movieCount, _ ->
                        onProgress(movieCount)
                        val at = android.os.SystemClock.elapsedRealtime()
                        if (at - lastEmitAt >= 650L) {
                            lastEmitAt = at
                            scope.launch {
                                val partial = graph.database.movies().all()
                                if (partial.isNotEmpty()) onUpdate(partial, true)
                            }
                        }
                    },
                )
            }
            onUpdate(graph.database.movies().all(), false)
        }
    }

    fun loadSeries(
        onProgress: (Int) -> Unit = {},
        onUpdate: (List<Series>, Boolean) -> Unit,
    ) {
        scope.launch {
            val cached = graph.database.series().all()
            if (cached.isNotEmpty()) {
                onUpdate(cached, false)
                return@launch
            }

            var lastEmitAt = 0L
            val now = System.currentTimeMillis()
            graph.sourceRepository.enabled().forEach { source ->
                graph.catalogRepository.syncVod(
                    source = source,
                    nowUtcMillis = now,
                    includeMovies = false,
                    includeSeries = true,
                    onProgress = { _, seriesCount ->
                        onProgress(seriesCount)
                        val at = android.os.SystemClock.elapsedRealtime()
                        if (at - lastEmitAt >= 650L) {
                            lastEmitAt = at
                            scope.launch {
                                val partial = graph.database.series().all()
                                if (partial.isNotEmpty()) onUpdate(partial, true)
                            }
                        }
                    },
                )
            }
            onUpdate(graph.database.series().all(), false)
        }
    }

    suspend fun episodes(series: Series): List<app.tufaratv.data.model.Episode> {
        val source = graph.sourceRepository.byId(series.sourceId)
        if (source != null) {
            graph.catalogRepository.ensureEpisodes(source, series.seriesId, series.tmdbId)
        }
        return graph.database.episodes().forSeries(series.sourceId, series.seriesId)
    }

    suspend fun searchChannels(query: String, limit: Int = 200): List<TellyChannelRow> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return channels.asSequence()
            .filter {
                (it.customName?.contains(q, ignoreCase = true) == true) ||
                    it.displayName.contains(q, ignoreCase = true)
            }
            .take(limit)
            .toList()
    }

    suspend fun searchMovies(query: String, limit: Int = 120): List<Movie> =
        graph.database.movies().search(query.trim(), limit).first()

    suspend fun searchSeries(query: String, limit: Int = 120): List<Series> =
        graph.database.series().search(query.trim(), limit).first()

    suspend fun recordings(): List<Recording> = graph.recordingRepository.all()

    fun playRecording(recording: Recording) {
        stopVodCheckpointing()
        val locator = when {
            recording.status == RecordingStatus.RECORDING -> "optvrec://${recording.id}"
            recording.filePath.startsWith("/") ->
                android.net.Uri.fromFile(java.io.File(recording.filePath)).toString()
            else -> recording.filePath
        }
        playerController.play(
            PlayerController.Request(
                url = locator,
                title = recording.title,
                userAgent = recording.userAgent,
                isLive = recording.status == RecordingStatus.RECORDING,
            ),
            debounce = false,
        )
    }

    fun openRecording(id: Long, onFound: ((Recording?) -> Unit)? = null) {
        scope.launch {
            val recording = graph.recordingRepository.byId(id)
            if (recording != null) playRecording(recording)
            onFound?.invoke(recording)
        }
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
        historyJob?.cancel()
        historyJob = null
        stopVodCheckpointing()
        playerController.release()
    }

    private companion object {
        const val EPG_SEGMENT_SIZE = 32
        const val EPG_SEGMENT_PREFETCH = 8
        const val EPG_MAX_SEGMENTS = 8
        const val EPG_CACHE_TTL_MS = 90L * 60 * 1000
    }
}
