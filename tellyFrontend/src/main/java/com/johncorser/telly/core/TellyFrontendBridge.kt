package com.johncorser.telly.core

import android.content.Context
import android.util.Xml
import com.johncorser.telly.core.settings.ParentalControls
import com.johncorser.telly.core.settings.TellySettings
import com.johncorser.telly.features.catchup.CatchupDeps
import com.johncorser.telly.features.catchup.CatchupToggles
import com.johncorser.telly.features.epg.EpgOffsets
import com.johncorser.telly.features.epg.EpgRepository
import com.johncorser.telly.features.epg.db.ProgramDao
import com.johncorser.telly.features.groups.RoomCustomGroupStore
import com.johncorser.telly.features.history.WatchHistory
import com.johncorser.telly.features.multiview.MultiviewDeps
import com.johncorser.telly.features.multiview.MultiviewTune
import com.johncorser.telly.features.playback.BlockSession
import com.johncorser.telly.features.playback.ClockStyle
import com.johncorser.telly.features.playback.PanelTimeouts
import com.johncorser.telly.features.playback.PlaybackDeps
import com.johncorser.telly.features.playback.PlaybackHooks
import com.johncorser.telly.features.playback.PlaybackSources
import com.johncorser.telly.features.playback.PlaybackTime
import com.johncorser.telly.features.playback.PlayerKeymap
import com.johncorser.telly.features.player.PlayerEngineFactory
import com.johncorser.telly.features.recording.recordingCenter
import com.johncorser.telly.features.reminders.GuideReminders
import com.johncorser.telly.features.reminders.ReminderChannelNames
import com.johncorser.telly.features.reminders.ReminderEngine
import com.johncorser.telly.features.reminders.ReminderPopupController
import com.johncorser.telly.features.reminders.ReminderSettingsFeed
import com.johncorser.telly.features.reminders.ReminderStore
import com.johncorser.telly.features.reminders.RemindersHub
import com.johncorser.telly.features.player.skip.SkipSteps
import com.johncorser.telly.features.playlist.M3uPlaylist
import com.johncorser.telly.features.playlist.PlaylistRepository
import com.johncorser.telly.features.playlist.StoredPlaylist
import com.johncorser.telly.features.playlist.db.ChannelDao
import com.johncorser.telly.features.search.SearchDeps
import com.johncorser.telly.features.search.SearchHistory
import com.johncorser.telly.features.search.SearchHooks
import com.johncorser.telly.features.search.SearchRepository
import com.johncorser.telly.features.search.db.SearchDao
import com.johncorser.telly.features.settings.BlockedChannels
import com.johncorser.telly.features.settings.PlaylistUpdater
import com.johncorser.telly.features.settings.SettingsActions
import com.johncorser.telly.features.settings.SettingsBackupManager
import com.johncorser.telly.features.settings.SettingsGraph
import com.johncorser.telly.features.settings.SettingsStores
import com.johncorser.telly.features.vod.VodDeps
import com.johncorser.telly.features.vod.db.VodItemDao
import com.johncorser.telly.features.vod.db.VodPositionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone

fun ServiceLocator.bridgedPlaybackDeps(
    context: Context,
    channelDao: ChannelDao,
    programDao: ProgramDao,
    hooks: PlaybackHooks = PlaybackHooks(),
    resolveStream: suspend (String) -> String = { it },
): PlaybackDeps {
    OpenTvStreamResolver.resolve = resolveStream
    val settings = settingsRepository(context)
    val epg =
        EpgRepository(
            programDao = programDao,
            newParser = { Xml.newPullParser() },
            storeDescriptions = { settings.get(TellySettings.EPG_STORE_DESCRIPTIONS) },
            offsets = channelDao.observeEpgOffsets().map(EpgOffsets::ofMinutes),
        )
    return PlaybackDeps(
        sources =
            PlaybackSources(
                channelDao = channelDao,
                epgRepository = epg,
                history = WatchHistory(database(context).watchHistoryDao(), clock),
                myList = myListStore(context),
            ),
        keyValueStore = keyValueStore(context),
        engineFactory = { tunedEngine(context) },
        time =
            PlaybackTime(
                clock = clock,
                style = ClockStyle(h24 = { ClockStyle.is24Raw(settings.get(TellySettings.CLOCK_FORMAT)) }),
                panelTimeouts = {
                    PanelTimeouts.forSeconds(settings.get(TellySettings.PLAYER_PANEL_TIMEOUT_SEC))
                },
            ),
        parental = ParentalControls(settings),
        hooks =
            hooks.copy(
                playerKeymap = { PlayerKeymap.from(settings) },
                recording = recordingCenter(context),
                parental = ParentalControls(settings),
                blockSession = BlockSession(),
                catchup =
                    CatchupDeps(
                        session = com.johncorser.telly.features.catchup.CatchupSession(),
                        toggles = CatchupToggles { setting -> settings.get(setting) },
                        skipSteps = { SkipSteps.of(settings) },
                    ),
                resolveUrl = proxyResolve(settings),
                customGroups = RoomCustomGroupStore(database(context).customGroupDao),
            ),
    )
}

fun ServiceLocator.bridgedRemindersHub(
    context: Context,
    channelDao: ChannelDao,
): RemindersHub {
    val db = database(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = ReminderStore(db.reminderDao())
    val channels = channelDao.observeVisible()
    val names = ReminderChannelNames(channels)
    val zone = TimeZone.getDefault()
    val popup = ReminderPopupController(keyValueStore(context), scope, zone)
    val prefs = settingsRepository(context)
    ReminderEngine(
        store = store,
        clock = clock,
        leadMinutes = { prefs.get(TellySettings.REMINDER_LEAD_MINUTES) },
        onDue = { reminder ->
            scope.launch { popup.show(reminder, names.nameOf(reminder.channelId)) }
        },
    ).startIn(scope, PlaybackTime.minuteBoundaryTicks(clock))

    return RemindersHub(
        database = db,
        store = store,
        guide = GuideReminders(store, scope),
        settingsFeed = ReminderSettingsFeed(store, channels, zone),
        popup = popup,
        scope = scope,
    )
}

fun ServiceLocator.bridgedSearchDeps(
    context: Context,
    searchDao: SearchDao,
    channelDao: ChannelDao,
    playback: PlaybackDeps,
): SearchDeps {
    val settings = settingsRepository(context)
    return SearchDeps(
        repository = SearchRepository(searchDao, channelDao, playback.sources.epgRepository),
        history =
            SearchHistory(
                store = searchHistoryStore(context),
                saveEnabled = { settings.get(TellySettings.SEARCH_SAVE_HISTORY) },
            ),
        lastChannelStore = keyValueStore(context),
        clock = clock,
        style = playback.time.style,
        hooks = SearchHooks(myList = playback.myList),
    )
}

fun ServiceLocator.bridgedMultiviewDeps(
    context: Context,
    channelDao: ChannelDao,
    playback: PlaybackDeps,
    resolveStream: suspend (String) -> String = { it },
): MultiviewDeps {
    val settings = settingsRepository(context)
    return MultiviewDeps(
        channelDao = channelDao,
        epgRepository = playback.sources.epgRepository,
        store = keyValueStore(context),
        time = playback.time,
        tune =
            MultiviewTune(
                engines = PlayerEngineFactory { tunedEngine(context, handleAudioFocus = false) },
                resolveUrl = proxyResolve(settings),
            ),
    )
}

fun ServiceLocator.bridgedVodDeps(
    context: Context,
    items: VodItemDao,
    positions: VodPositionDao,
    resolveStream: suspend (String) -> String = { it },
): VodDeps =
    VodDeps(
        items = items,
        positions = positions,
        engineFactory = { tunedEngine(context) },
        rememberPosition = { true },
        clock = clock,
    )

fun ServiceLocator.bridgedSettingsGraph(
    context: Context,
    playlists: PlaylistRepository,
    channelDao: ChannelDao,
    positions: VodPositionDao,
    refreshPlaylist: suspend (String) -> Boolean = { false },
    refreshEpg: suspend () -> Int = { 0 },
): SettingsGraph {
    val settings = settingsRepository(context)
    return SettingsGraph(
        settings = settings,
        playlists = playlists,
        parental = ParentalControls(settings),
        actions =
            SettingsActions(
                updater =
                    PlaylistUpdater(
                        fetchPlaylist = { "#EXTM3U\n" },
                        repository = RefreshPlaylistRepository(playlists, refreshPlaylist),
                    ),
                updateEpgNow = refreshEpg,
                backup = SettingsBackupManager(settings, playlists, context.filesDir),
                clearVodPositions = positions::clearAll,
            ),
        versionName =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown"),
        stores =
            SettingsStores(
                epgSources = epgSourceStore(context),
                blocked = BlockedChannels(channelDao),
                searchHistory =
                    SearchHistory(
                        store = searchHistoryStore(context),
                        saveEnabled = { settings.get(TellySettings.SEARCH_SAVE_HISTORY) },
                    ),
            ),
    )
}


private class RefreshPlaylistRepository(
    private val delegate: PlaylistRepository,
    private val refresh: suspend (String) -> Boolean,
) : PlaylistRepository {
    override val playlists: Flow<List<StoredPlaylist>> get() = delegate.playlists

    override suspend fun add(sourceUrl: String, playlist: M3uPlaylist, name: String?) {
        check(refresh(sourceUrl)) { "OpenTV source refresh failed" }
    }

    override suspend fun rename(sourceUrl: String, name: String) =
        delegate.rename(sourceUrl, name)

    override suspend fun changeUrl(oldUrl: String, newUrl: String): Boolean =
        delegate.changeUrl(oldUrl, newUrl)

    override suspend fun delete(sourceUrl: String) =
        delegate.delete(sourceUrl)
}
