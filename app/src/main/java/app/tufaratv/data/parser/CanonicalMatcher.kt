/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

import app.tufaratv.data.model.CanonicalContent
import app.tufaratv.data.model.CanonicalMatchKind

/**
 * Decides how a newly-synced `Movie`/`Series`/`Episode` row (a "candidate") links into the
 * canonical, cross-provider catalog — the piece that turns four providers' separate copies of
 * the same film into one [CanonicalContent] with several source variants behind it.
 *
 * Pure and unit-tested, like [M3uParser]/[VodTitleCleaner]: [decide] takes the candidate plus
 * whatever existing canonical rows the caller already looked up, and returns a decision — it
 * never queries or writes the database itself. `CatalogRepository` does the actual lookups
 * ([app.tufaratv.data.db.CanonicalMovieDao.findByTmdbId]/`findAllByTitleKey`) and writes
 * ([app.tufaratv.data.db.CanonicalMovieDao.insert]/`update`) around this, at sync time, with no
 * network calls — TMDB enrichment is a separate, later, async pass (see
 * [app.tufaratv.data.work.CanonicalEnrichmentWorker]) that never blocks a row from being usable.
 *
 * Confidence is tracked, not assumed: every decision carries a [CanonicalMatchKind] recording how
 * sure the link is, so a `TITLE_YEAR`/`TITLE_ONLY` guess can be revisited once real TMDB evidence
 * arrives, instead of being indistinguishable from a confirmed match forever.
 */
object CanonicalMatcher {

    /** A variant row's identifying fields, ahead of it being written to `Movie`/`Series`/`Episode`. */
    data class Candidate(
        /** The provider's raw, uncleaned name — `VodTitleCleaner.clean` runs on this inside [keyOf]. */
        val rawName: String,
        /** Year from a structured field (Xtream's `year`), when the provider supplies one — checked
         *  ahead of a year embedded in [rawName], since a structured field is the more trustworthy. */
        val explicitYear: Int? = null,
        /** A provider-supplied TMDB id, when present (Xtream `get_vod_info`/`get_series_info`, or
         *  written back by TMDB enrichment on a later sync). Coerced to String upstream (panels vary). */
        val tmdbId: String? = null,
    )

    /** The normalized identity derived from a candidate — computed once, reused by both [decide]
     *  and the caller (to populate a new [CanonicalContent]'s `title`/`titleKey`/`year`). */
    data class Key(val titleKey: String, val displayTitle: String, val year: Int?)

    enum class Action { LINK_EXISTING, CREATE_NEW }

    data class Decision(
        val action: Action,
        /** Set only when [action] is [Action.LINK_EXISTING]. */
        val existingId: Long? = null,
        val matchKind: CanonicalMatchKind,
        val key: Key,
    )


    /**
     * Normalizes a candidate's raw name into a match key and clean display title. Reuses the same
     * year-stripped [ChannelNameNormalizer.normalize] grouping [CatalogRepository.collapseMovieVariants]
     * already relies on for the match key (so this introduces no second key format), and
     * [VodTitleCleaner.clean] for the display title, which keeps a trailing `(year)` and real casing
     * that the group key deliberately throws away.
     */
    fun keyOf(rawName: String, explicitYear: Int? = null): Key {
        val displayTitle = VodTitleCleaner.clean(rawName)
        val inferredYear = VodTitleCleaner.inferReleaseYear(rawName)
        val year = explicitYear ?: inferredYear

        // Strip only the release year we actually inferred. Never remove every 19xx/20xx token:
        // numbers such as "2001" and "2049" can be part of the work's real title.
        val titleForKey = if (year != null) {
            displayTitle
                .replace(Regex("""\s*[\[(]\s*__YEAR__\s*[\])]\s*$""".replace("__YEAR__", year.toString())), "")
                .replace(Regex("""\s+__YEAR__\s*$""".replace("__YEAR__", year.toString())), "")
                .trim()
                .ifBlank { displayTitle }
        } else {
            displayTitle
        }
        val titleKey = ChannelNameNormalizer.normalize(titleForKey).groupKey
        return Key(titleKey = titleKey, displayTitle = displayTitle, year = year)
    }

    /**
     * @param existingByTmdbId looked up by [Candidate.tmdbId], when non-null — an exact match.
     * @param existingByTitleKey every existing canonical entry (of the right kind) sharing this
     *   candidate's [Key.titleKey], regardless of year — [decide] applies the year-compatibility
     *   rule itself rather than have the caller pre-filter it.
     */
    fun decide(
        candidate: Candidate,
        existingByTmdbId: CanonicalContent?,
        existingByTitleKey: List<CanonicalContent>,
    ): Decision {
        val key = keyOf(candidate.rawName, candidate.explicitYear)

        if (candidate.tmdbId != null) {
            return if (existingByTmdbId != null) {
                Decision(Action.LINK_EXISTING, existingByTmdbId.id, CanonicalMatchKind.TMDB, key)
            } else {
                Decision(Action.CREATE_NEW, matchKind = CanonicalMatchKind.TMDB, key = key)
            }
        }

        if (key.year != null) {
            // Two titles under the same key with different KNOWN years are different works
            // (a remake, a sequel that reused the title) — never merged.
            val exact = existingByTitleKey.firstOrNull { it.year == key.year }
            if (exact != null) return Decision(Action.LINK_EXISTING, exact.id, CanonicalMatchKind.TITLE_YEAR, key)

            // A single yearless existing entry under this key is presumed to be the same work,
            // just first seen without a year — this candidate's year fills that gap.
            val yearless = existingByTitleKey.filter { it.year == null }
            if (yearless.size == 1) {
                return Decision(Action.LINK_EXISTING, yearless.single().id, CanonicalMatchKind.TITLE_YEAR, key)
            }
            return Decision(Action.CREATE_NEW, matchKind = CanonicalMatchKind.TITLE_YEAR, key = key)
        }

        // No year at all on this candidate: only merge when it is unambiguous — i.e. every
        // existing entry under this key already agrees (there's at most one, or they somehow
        // share a year already). Two existing entries with genuinely different years mean the
        // key alone cannot tell them apart, so a yearless candidate creates its own entry rather
        // than guessing.
        val distinctYears = existingByTitleKey.mapNotNull { it.year }.distinct()
        return when {
            existingByTitleKey.isEmpty() -> Decision(Action.CREATE_NEW, matchKind = CanonicalMatchKind.TITLE_ONLY, key = key)
            existingByTitleKey.size == 1 || distinctYears.size <= 1 ->
                Decision(Action.LINK_EXISTING, existingByTitleKey.first().id, CanonicalMatchKind.TITLE_ONLY, key)
            else -> Decision(Action.CREATE_NEW, matchKind = CanonicalMatchKind.TITLE_ONLY, key = key)
        }
    }
}
