package app.tufaratv.telly

import android.content.Context
import app.tufaratv.core.ServiceLocator as OpenTvServiceLocator
import app.tufaratv.data.repo.SourceVariant
import com.johncorser.telly.core.ServiceLocator as TellyServiceLocator
import com.johncorser.telly.core.bridgedMultiviewDeps
import com.johncorser.telly.core.bridgedPlaybackDeps
import com.johncorser.telly.core.bridgedRemindersHub
import com.johncorser.telly.core.bridgedSearchDeps
import com.johncorser.telly.core.bridgedSettingsGraph
import com.johncorser.telly.core.bridgedVodDeps
import com.johncorser.telly.core.guideDeps
import com.johncorser.telly.core.tunedEngine
import com.johncorser.telly.features.guide.GuideDeps
import com.johncorser.telly.features.multiview.MultiviewDeps
import com.johncorser.telly.features.playback.PlaybackDeps
import com.johncorser.telly.features.recording.RecordingDeps
import com.johncorser.telly.features.recording.recordingDeps
import com.johncorser.telly.features.reminders.RemindersHub
import com.johncorser.telly.features.search.SearchDeps
import com.johncorser.telly.features.settings.SettingsGraph
import com.johncorser.telly.features.vod.VodDeps

/**
 * Production dependency graph for the Telly Android-TV frontend.
 *
 * OpenTV owns provider/catalogue persistence. Telly consumes adapters for
 * channels, EPG, search and VOD while retaining its own UI-only preferences.
 */
class OpenTvTellyGraph(context: Context) {
    private val appContext = context.applicationContext
    private val openTv = OpenTvServiceLocator.get(appContext)

    val channels =
        OpenTvChannelDaoAdapter(
            channels = openTv.database.channels(),
            categories = openTv.database.categories(),
        )

    val programmes =
        OpenTvProgramDaoAdapter(
            programmes = openTv.database.programmes(),
        )

    val playlists =
        OpenTvPlaylistRepository(
            sources = openTv.database.sources(),
            channels = openTv.database.channels(),
        )

    val searchDao =
        OpenTvSearchDaoAdapter(
            channels = openTv.database.channels(),
            programmes = openTv.database.programmes(),
            categories = openTv.database.categories(),
        )

    val vodItems =
        OpenTvVodItemDaoAdapter(
            movies = openTv.database.movies(),
            categories = openTv.database.categories(),
        )

    val vodPositions =
        OpenTvVodPositionDaoAdapter(
            positions = openTv.database.positions(),
            settings = openTv.settings,
        )

    val playback: PlaybackDeps =
        TellyServiceLocator.bridgedPlaybackDeps(
            context = appContext,
            channelDao = channels,
            programDao = programmes,
            engineFactory = { resolvingEngine() },
        )

    val guide: GuideDeps =
        TellyServiceLocator.guideDeps(
            context = appContext,
            playback = playback,
        )

    val search: SearchDeps =
        TellyServiceLocator.bridgedSearchDeps(
            context = appContext,
            searchDao = searchDao,
            channelDao = channels,
            playback = playback,
        )

    val multiview: MultiviewDeps =
        TellyServiceLocator.bridgedMultiviewDeps(
            context = appContext,
            channelDao = channels,
            playback = playback,
            engineFactory = { resolvingEngine(handleAudioFocus = false) },
        )

    val vod: VodDeps =
        TellyServiceLocator.bridgedVodDeps(
            context = appContext,
            items = vodItems,
            positions = vodPositions,
            engineFactory = { resolvingEngine() },
        )

    val reminders: RemindersHub =
        TellyServiceLocator.bridgedRemindersHub(
            context = appContext,
            channelDao = channels,
        )

    val recording: RecordingDeps =
        TellyServiceLocator.recordingDeps(appContext)

    private fun resolvingEngine(handleAudioFocus: Boolean = true) =
        OpenTvResolvingPlayerEngine(
            delegate = TellyServiceLocator.tunedEngine(appContext, handleAudioFocus = handleAudioFocus),
            resolve = ::resolveStreamUrl,
        )

    private suspend fun resolveStreamUrl(url: String): String {
        if (!url.startsWith("stalker://")) return url

        openTv.database.channels().byStreamUrl(url)?.let { channel ->
            val source = openTv.sourceRepository.byId(channel.sourceId)
            return openTv.catalogRepository.resolvePlaybackUrl(channel, source)
        }

        openTv.database.movies().byStreamUrl(url)?.let { movie ->
            val source = openTv.sourceRepository.byId(movie.sourceId) ?: return movie.streamUrl
            return openTv.catalogRepository.resolveVariantPlaybackUrl(
                SourceVariant(
                    sourceId = movie.sourceId,
                    sourceName = source.name,
                    streamId = movie.streamId,
                    quality = movie.qualityLabel,
                    qualityRank = movie.qualityRank,
                    language = movie.language,
                    codec = movie.codec,
                    streamUrl = movie.streamUrl,
                    userAgent = source.userAgent,
                    cmd = movie.cmd,
                ),
            )
        }

        openTv.database.episodes().byStreamUrl(url)?.let { episode ->
            val source = openTv.sourceRepository.byId(episode.sourceId) ?: return episode.streamUrl
            return openTv.catalogRepository.resolveVariantPlaybackUrl(
                SourceVariant(
                    sourceId = episode.sourceId,
                    sourceName = source.name,
                    streamId = episode.episodeId,
                    quality = episode.qualityLabel,
                    qualityRank = episode.qualityRank,
                    language = episode.language,
                    codec = episode.codec,
                    streamUrl = episode.streamUrl,
                    userAgent = source.userAgent,
                    cmd = episode.cmd,
                ),
            )
        }

        return url
    }

    val settings: SettingsGraph =
        TellyServiceLocator.bridgedSettingsGraph(
            context = appContext,
            playlists = playlists,
            channelDao = channels,
            positions = vodPositions,
            refreshPlaylist = { sourceKey ->
                val sourceId = sourceKey.substringAfterLast('/').toLongOrNull()
                val source = sourceId?.let { openTv.sourceRepository.byId(it) }
                if (source == null) {
                    false
                } else {
                    openTv.catalogRepository.sync(source, System.currentTimeMillis()) is
                        app.tufaratv.data.repo.CatalogRepository.SyncResult.Success
                }
            },
            refreshEpg = {
                openTv.epgRepository.syncAll(
                    nowUtcMillis = System.currentTimeMillis(),
                    force = true,
                ).feedsSucceeded
            },
        )
}
