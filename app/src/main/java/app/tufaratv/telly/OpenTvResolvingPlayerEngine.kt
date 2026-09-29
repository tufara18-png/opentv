package app.tufaratv.telly

import com.johncorser.telly.features.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Telly's player surface/engine while letting OpenTV resolve short-lived provider URLs.
 *
 * OpenTV stores Stalker/Ministra streams as stable stalker:// markers and mints the real URL
 * only when playback starts. Resolving here avoids pre-resolving the whole catalogue and keeps
 * Telly's UI/navigation/player code unchanged.
 */
class OpenTvResolvingPlayerEngine(
    private val delegate: PlayerEngine,
    private val resolve: suspend (String) -> String,
) : PlayerEngine {
    override val state get() = delegate.state
    override val video get() = delegate.video
    override val paused get() = delegate.paused
    override val tracks get() = delegate.tracks
    override val decoders get() = delegate.decoders

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null

    override fun load(streamUrl: String) {
        resolveJob?.cancel()
        if (!streamUrl.startsWith(STALKER_SCHEME)) {
            delegate.load(streamUrl)
            return
        }

        delegate.stop()
        resolveJob = scope.launch {
            val playable = runCatching { resolve(streamUrl) }.getOrDefault(streamUrl)
            delegate.load(playable)
        }
    }

    override fun stop() {
        resolveJob?.cancel()
        resolveJob = null
        delegate.stop()
    }

    override fun release() {
        resolveJob?.cancel()
        resolveJob = null
        scope.cancel()
        delegate.release()
    }

    override fun setMuted(muted: Boolean) = delegate.setMuted(muted)
    override fun pause() = delegate.pause()
    override fun resume() = delegate.resume()
    override fun positionMs(): Long = delegate.positionMs()
    override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)

    private companion object {
        const val STALKER_SCHEME = "stalker://"
    }
}
