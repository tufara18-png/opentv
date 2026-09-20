/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

/**
 * Picks which quality to play among the [SourceVariant]s of one already-chosen source (see
 * [SourceSelector] for picking the source itself — kept genuinely separate, not blended into one
 * decision, per the two independent "Source" / "Quality" panels the player shows).
 *
 * Pure and unit-tested: no DB, no player, just a list in and one variant out.
 */
object QualitySelector {

    /**
     * @param variants candidates, already scoped to one source — typically
     *   `CanonicalMovieDao.variantsFor(canonicalId)`/etc. filtered to one `sourceId`.
     * @param preferenceOrder the user's ranked quality labels, most-preferred first — see
     *   `AppSettings.qualityPreferenceOrder`. A label not listed at all ranks alongside
     *   `"inconnu"`, matching the "unrecognised labels bucket to inconnu" rule.
     * @param override an exact (case-insensitive) quality label to prefer when it is still among
     *   [variants] — the user's remembered pick (`PreferredVariant.qualityKey`). `null`/not-found
     *   falls through to [preferenceOrder]. Never throws on an empty [variants] list; returns null.
     */
    fun pick(variants: List<SourceVariant>, preferenceOrder: List<String>, override: String? = null): SourceVariant? {
        if (variants.isEmpty()) return null
        if (override != null) {
            variants.firstOrNull { it.quality.equals(override, ignoreCase = true) }?.let { return it }
        }
        return ranked(variants, preferenceOrder).firstOrNull()
    }

    /** Every [variants] ordered best-to-worst by [preferenceOrder] — what [pick] and the Quality
     *  panel's listing, and [app.tufaratv.data.repo.PlayerLadder]'s failover order, all use. */
    fun ranked(variants: List<SourceVariant>, preferenceOrder: List<String>): List<SourceVariant> =
        variants.sortedBy { rank(it, preferenceOrder) }

    /** Lower sorts first. Variants that land in the same bucket (both "inconnu", or both an
     *  identical listed label) keep their relative input order — callers pass variants already
     *  ordered best-quality-first ([app.tufaratv.data.db.CanonicalMovieDao.variantsFor] does),
     *  so a numeric tie-break naturally falls out of that without a second sort key here. */
    private fun rank(variant: SourceVariant, preferenceOrder: List<String>): Int {
        val label = variant.quality
        val idx = if (label.isNotBlank()) {
            preferenceOrder.indexOfFirst { it.equals(label, ignoreCase = true) }
        } else {
            -1
        }
        if (idx >= 0) return idx
        val inconnuIdx = preferenceOrder.indexOfFirst { it.equals("inconnu", ignoreCase = true) }
        return if (inconnuIdx >= 0) inconnuIdx else preferenceOrder.size
    }
}
