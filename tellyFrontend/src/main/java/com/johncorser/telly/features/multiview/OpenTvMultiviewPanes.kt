package com.johncorser.telly.features.multiview

import com.johncorser.telly.core.OpenTvStreamResolver
import com.johncorser.telly.features.player.PlayerEngine
import com.johncorser.telly.features.player.PlayerEnginePool
import com.johncorser.telly.features.playlist.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One multiview pane: a channel playing on its own pooled engine. */
data class MultiviewPane(
    val id: Int,
    val channel: ChannelEntity,
    val engine: PlayerEngine,
)

/**
 * The pane list and its audio ownership: the FOCUSED pane is the only
 * unmuted engine (N players cannot all own audio), adding focuses the new
 * pane, removing collapses the grid onto a neighbour, and the ViewModel's
 * PIN-gated CH+/- zap retunes the focused pane via [change]. All
 * uncapturable (premium in the reference) — telly design decisions, see
 * the decisions log.
 */
class MultiviewPanes(
    private val pool: PlayerEnginePool,
    /** Stream-URL resolution (UDP-proxy rewrite), same seam as live tunes. */
    private val resolveUrl: (String) -> String = { it },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolveJobs = mutableMapOf<Int, Job>()
    private val mutablePanes = MutableStateFlow<List<MultiviewPane>>(emptyList())
    private val mutableFocusedId = MutableStateFlow(0)
    private var nextId = 1

    val panes: StateFlow<List<MultiviewPane>> = mutablePanes.asStateFlow()
    val focusedId: StateFlow<Int> = mutableFocusedId.asStateFlow()

    val focused: MultiviewPane? get() = mutablePanes.value.firstOrNull { it.id == mutableFocusedId.value }

    /** Adds a pane playing [channel] (capped at [MultiviewGrid.MAX_PANES]) and focuses it. */
    fun add(channel: ChannelEntity): Boolean {
        if (mutablePanes.value.size >= MultiviewGrid.MAX_PANES) return false
        val pane = MultiviewPane(nextId++, channel, pool.acquire())
        mutablePanes.update { it + pane }
        load(pane)
        focus(pane.id)
        return true
    }

    /** Change channel: the focused pane retunes in place. */
    fun change(channel: ChannelEntity) {
        val pane = focused ?: return
        val updated = pane.copy(channel = channel)
        mutablePanes.update { list -> list.map { if (it.id == pane.id) updated else it } }
        load(updated)
    }

    /** Removes the focused pane (never the last); focus falls to a neighbour. */
    fun removeFocused(): Boolean {
        val list = mutablePanes.value
        val pane = focused
        if (pane == null || list.size <= 1) return false
        val remaining = list - pane
        resolveJobs.remove(pane.id)?.cancel()
        pool.release(pane.engine)
        mutablePanes.value = remaining
        focus(remaining[list.indexOf(pane).coerceAtMost(remaining.lastIndex)].id)
        return true
    }

    /** D-pad focus between panes; the focused pane owns audio, others mute. */
    fun focus(paneId: Int) {
        if (mutablePanes.value.none { it.id == paneId }) return
        mutableFocusedId.value = paneId
        mutablePanes.value.forEach { it.engine.setMuted(it.id != paneId) }
    }

    private fun load(pane: MultiviewPane) {
        resolveJobs.remove(pane.id)?.cancel()
        resolveJobs[pane.id] =
            scope.launch {
                val sourceUrl = pane.channel.source.streamUrl
                val resolved =
                    if (sourceUrl.startsWith("stalker://")) {
                        runCatching { OpenTvStreamResolver.resolve(sourceUrl) }.getOrDefault(sourceUrl)
                    } else {
                        sourceUrl
                    }
                if (focused?.id == pane.id || mutablePanes.value.any { it.id == pane.id }) {
                    pane.engine.load(resolveUrl(resolved))
                }
            }
    }

    fun releaseAll() {
        resolveJobs.values.forEach(Job::cancel)
        resolveJobs.clear()
        scope.cancel()
        pool.releaseAll()
    }
}
