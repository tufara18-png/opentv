/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.player

import kotlinx.coroutines.flow.Flow

/** What a screen actually asks to be played — engine-agnostic, unlike [PlayerController.Request]
 *  which is Media3-flavoured (it still is; [Media3Engine] translates one into the other). */
data class EngineRequest(
    val url: String,
    val title: String,
    val userAgent: String,
    val startPositionMillis: Long = 0L,
    val isLive: Boolean = true,
)

/** Mirrors [PlayerController.State], minus the Media3-specific `canRetry` flag — [terminal] is
 *  what [PlayerCoordinator] actually needs: has this engine given up on this request for good. */
sealed interface EngineState {
    data object Idle : EngineState
    data class Buffering(val title: String) : EngineState
    data class Playing(val title: String) : EngineState
    data class Error(val title: String, val message: String, val terminal: Boolean) : EngineState
}

/**
 * What [PlayerCoordinator] drives instead of touching a concrete player directly — [Media3Engine]
 * today, a `VlcEngine` once one exists. The seam that makes engine choice (the player's "Lecteur"
 * panel/`AppSettings.playerEngine`) and failover between engines possible without
 * `PlayerCoordinator` ever importing ExoPlayer or libVLC types.
 */
interface PlaybackEngine {
    val state: Flow<EngineState>
    fun play(request: EngineRequest)
    fun stop()
}
