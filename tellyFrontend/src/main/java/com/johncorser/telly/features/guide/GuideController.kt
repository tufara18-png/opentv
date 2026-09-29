package com.johncorser.telly.features.guide

import com.johncorser.telly.features.catchup.GuideCatchup
import com.johncorser.telly.features.groups.GroupToolLauncher
import com.johncorser.telly.features.history.WatchHistory
import com.johncorser.telly.features.mylist.MyListMenu
import com.johncorser.telly.features.playback.PlaybackEnv
import com.johncorser.telly.features.playback.PlaybackLifecycle
import com.johncorser.telly.features.playback.TuneBlockPrompt
import com.johncorser.telly.features.playback.gatedTuner
import com.johncorser.telly.features.playback.groupTools
import com.johncorser.telly.features.playlist.db.ChannelEntity
import com.johncorser.telly.features.recording.RecordingMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The TV-guide screen's state machine: grid rows over the visible window,
 * focus/scroll, the active layer, and the preview tuner. A plain class —
 * everything injected, unit-tested on the JVM. "Now" ticks per minute and
 * re-seeds on foreground resume ([GuideNow]); no wall-clock reads in logic.
 */
class GuideController(
    env: PlaybackEnv,
    history: WatchHistory,
    private val pastDays: () -> Int,
    scope: CoroutineScope,
    private val callbacks: GuideCallbacks,
    seams: GuideSeams = GuideSeams(),
) {
    /** 12/24-hour rendering (+ zone) for the header clock and timeline ticks. */
    val clockStyle = env.time.style

    /** Restores the picked group across a fullscreen→back guide rebuild. */
    private val groupMemory = GuideGroupMemory(env.store)

    /** OK on a playable past cell hands the archive to fullscreen playback. */
    private val catchup = GuideCatchup(env.hooks.catchup.session, env.time.clock, callbacks.onFullscreen)

    /** Settings → Remote control → TV guide key remaps, read per key press. */
    private val keymap = seams.keymap

    /** Minute-ticked "now" (header clock, now-line); origin stays anchored. */
    private val ticker = GuideNow(env.time.clock, scope, env.time.minuteTicks)
    val now: StateFlow<Long> = ticker.now
    val originMs = GuideGeometry.halfHourFloor(now.value, clockStyle.zone)

    private val tuner = gatedTuner(env, history, scope, external = callbacks.external)

    /** The blocked-channel tune gate's prompt (guide OK / preview restore). */
    val blockPrompt = TuneBlockPrompt(tuner)

    /** Exit-confirm state + the persisted PIN input method (screen chrome). */
    val chrome = GuideChrome(env, scope)

    /** Background stop + foreground re-seed/re-tune (round7 resume P2). */
    val lifecycle = PlaybackLifecycle(tuner, onForegrounded = ticker::reseed, recover = tuner::retune)
    private val focusEngine =
        GuideFocusEngine(originMs, { GuideWindowMath.scrollFloorDp(pastDays()) }, visibleRows = seams.visibleRows)

    /** The shared factory behind the sheet's six group/bulk tool rows. */
    private val groupTools = env.groupTools(scope)
    private val toolLauncher = GroupToolLauncher(groupTools) { groupMemory.value }
    private val sources = GuideRowsSources(tuner.channels, groupMemory.group, groupTools.groups)
    private val feed =
        guideRowsFeed(
            env = env,
            sources = sources,
            scrollX = focusEngine.scrollX,
            firstVisibleRow = focusEngine.firstVisibleRow,
            visibleRows = seams.visibleRows,
            originMs = originMs,
            scope = scope,
        )

    val rows: StateFlow<List<GuideRow>> = feed.rows

    val groups: StateFlow<List<String>> = feed.groups
    val selectedGroup: StateFlow<String> = groupMemory.group
    val focus: StateFlow<GuideFocus?> = focusEngine.focus
    val scrollX: StateFlow<Float> = focusEngine.scrollX
    val firstVisibleRow: StateFlow<Int> = focusEngine.firstVisibleRow
    val preview: StateFlow<ChannelEntity?> = tuner.current

    val hint: StateFlow<Boolean> = GuideHint(env.store).startIn(scope)

    val info: StateFlow<GuideInfoData?> = GuideInfoBuilder.feed(rows, focusEngine.focus, now, clockStyle, scope)

    /** My-list toggle state: the dropdown/sheet labels flip on its keys. */
    val myList = MyListMenu(seams.myList, env.time.clock, scope)

    private val sheetMyList = guideMyListHost(myList, focus, { groupMemory.value }, callbacks)

    /** Layers + the long-OK row context sheet (catalogue §3 38-42). */
    val menu =
        GuideMenuController(
            channelActions = guideSheetActions(env, scope, sheetMyList, tuner::zapAwayFrom, toolLauncher),
            focusedRow = ::focusedRow,
            info = { info.value },
            callbacks = callbacks,
            focusMemory = GuideFocusMemory(focusEngine, seams.visibleRows) { rows.value },
            recording = { recordingMenu },
        )

    /** The sheet/cell Record rows act through this (null while no DVR wired). */
    val recordingMenu: RecordingMenu? =
        env.hooks.recording?.let { center ->
            RecordingMenu(center, scope, env.time.clock) { prompt -> menu.onRecordingPrompt(prompt) }
        }

    val layer: StateFlow<GuideLayer> = menu.layer

    /** Transition half of the state machine: commands over engine + layers. */
    private val commands =
        GuideCommands(focusEngine, menu, { rows.value }, pastDays, seams.visibleRows, ::activate)

    init {
        menu.remind.reminders = seams.reminders
        menu.onPlayChannel = ::play
        scope.launch { rows.collect { focusEngine.ensureFocus(it, now.value) } }
        groupMemory.arm(scope, tuner.channels, groupTools.groups)
        // The guide is reached from playback (BACK / the TV-guide card), where
        // the last channel keeps playing in the preview window AND its row
        // takes the initial focus (the rebuild would otherwise drop focus to
        // the top of the list); a cold start with "Turn on last channel on
        // app start" OFF instead lands here untuned — the preview stays dark
        // until OK tunes a cell and focus starts at the first row.
        if (seams.resumePreview()) {
            tuner.resumeStored()
            GuideFocusResume(focusEngine, env.store, seams.visibleRows).arm(scope, rows) { now.value }
        }
    }

    /** Routes a key through the layer map; true = consumed. */
    fun onKey(key: GuideKey): Boolean =
        GuideKeyPolicy.commandFor(layer.value, key, keymap())?.also(commands::execute) != null

    /** OK on a group filters the grid and renumbers from 1 (capture 74). */
    fun selectGroup(group: String) {
        if (groupMemory.select(group)) {
            focusEngine.reset()
            focusEngine.ensureFocus(rows.value, now.value)
        }
        menu.reset()
    }

    private fun focusedRow(): GuideRow? = focus.value?.let { rows.value.getOrNull(it.rowIndex) }

    private fun activate() {
        val focused = focus.value ?: return
        val row = focusedRow() ?: return
        when (val action = GuideActivation.activate(row, focused.cell, now.value)) {
            is GuideAction.PlayChannel -> play(action.channel)
            is GuideAction.PlayCatchup -> catchup.play(action.channel, action.cell)
        }
    }

    /** Regular OK / cell-menu Play channel: tune, then jump to fullscreen. */
    private fun play(channel: ChannelEntity) {
        tuner.tune(channel)
        callbacks.onFullscreen()
    }
}
