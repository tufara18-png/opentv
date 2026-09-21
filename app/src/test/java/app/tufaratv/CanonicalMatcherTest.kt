/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.model.CanonicalContent
import app.tufaratv.data.model.CanonicalKind
import app.tufaratv.data.model.CanonicalMatchKind
import app.tufaratv.data.parser.CanonicalMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Each case here is a real multi-provider dedup scenario: the same title showing up from two
 * IPTV panels with different tags/wording, or two genuinely different works sharing a name. When
 * two providers' copies of a film wrongly land in separate canonical entries (or, worse, two
 * different films wrongly merge), the fix starts with a new case here.
 */
class CanonicalMatcherTest {

    private fun canonical(
        id: Long = 1,
        tmdbId: String? = null,
        title: String = "The Wilds",
        titleKey: String = "thewilds",
        year: Int? = null,
    ) = CanonicalContent(id = id, kind = CanonicalKind.MOVIE, tmdbId = tmdbId, title = title, titleKey = titleKey, year = year)

    @Test
    fun `a provider tmdbId links straight to the existing entry with that id`() {
        val existing = canonical(id = 42, tmdbId = "603")
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "King - The Matrix FHD", explicitYear = 1999, tmdbId = "603"),
            existingByTmdbId = existing,
            existingByTitleKey = listOf(existing),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.LINK_EXISTING)
        assertThat(decision.existingId).isEqualTo(42)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TMDB)
    }

    @Test
    fun `a provider tmdbId with no existing match creates a new TMDB-confidence entry`() {
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "Render - The Matrix FHD", explicitYear = 1999, tmdbId = "603"),
            existingByTmdbId = null,
            existingByTitleKey = emptyList(),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.CREATE_NEW)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TMDB)
    }

    @Test
    fun `same title and exact matching year links to the existing entry`() {
        val existing = canonical(id = 7, year = 2020)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "Render | The Wilds (2020)"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(existing),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.LINK_EXISTING)
        assertThat(decision.existingId).isEqualTo(7)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_YEAR)
    }

    @Test
    fun `same title but a different known year never merges - no blind remake merge`() {
        val existing1990 = canonical(id = 1, title = "Dune", titleKey = "dune", year = 1984)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "King - Dune (2021)", explicitYear = 2021),
            existingByTmdbId = null,
            existingByTitleKey = listOf(existing1990),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.CREATE_NEW)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_YEAR)
    }

    @Test
    fun `a dated candidate merges into the sole existing yearless entry under that key`() {
        val yearless = canonical(id = 9, year = null)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "King | The Wilds (2020)", explicitYear = 2020),
            existingByTmdbId = null,
            existingByTitleKey = listOf(yearless),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.LINK_EXISTING)
        assertThat(decision.existingId).isEqualTo(9)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_YEAR)
    }

    @Test
    fun `a yearless candidate merges into the single existing entry under that key`() {
        val existing = canonical(id = 3, year = 2020)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "Render - The Wilds"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(existing),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.LINK_EXISTING)
        assertThat(decision.existingId).isEqualTo(3)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_ONLY)
    }

    @Test
    fun `a yearless candidate does not guess between two genuinely different existing years`() {
        val dune1984 = canonical(id = 1, title = "Dune", titleKey = "dune", year = 1984)
        val dune2021 = canonical(id = 2, title = "Dune", titleKey = "dune", year = 2021)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "King - Dune"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(dune1984, dune2021),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.CREATE_NEW)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_ONLY)
    }

    @Test
    fun `a first sighting with no existing entries under the key creates a new one`() {
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(rawName = "UK| Some Brand New Film"),
            existingByTmdbId = null,
            existingByTitleKey = emptyList(),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.CREATE_NEW)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TITLE_ONLY)
    }

    @Test
    fun `keyOf strips provider junk and quality tags but keeps the year in the display title`() {
        val key = CanonicalMatcher.keyOf("US| The Godfather 1972 HD")

        assertThat(key.titleKey).isEqualTo("thegodfather")
        assertThat(key.displayTitle).isEqualTo("The Godfather 1972")
        assertThat(key.year).isEqualTo(1972)
    }

    @Test
    fun `keyOf prefers an explicit structured year over one embedded in the name`() {
        val key = CanonicalMatcher.keyOf("The Wilds (2019)", explicitYear = 2020)

        assertThat(key.year).isEqualTo(2020)
    }

    @Test
    fun `two providers' quality-tagged copies of the same dated film share a title key`() {
        val a = CanonicalMatcher.keyOf("Barbie (2023) FHD")
        val b = CanonicalMatcher.keyOf("Barbie (2023) 4K")

        assertThat(a.titleKey).isEqualTo(b.titleKey)
        assertThat(a.year).isEqualTo(b.year)
    }
    @Test
    fun `title years are preserved and release years are separated correctly`() {
        val spaceOdyssey = CanonicalMatcher.keyOf("2001: A Space Odyssey (1968)")
        assertThat(spaceOdyssey.titleKey).isEqualTo("2001aspaceodyssey")
        assertThat(spaceOdyssey.year).isEqualTo(1968)

        val bladeRunner = CanonicalMatcher.keyOf("Blade Runner 2049")
        assertThat(bladeRunner.titleKey).isEqualTo("bladerunner2049")
        assertThat(bladeRunner.year).isNull()

        val bladeRunnerDated = CanonicalMatcher.keyOf("Blade Runner 2049 (2017) 4K")
        assertThat(bladeRunnerDated.titleKey).isEqualTo("bladerunner2049")
        assertThat(bladeRunnerDated.year).isEqualTo(2017)
    }

    @Test
    fun `dirty multi-source variants of the same movie converge to one canonical key`() {
        val sourceA = CanonicalMatcher.keyOf("FR| Oppenheimer (2023) FHD")
        val sourceB = CanonicalMatcher.keyOf("[MULTI] Oppenheimer 2023 4K")
        val sourceC = CanonicalMatcher.keyOf("AMZ - Oppenheimer (2023) HEVC HDR")

        assertThat(sourceA.titleKey).isEqualTo("oppenheimer")
        assertThat(sourceB.titleKey).isEqualTo("oppenheimer")
        assertThat(sourceC.titleKey).isEqualTo("oppenheimer")
        assertThat(setOf(sourceA.year, sourceB.year, sourceC.year)).containsExactly(2023)
    }

    @Test
    fun `same provider title with different release years remains split for remakes`() {
        val dune1984 = canonical(id = 1, title = "Dune", titleKey = "dune", year = 1984)
        val dune2021 = canonical(id = 2, title = "Dune", titleKey = "dune", year = 2021)

        val old = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate("Dune (1984) FHD"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(dune1984, dune2021),
        )
        val modern = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate("Dune (2021) 4K"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(dune1984, dune2021),
        )

        assertThat(old.existingId).isEqualTo(1)
        assertThat(modern.existingId).isEqualTo(2)
    }

    @Test
    fun `same tmdb id beats localized provider titles completely`() {
        val existing = canonical(id = 55, tmdbId = "129", title = "Spirited Away", titleKey = "spiritedaway", year = 2001)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate(
                rawName = "FR| Le Voyage de Chihiro FHD",
                explicitYear = 2001,
                tmdbId = "129",
            ),
            existingByTmdbId = existing,
            existingByTitleKey = emptyList(),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.LINK_EXISTING)
        assertThat(decision.existingId).isEqualTo(55)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TMDB)
    }

    @Test
    fun `different tmdb ids never merge just because provider titles look identical`() {
        val otherDune = canonical(id = 1, tmdbId = "841", title = "Dune", titleKey = "dune", year = 1984)
        val decision = CanonicalMatcher.decide(
            candidate = CanonicalMatcher.Candidate("Dune", explicitYear = 2021, tmdbId = "438631"),
            existingByTmdbId = null,
            existingByTitleKey = listOf(otherDune),
        )

        assertThat(decision.action).isEqualTo(CanonicalMatcher.Action.CREATE_NEW)
        assertThat(decision.matchKind).isEqualTo(CanonicalMatchKind.TMDB)
    }


    @Test
    fun `structured provider year wins without polluting the title key with a bad embedded year`() {
        val key = CanonicalMatcher.keyOf("The Wilds (2019) FHD", explicitYear = 2020)

        assertThat(key.titleKey).isEqualTo("thewilds")
        assertThat(key.year).isEqualTo(2020)
    }


}
