package com.johncorser.telly.features.playback

import com.johncorser.telly.core.OpenTvStreamResolver
import com.johncorser.telly.core.kv.KeyValueStore
import com.johncorser.telly.features.history.WatchHistory
import com.johncorser.telly.features.player.PlayerEngine
import com.johncorser.telly.features.playlist.db.ChannelDao
import com.johncorser.telly.features.playlist.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns which channel is tuned: restores the last-watched channel on start,
 * pushes streams into the [PlayerEngine], persists the last channel id plus
 * a watch-history event, and zaps with wrap-around.
 */
class TuneController(
    private val engine: PlayerEngine,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    channelDao: ChannelDao,
    private val history: WatchHistory,
    private val policies: TunePolicies = TunePolicies(),
) {
    private val resolveUrl: (String) -> String get() = policies.resolveUrl

    /** The blocked-channel gate; [TuneBlockPrompt] drives its PIN prompt. */
    val gate: BlockGate get() = policies.gate

    /** All visible channels in TiviMate "All channels" order. */
    val channels: StateFlow<List<ChannelEntity>> =
        channelDao.observeVisible().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mutableCurrent = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = mutableCurrent.asStateFlow()

    /** Tunes the last-watched channel (or the first) once channels arrive. */
    fun start() {
        scope.launch {
            val list = channels.first { it.isNotEmpty() }
            if (mutableCurrent.value == null) {
                ChannelZapper.restore(list, store.getLong(LAST_CHANNEL_KEY))?.let { tune(it, allowExternal = false) }
            }
        }
    }

    /**
     * Re-tunes the stored last-watched channel for the guide preview. Uses the
     * cold-start [ChannelZapper.restore] fallback so a stale stored id (row ids
     * are reassigned on every playlist re-import) still tunes the first channel
     * rather than leaving a rebuilt engine idle — the black preview after the
     * engine is released and re-leased, e.g. a Search round trip. A never-set
     * id resumes nothing (fresh install has no channel to restore).
     */
    fun resumeStored() {
        scope.launch {
            val list = channels.first { it.isNotEmpty() }
            val storedId = store.getLong(LAST_CHANNEL_KEY) ?: return@launch
            ChannelZapper.restore(list, storedId)?.let { tune(it, allowExternal = false) }
        }
    }

    /**
     * Tunes [channel], or opens the [gate]'s PIN prompt when it is blocked
     * (the gate fires FIRST — a blocked channel never reaches the external
     * app either, and a blocked channel's archive needs the PIN too; the
     * verified PIN's re-tune replays the intercepted catch-up URL). While
     * "Use external player" is On — per-channel Channel-options override
     * first, the global setting otherwise — a user-initiated tune opens the
     * stream in the external app instead of the internal engine (ux-spec §3.18);
     * with no handler installed it falls back to internal playback. Restore
     * paths (cold start, guide resume) pass [allowExternal] = false so app
     * start never bounces to another app. A non-null [catchupUrl] plays
     * that already-aired programme stream instead of the live one: it stays
     * on the internal engine (the archive URL is not the live URL the
     * external app expects) and no watch-history event fires (catch-up is
     * not a live watch).
     */
    fun tune(
        channel: ChannelEntity,
        allowExternal: Boolean = true,
        catchupUrl: String? = null,
    ) {
        if (gate.intercept(channel)) return pendingCatchup.stash(channel.id, catchupUrl)
        val archiveUrl = pendingCatchup.consume(channel.id, catchupUrl)
        mutableCurrent.value = channel
        suspended = false
        store.putLong(LAST_CHANNEL_KEY, channel.id)
        // Channel-options decoder overrides apply to this tune's prepare.
        engine.applyDecoderOverridesOf(channel)
        if (archiveUrl != null) {
            engine.load(archiveUrl)
            return
        }
        scope.launch { history.record(channel) }
        openTvResolveJob?.cancel()
        val sourceUrl = channel.source.streamUrl
        if (sourceUrl.startsWith("stalker://")) {
            openTvResolveJob =
                scope.launch {
                    val resolved =
                        runCatching { OpenTvStreamResolver.resolve(sourceUrl) }
                            .getOrDefault(sourceUrl)
                    val liveUrl = resolveUrl(resolved)
                    if (!(allowExternal && policies.external.handsOff(channel, liveUrl))) {
                        engine.load(liveUrl)
                    }
                }
        } else {
            val liveUrl = resolveUrl(sourceUrl)
            if (!(allowExternal && policies.external.handsOff(channel, liveUrl))) {
                engine.load(liveUrl)
            }
        }
    }

    /** The gate-intercepted archive tune the PIN unlock should replay. */
    private val pendingCatchup = PendingCatchup()

    private var suspended = false
    private var openTvResolveJob: Job? = null

    /** Activity STOP: stop the stream like the reference does in background. */
    fun suspendPlayback() {
        if (mutableCurrent.value == null) return
        openTvResolveJob?.cancel()
        openTvResolveJob = null
        engine.stop()
        suspended = true
    }

    /** True exactly once after a background stop; the caller recovers. */
    fun consumeSuspension(): Boolean {
        val was = suspended
        suspended = false
        return was
    }

    /** Re-loads the current channel's stream after a background stop. */
    fun retune() {
        val channel = mutableCurrent.value ?: return
        openTvResolveJob?.cancel()
        val sourceUrl = channel.source.streamUrl
        if (sourceUrl.startsWith("stalker://")) {
            openTvResolveJob =
                scope.launch {
                    val resolved =
                        runCatching { OpenTvStreamResolver.resolve(sourceUrl) }
                            .getOrDefault(sourceUrl)
                    engine.load(resolveUrl(resolved))
                }
        } else {
            engine.load(resolveUrl(sourceUrl))
        }
    }

    /** Tunes the channel [delta] steps away (wraps); false when impossible or PIN-gated. */
    fun zap(delta: Int): Boolean {
        val next = ChannelZapper.neighbour(channels.value, mutableCurrent.value, delta) ?: return false
        tune(next)
        return mutableCurrent.value?.id == next.id
    }

    fun byId(channelId: Long): ChannelEntity? = channels.value.firstOrNull { it.id == channelId }

    /** Retunes to the next channel when [channel] is about to disappear. */
    fun zapAwayFrom(channel: ChannelEntity) {
        if (channel.id != mutableCurrent.value?.id) return
        ChannelZapper
            .neighbour(channels.value, channel, +1)
            ?.takeIf { it.id != channel.id }
            ?.let(::tune)
    }

    companion object {
        const val LAST_CHANNEL_KEY = "lastChannelId"
    }
}
