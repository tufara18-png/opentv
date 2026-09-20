/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

/** Which player engine to use — the third independent axis alongside Source and Quality. `AUTO`
 *  tries [android's Media3/ExoPlayer][EngineKind.MEDIA3] first and falls back per
 *  [FailoverStrategy]; an explicit choice skips the dance and plays only that engine. */
enum class PlayerEngineChoice { AUTO, MEDIA3, VLC }

/** Which concrete engine is behind one [PlaybackAttempt]. Kept distinct from
 *  [PlayerEngineChoice]: that is what the user picked (possibly `AUTO`), this is what a specific
 *  rung of the ladder actually plays through. */
enum class EngineKind { MEDIA3, VLC }

/**
 * How `Auto` walks the failover ladder when a source/quality combo's own retries are exhausted.
 * User-configurable ([app.tufaratv.core.AppSettings.failoverStrategy]) because the trade-off is
 * real: trying every dead URL through a second engine before moving on is more thorough but costs
 * real seconds per dead source.
 */
enum class FailoverStrategy {
    /** Exhausts every source×quality combo on Media3 first; only once none of them work does it
     *  retry the top-ranked combos through VLC as a last resort. The default — a string of dead
     *  Media3 sources should not each cost a second engine's worth of connect-and-fail time. */
    FAST_AUTO,

    /** Tries VLC on the same URL immediately after Media3 fails it, before moving to the next
     *  combo — catches a stream that plays fine but trips only Media3's codec support, at every
     *  quality/provider, not just the best-ranked one. Slower per dead source. */
    MAX_COMPATIBILITY,
}

/** One rung of the failover ladder: a specific source/quality variant, through a specific engine. */
data class PlaybackAttempt(val variant: SourceVariant, val engine: EngineKind)

/**
 * Builds the ordered list of things to try for a title, and — separately — the plain
 * source/quality ranking the Source and Quality panels list. Pure: no DB, no player, no
 * coroutines. `PlayerCoordinator` (in `player/`, which owns the actual engines) walks the list
 * this returns; it never re-derives the ordering itself.
 */
object PlayerLadder {

    /**
     * Every [variants] ranked best-first: grouped by source (pinned [sourceOverride] first, else
     * best-available-quality-first), and within the active group, [QualitySelector.ranked] —
     * pinned [qualityOverride] first when it names something still available in that group, since
     * an override was a choice within *that* source, not a demand every other source must also
     * satisfy on failover.
     */
    fun rankedVariants(
        variants: List<SourceVariant>,
        qualityOrder: List<String>,
        sourceOverride: String? = null,
        qualityOverride: String? = null,
    ): List<SourceVariant> {
        val groups = SourceSelector.groupBySource(variants)
        if (groups.isEmpty()) return emptyList()

        val orderedGroups = if (sourceOverride != null) {
            val pinned = groups.firstOrNull { it.sourceId.toString() == sourceOverride }
            if (pinned != null) {
                listOf(pinned) + groups.filterNot { it === pinned }.sortedByDescending { it.bestQualityRank }
            } else {
                groups.sortedByDescending { it.bestQualityRank }
            }
        } else {
            groups.sortedByDescending { it.bestQualityRank }
        }

        return orderedGroups.flatMapIndexed { index, group ->
            val ranked = QualitySelector.ranked(group.variants, qualityOrder)
            if (index == 0 && qualityOverride != null) {
                val preferred = ranked.firstOrNull { it.quality.equals(qualityOverride, ignoreCase = true) }
                if (preferred != null) listOf(preferred) + ranked.filterNot { it === preferred } else ranked
            } else {
                ranked
            }
        }
    }

    /**
     * The full attempt sequence [PlayerCoordinator] walks on failure, per [engineChoice] and
     * [failoverStrategy]. [vlcAvailable] is false until a `VlcEngine` actually exists — every
     * attempt this builds is then `MEDIA3`, so the ladder degrades gracefully rather than
     * producing attempts nothing can execute.
     */
    fun buildAttempts(
        variants: List<SourceVariant>,
        qualityOrder: List<String>,
        sourceOverride: String?,
        qualityOverride: String?,
        engineChoice: PlayerEngineChoice,
        failoverStrategy: FailoverStrategy,
        vlcAvailable: Boolean,
    ): List<PlaybackAttempt> {
        val ranked = rankedVariants(variants, qualityOrder, sourceOverride, qualityOverride)
        if (ranked.isEmpty()) return emptyList()

        return when (engineChoice) {
            PlayerEngineChoice.MEDIA3 -> ranked.map { PlaybackAttempt(it, EngineKind.MEDIA3) }
            PlayerEngineChoice.VLC ->
                if (vlcAvailable) ranked.map { PlaybackAttempt(it, EngineKind.VLC) }
                else ranked.map { PlaybackAttempt(it, EngineKind.MEDIA3) }
            PlayerEngineChoice.AUTO -> when (failoverStrategy) {
                FailoverStrategy.MAX_COMPATIBILITY -> ranked.flatMap { v ->
                    if (vlcAvailable) {
                        listOf(PlaybackAttempt(v, EngineKind.MEDIA3), PlaybackAttempt(v, EngineKind.VLC))
                    } else {
                        listOf(PlaybackAttempt(v, EngineKind.MEDIA3))
                    }
                }
                FailoverStrategy.FAST_AUTO -> {
                    val media3 = ranked.map { PlaybackAttempt(it, EngineKind.MEDIA3) }
                    val vlc = if (vlcAvailable) ranked.take(2).map { PlaybackAttempt(it, EngineKind.VLC) } else emptyList()
                    media3 + vlc
                }
            }
        }
    }
}
