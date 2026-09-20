/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wraps an existing [PlayerController] as a [PlaybackEngine] — a thin translation layer, not a
 * reimplementation. `PlayerController`'s own debounce/retry/backoff tuning (see its doc comment)
 * is untouched; this only maps its [PlayerController.State] into the engine-agnostic
 * [EngineState] `PlayerCoordinator` understands, and always plays with `debounce = false` — a
 * coordinator-driven failover attempt is a deliberate selection, never a channel-surf keystroke.
 */
class Media3Engine(private val controller: PlayerController) : PlaybackEngine {

    override val state: Flow<EngineState> = controller.state.map { s ->
        when (s) {
            is PlayerController.State.Idle -> EngineState.Idle
            is PlayerController.State.Buffering -> EngineState.Buffering(s.title)
            is PlayerController.State.Playing -> EngineState.Playing(s.title)
            is PlayerController.State.Error ->
                EngineState.Error(s.title, s.message, terminal = !s.canRetry)
        }
    }

    override fun play(request: EngineRequest) {
        controller.play(
            PlayerController.Request(
                url = request.url,
                title = request.title,
                userAgent = request.userAgent,
                startPositionMillis = request.startPositionMillis,
                isLive = request.isLive,
            ),
            debounce = false,
        )
    }

    override fun stop() = controller.stop()
}
