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
import com.johncorser.telly.features.playback.BlockSession
import com.johncorser.telly.features.playback.ClockStyle
import com.johncorser.telly.features.playback.PanelTimeouts
import com.johncorser.telly.features.playback.PlaybackDeps
import com.johncorser.telly.features.playback.PlaybackHooks
import com.johncorser.telly.features.playback.PlaybackSources
import com.johncorser.telly.features.playback.PlaybackTime
import com.johncorser.telly.features.playback.PlayerKeymap
import com.johncorser.telly.features.player.skip.SkipSteps
import com.johncorser.telly.features.playlist.db.ChannelDao
import com.johncorser.telly.features.recording.recordingCenter
import com.johncorser.telly.features.history.WatchHistory
import kotlinx.coroutines.flow.map

/**
 * Public seam used by host apps that want Telly's UI while keeping their own
 * catalogue and EPG persistence.
 *
 * This lives inside the Telly Android-library module on purpose: it can reuse
 * Telly's internal engine builder instead of duplicating Media3 tuning.
 */
fun ServiceLocator.bridgedPlaybackDeps(
    context: Context,
    channelDao: ChannelDao,
    programDao: ProgramDao,
    hooks: PlaybackHooks = PlaybackHooks(),
): PlaybackDeps {
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
