/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

/** One provider's [SourceVariant]s of a title, grouped — what the "Source" panel lists as a
 *  single row (`King`, `Render`, …), each with its own qualities behind it. */
data class SourceGroup(val sourceId: Long, val sourceName: String, val variants: List<SourceVariant>) {
    /** What Auto would play from this group if it were chosen — the group's own best quality,
     *  used only to rank groups against each other, never to skip [QualitySelector]. */
    val bestQualityRank: Int get() = variants.maxOf { it.qualityRank }
}

/**
 * Picks *which provider* to play a title from — kept genuinely independent of [QualitySelector]
 * (which picks the quality within whichever source this returns), per the two separate "Source" /
 * "Quality" panels the player shows, each with its own `Auto`.
 *
 * Pure and unit-tested: no DB, no player, just a list in and one group out.
 */
object SourceSelector {

    fun groupBySource(variants: List<SourceVariant>): List<SourceGroup> =
        variants.groupBy { it.sourceId }
            .map { (id, vs) -> SourceGroup(id, vs.first().sourceName, vs) }

    /**
     * @param override the user's remembered source, as `sourceId.toString()`
     *   (`PreferredVariant.sourceKey`) — wins when that source is still among [variants]. `null`/
     *   not-found falls through to Auto: the source whose best available quality ranks highest,
     *   a reasonable default and nothing more (the actual played quality is still [QualitySelector]'s
     *   call, scoped to whichever group this returns).
     */
    fun pick(variants: List<SourceVariant>, override: String? = null): SourceGroup? {
        val groups = groupBySource(variants)
        if (groups.isEmpty()) return null
        if (override != null) {
            groups.firstOrNull { it.sourceId.toString() == override }?.let { return it }
        }
        return groups.maxByOrNull { it.bestQualityRank }
    }
}
