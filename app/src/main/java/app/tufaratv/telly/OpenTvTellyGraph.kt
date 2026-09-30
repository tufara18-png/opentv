package app.tufaratv.telly

import android.content.Context
import android.util.Log
import android.os.Process
import app.tufaratv.core.ServiceLocator as OpenTvServiceLocator
import app.tufaratv.data.model.Source
import app.tufaratv.data.model.SourceKind
import app.tufaratv.data.repo.SourceVariant
import com.johncorser.telly.core.ServiceLocator as TellyServiceLocator
import com.johncorser.telly.core.bridgedMultiviewDeps
import com.johncorser.telly.core.bridgedPlaybackDeps
import com.johncorser.telly.core.bridgedRemindersHub
import com.johncorser.telly.core.bridgedSearchDeps
import com.johncorser.telly.core.bridgedSettingsGraph
import com.johncorser.telly.core.bridgedVodDeps
import com.johncorser.telly.core.guideDeps
import com.johncorser.telly.core.playlistFetchUserAgentFor
import com.johncorser.telly.features.guide.GuideDeps
import com.johncorser.telly.features.multiview.MultiviewDeps
import com.johncorser.telly.features.onboarding.OpenTvProviderBridge
import com.johncorser.telly.features.onboarding.OpenTvProviderKind
import com.johncorser.telly.features.playback.PlaybackDeps
import com.johncorser.telly.features.playlist.M3uFetcher
import com.johncorser.telly.features.recording.RecordingDeps
import com.johncorser.telly.features.recording.recordingDeps
import com.johncorser.telly.features.reminders.RemindersHub
import com.johncorser.telly.features.search.SearchDeps
import com.johncorser.telly.features.settings.SettingsGraph
import com.johncorser.telly.features.vod.VodDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Production dependency graph for the Telly Android-TV frontend.
 *
 * OpenTV owns provider/catalogue persistence. Telly consumes adapters for
 * channels, EPG, search and VOD while retaining its own UI-only preferences.
 */
class OpenTvTellyGraph(context: Context) {
    private val appContext = context.applicationContext
    private val openTv = OpenTvServiceLocator.get(appContext)
    private val syncDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }.apply { name = "tufaratv-provider-sync" }
        }.asCoroutineDispatcher()

    private val syncScope = CoroutineScope(SupervisorJob() + syncDispatcher)

    init {
        OpenTvProviderBridge.install { draft ->
            Log.i(
                TAG,
                "Provider submit kind=${draft.kind} host=${hostOf(draft.url)}",
            )
            runCatching {
                val kind =
                    when (draft.kind) {
                        OpenTvProviderKind.XTREAM -> SourceKind.XTREAM
                        OpenTvProviderKind.STALKER -> SourceKind.STALKER
                    }
                val source =
                    Source(
                        name =
                            draft.name.ifBlank {
                                if (kind == SourceKind.XTREAM) "Xtream Codes" else "Stalker Portal"
                            },
                        kind = kind,
                        url = draft.url,
                        username = draft.username.takeIf { it.isNotBlank() },
                        password = draft.password.takeIf { it.isNotBlank() },
                        macAddress = draft.macAddress.takeIf { it.isNotBlank() },
                    )

                val tested =
                    withTimeoutOrNull(PROVIDER_TEST_TIMEOUT_MS) {
                        openTv.sourceRepository.test(source)
                    } ?: error("Connexion au fournisseur expirée après 20 secondes. Vérifiez l’adresse, le port et les identifiants.")
                tested.getOrThrow()

                val id = openTv.sourceRepository.save(source)
                val saved = openTv.sourceRepository.byId(id)
                    ?: error("Provider was not saved")

                Log.i(
                    TAG,
                    "Provider authenticated kind=${saved.kind} sourceId=${saved.id}; onboarding complete, syncing in background",
                )

                syncScope.launch {
                    val live =
                        withTimeoutOrNull(LIVE_SYNC_TIMEOUT_MS) {
                            openTv.catalogRepository.syncLive(saved, System.currentTimeMillis())
                        }
                    when (live) {
                        null ->
                            Log.e(
                                TAG,
                                "Background live sync timed out kind=${saved.kind} sourceId=${saved.id}",
                            )
                        is app.tufaratv.data.repo.CatalogRepository.SyncResult.Failed ->
                            Log.e(
                                TAG,
                                "Background live sync failed kind=${saved.kind} host=${hostOf(saved.url)} reason=${live.reason}",
                                live.cause,
                            )
                        is app.tufaratv.data.repo.CatalogRepository.SyncResult.Success -> {
                            Log.i(
                                TAG,
                                "Background live sync complete kind=${saved.kind} sourceId=${saved.id} channels=${live.channelCount}",
                            )

                            // Give the TV UI exclusive breathing room after the first live catalogue
                            // becomes usable. EPG follows shortly after; VOD/canonical matching is
                            // deliberately deferred so it cannot compete with the guide for CPU/Room.
                            delay(EPG_BACKGROUND_DELAY_MS)
                            runCatching {
                                openTv.epgRepository.syncAll(
                                    nowUtcMillis = System.currentTimeMillis(),
                                    force = true,
                                )
                            }.onFailure {
                                Log.w(TAG, "Background EPG sync failed for source ${saved.id}", it)
                            }

                            delay(VOD_BACKGROUND_DELAY_MS)
                            runCatching {
                                openTv.catalogRepository.syncVod(
                                    saved,
                                    System.currentTimeMillis(),
                                )
                            }.onFailure {
                                Log.w(TAG, "Deferred VOD sync failed for source ${saved.id}", it)
                            }
                        }
                    }
                }

                val firstChannelsReady =
                    withTimeoutOrNull(FIRST_CHANNEL_TIMEOUT_MS) {
                        while (openTv.database.channels().countForSource(saved.id) == 0) {
                            delay(100)
                        }
                        true
                    } ?: false

                if (!firstChannelsReady) {
                    error("La connexion fonctionne, mais aucune chaîne n’a encore été reçue.")
                }

                Log.i(
                    TAG,
                    "Provider first channels ready sourceId=${saved.id}; leaving onboarding",
                )
                Unit
            }.onFailure {
                Log.e(
                    TAG,
                    "Provider submit failed kind=${draft.kind} host=${hostOf(draft.url)}: ${it.message}",
                    it,
                )
            }
        }
    }

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
            addSource = { sourceUrl, playlist, name ->
                val id =
                    openTv.sourceRepository.save(
                        Source(
                            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Playlist",
                            kind = SourceKind.M3U,
                            url = sourceUrl,
                            epgUrl = playlist.epgUrl,
                        ),
                    )
                val source = openTv.sourceRepository.byId(id)
                    ?: error("OpenTV source was not created")
                when (val sync = openTv.catalogRepository.syncLive(source, System.currentTimeMillis())) {
                    is app.tufaratv.data.repo.CatalogRepository.SyncResult.Success -> Unit
                    is app.tufaratv.data.repo.CatalogRepository.SyncResult.Failed -> {
                        Log.e(
                            TAG,
                            "M3U catalogue sync failed host=${hostOf(source.url)} reason=${sync.reason}",
                            sync.cause,
                        )
                        openTv.database.sources().delete(id)
                        error(sync.reason)
                    }
                }
                Log.i(TAG, "M3U live sync complete sourceId=${source.id}")
                if (!playlist.epgUrl.isNullOrBlank()) {
                    syncScope.launch {
                        delay(EPG_BACKGROUND_DELAY_MS)
                        runCatching {
                            openTv.epgRepository.syncAll(
                                nowUtcMillis = System.currentTimeMillis(),
                                force = true,
                            )
                        }.onFailure {
                            Log.w(TAG, "Background M3U EPG sync failed for source ${source.id}", it)
                        }
                    }
                }
            },
            changeSourceUrl = { sourceId, newUrl ->
                val source = openTv.sourceRepository.byId(sourceId)
                if (source == null) {
                    false
                } else {
                    openTv.sourceRepository.save(source.copy(url = newUrl))
                    val updated = openTv.sourceRepository.byId(sourceId)
                    updated != null &&
                        openTv.catalogRepository.sync(updated, System.currentTimeMillis()) is
                        app.tufaratv.data.repo.CatalogRepository.SyncResult.Success
                }
            },
            deleteSource = { sourceId ->
                openTv.database.sources().delete(sourceId)
            },
        )

    private val configuredPlaylistUserAgent =
        TellyServiceLocator.playlistFetchUserAgentFor(appContext)

    suspend fun fetchPlaylist(url: String): String {
        Log.i(TAG, "M3U fetch start host=${hostOf(url)}")

        val userAgents =
            listOfNotNull(
                configuredPlaylistUserAgent(url)?.takeIf { it.isNotBlank() },
                Source.DEFAULT_USER_AGENT,
                "VLC/3.0.21 LibVLC/3.0.21",
                "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36",
            ).distinct()

        var lastFailure: Throwable? = null
        for ((attempt, userAgent) in userAgents.withIndex()) {
            val result =
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val request =
                            Request.Builder()
                                .url(url)
                                .header("User-Agent", userAgent)
                                .header("Accept", "*/*")
                                .header("Accept-Encoding", "identity")
                                .build()

                        openTv.httpClient.newCall(request).execute().use { response ->
                            Log.i(
                                TAG,
                                "M3U HTTP attempt=${attempt + 1} host=${hostOf(url)} status=${response.code} ua=${userAgent.substringBefore(' ')}",
                            )
                            if (!response.isSuccessful) {
                                error("HTTP ${response.code} while downloading playlist")
                            }
                            response.body?.string()
                                ?: error("Le serveur a renvoyé une playlist vide.")
                        }
                    }
                }

            result.onSuccess { body ->
                Log.i(TAG, "M3U fetch success host=${hostOf(url)} bytes=${body.length}")
                return body
            }
            lastFailure = result.exceptionOrNull()
        }

        val failure =
            lastFailure ?: IllegalStateException("Impossible de télécharger la playlist.")
        Log.e(TAG, "M3U fetch failed host=${hostOf(url)}: ${failure.message}", failure)
        throw failure
    }

    suspend fun hasSources(): Boolean = openTv.sourceRepository.enabled().isNotEmpty()

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
            resolveStream = ::resolveStreamUrl,
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
            resolveStream = ::resolveStreamUrl,
        )

    val vod: VodDeps =
        TellyServiceLocator.bridgedVodDeps(
            context = appContext,
            items = vodItems,
            positions = vodPositions,
            resolveStream = ::resolveStreamUrl,
        )

    val reminders: RemindersHub =
        TellyServiceLocator.bridgedRemindersHub(
            context = appContext,
            channelDao = channels,
        )

    val recording: RecordingDeps =
        TellyServiceLocator.recordingDeps(appContext)

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

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url.substringBefore('?') }
            .getOrDefault(url.substringBefore('?'))

    private companion object {
        const val TAG = "OpenTvTellyGraph"
        const val PROVIDER_TEST_TIMEOUT_MS = 20_000L
        const val LIVE_SYNC_TIMEOUT_MS = 120_000L
        const val FIRST_CHANNEL_TIMEOUT_MS = 30_000L
        const val EPG_BACKGROUND_DELAY_MS = 5_000L
        const val VOD_BACKGROUND_DELAY_MS = 90_000L
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
