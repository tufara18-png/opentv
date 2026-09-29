package com.johncorser.telly.features.vod

import com.johncorser.telly.core.OpenTvStreamResolver
import com.johncorser.telly.features.player.Media3PlayerEngine
import com.johncorser.telly.features.player.PlayerState
import com.johncorser.telly.features.vod.db.VodItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * VOD playback (ux-spec §VOD): loads the item, offers Resume/Start-over
 * when a stored position sits inside the resume band, and persists the
 * position on pause/exit and every ~10 s. Transport keys live in
 * [VodPlayerControls]. A plain class, JVM-tested.
 */
class VodPlaybackViewModel(
    deps: VodDeps,
    private val itemKey: String,
    val engine: Media3PlayerEngine,
    private val scope: CoroutineScope,
    private val onExit: () -> Unit,
) {
    private val items = deps.items
    private val store = VodPositionStore(deps.positions, deps.rememberPosition, deps.clock)

    val controls: VodPlayerControls =
        VodPlayerControls(
            engine = engine,
            transport = VodTransportVisibility(scope),
            persistOnPause = { scope.launch { persist() } },
        )

    private val mutableItem = MutableStateFlow<VodItemEntity?>(null)
    val item: StateFlow<VodItemEntity?> = mutableItem.asStateFlow()

    private val mutableStage = MutableStateFlow<VodStage>(VodStage.Loading)
    val stage: StateFlow<VodStage> = mutableStage.asStateFlow()

    private var watching = false
    private var exited = false
    private var resolveJob: kotlinx.coroutines.Job? = null

    /** Loads the item; a vanished key just leaves the route. */
    fun start() {
        scope.launch {
            val loaded = items.byKey(itemKey) ?: return@launch leave()
            mutableItem.value = loaded
            val stored = store.read(itemKey)
            if (stored != null && VodResumePolicy.offerResume(stored.positionMs, stored.durationMs)) {
                mutableStage.value = VodStage.ResumePrompt(stored.positionMs)
            } else {
                play(0)
            }
        }
    }

    fun resumeStored() = play((mutableStage.value as? VodStage.ResumePrompt)?.positionMs ?: 0)

    fun startOver() = play(0)

    /** BACK: persist the position (playing only), then pop the route. */
    fun exit() {
        if (mutableStage.value != VodStage.Playing) return leave()
        scope.launch {
            persist()
            leave()
        }
    }

    fun release() {
        resolveJob?.cancel()
        engine.release()
    }

    private fun play(fromMs: Long) {
        val loaded = mutableItem.value ?: return
        mutableStage.value = VodStage.Playing
        resolveJob?.cancel()
        resolveJob =
            scope.launch {
                val sourceUrl = loaded.streamUrl
                val resolved =
                    if (sourceUrl.startsWith("stalker://")) {
                        runCatching { OpenTvStreamResolver.resolve(sourceUrl) }.getOrDefault(sourceUrl)
                    } else {
                        sourceUrl
                    }
                if (exited) return@launch
                engine.load(resolved)
                if (fromMs > 0) engine.player.seekTo(fromMs)
                controls.begin()
                watch()
            }
    }

    /** Sampling + persistence loops and the media-end watcher. */
    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            while (true) {
                controls.sample()
                delay(SAMPLE_MS)
            }
        }
        scope.launch {
            while (true) {
                delay(PERSIST_MS)
                persist()
            }
        }
        // Media end: the position row clears as finished and the route exits.
        scope.launch {
            engine.state.collect { state ->
                if (state == PlayerState.Ended) {
                    store.finish(itemKey, controls.durationMs())
                    leave()
                }
            }
        }
    }

    private suspend fun persist() {
        store.save(itemKey, controls.positionMs(), controls.durationMs())
    }

    private fun leave() {
        if (exited) return
        exited = true
        onExit()
    }

    companion object {
        /** LEFT/RIGHT seek step. */
        const val SEEK_STEP_MS = 10_000L

        /** RW/FF jump step. */
        const val JUMP_STEP_MS = 30_000L
        private const val SAMPLE_MS = 500L
        private const val PERSIST_MS = 10_000L
    }
}
