/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.player

import app.tufaratv.data.repo.EngineKind
import app.tufaratv.data.repo.PlaybackAttempt
import app.tufaratv.data.repo.SourceVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sits between a screen and the player engines, and is what actually walks the failover ladder
 * [app.tufaratv.data.repo.PlayerLadder] built:
 *
 * ```
 * VodPlayerScreen / PlayerScreen
 *         |
 *   PlayerCoordinator  --uses--> SourceSelector, QualitySelector (via PlayerLadder)
 *         |
 *   PlaybackEngine
 *    +-- Media3Engine   (wraps the existing PlayerController)
 *    +-- VlcEngine       (not yet built — see PlayerLadder's `vlcAvailable` parameter)
 * ```
 *
 * A screen calls [start] once with the attempt list [app.tufaratv.data.repo.PlayerLadder.buildAttempts]
 * produced; from there [PlayerCoordinator] owns walking it on failure. It never re-ranks or
 * re-decides anything itself — all of that lives in the pure `PlayerLadder`/`SourceSelector`/
 * `QualitySelector`, so this class is purely the stateful "which rung am I on, and what does the
 * live engine say" orchestration.
 */
class PlayerCoordinator(
    private val scope: CoroutineScope,
    private val media3: PlaybackEngine,
    /** Null until a `VlcEngine` exists — any attempt built with [EngineKind.VLC] while this is
     *  null is skipped (see [PlayerLadder.buildAttempts]'s `vlcAvailable`, which should already
     *  prevent that from happening, but this is the last line of defence). */
    private val vlc: PlaybackEngine? = null,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _activeAttempt = MutableStateFlow<PlaybackAttempt?>(null)
    /** The variant + engine currently playing (or being tried) — what the Source/Quality/Lecteur
     *  panels highlight as the active choice on each axis. */
    val activeAttempt: StateFlow<PlaybackAttempt?> = _activeAttempt.asStateFlow()

    private var attempts: List<PlaybackAttempt> = emptyList()
    private var attemptIndex = 0
    private var title = ""
    private var isLive = true
    private var startPositionMillis = 0L
    private var userAgentFor: (SourceVariant) -> String = { "" }
    private var resolveUrl: suspend (SourceVariant) -> String = { it.streamUrl }
    private var collectJob: Job? = null
    private var playJob: Job? = null

    /**
     * Starts (or restarts, e.g. after a manual Source/Quality/Lecteur pick rebuilt [attempts])
     * playback from the top of the ladder. [startPositionMillis] is preserved across every
     * subsequent failover jump within this run — a jump is not a fresh play, so VOD resume
     * position must not reset to zero partway through. [resolveUrl] turns a variant into the URL
     * to actually feed the engine — most variants already carry a playable
     * [SourceVariant.streamUrl] and don't need it, but a Stalker variant's `cmd` is short-lived
     * and must be resolved right before this specific play, not when the variant list was built.
     */
    fun start(
        attempts: List<PlaybackAttempt>,
        title: String,
        isLive: Boolean,
        startPositionMillis: Long = 0L,
        userAgentFor: (SourceVariant) -> String,
        resolveUrl: suspend (SourceVariant) -> String = { it.streamUrl },
    ) {
        this.attempts = attempts
        this.attemptIndex = 0
        this.title = title
        this.isLive = isLive
        this.startPositionMillis = startPositionMillis
        this.userAgentFor = userAgentFor
        this.resolveUrl = resolveUrl
        playCurrent()
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        playJob?.cancel()
        playJob = null
        media3.stop()
        vlc?.stop()
        attempts = emptyList()
        _activeAttempt.value = null
        _state.value = EngineState.Idle
    }

    private fun playCurrent() {
        val attempt = attempts.getOrNull(attemptIndex)
        if (attempt == null) {
            _activeAttempt.value = null
            _state.value = EngineState.Error(title, NO_PLAYABLE_SOURCE, terminal = true)
            return
        }
        val engine = if (attempt.engine == EngineKind.VLC) vlc else media3
        if (engine == null) {
            // Ladder called for VLC but none exists yet — skip straight to the next rung.
            attemptIndex++
            playCurrent()
            return
        }

        _activeAttempt.value = attempt

        // See the class doc: a StateFlow re-subscribed here can immediately replay the PREVIOUS
        // attempt's terminal error before this attempt's own Buffering has landed. Only a
        // terminal error seen AFTER this attempt has genuinely made progress (Buffering/Playing)
        // counts as this attempt failing — anything before that is stale, not a fresh failure.
        var sawProgress = false
        collectJob?.cancel()
        collectJob = scope.launch {
            engine.state.collect { s ->
                _state.value = s
                when (s) {
                    is EngineState.Buffering, is EngineState.Playing -> sawProgress = true
                    is EngineState.Error -> if (s.terminal && sawProgress) {
                        attemptIndex++
                        playCurrent()
                    }
                    is EngineState.Idle -> {}
                }
            }
        }
        playJob?.cancel()
        playJob = scope.launch {
            val url = runCatching { resolveUrl(attempt.variant) }.getOrDefault(attempt.variant.streamUrl)
            engine.play(
                EngineRequest(
                    url = url,
                    title = title,
                    userAgent = userAgentFor(attempt.variant),
                    startPositionMillis = startPositionMillis,
                    isLive = isLive,
                ),
            )
        }
    }

    private companion object {
        const val NO_PLAYABLE_SOURCE = "Aucune source lisible n’a fonctionné pour ce titre."
    }
}
