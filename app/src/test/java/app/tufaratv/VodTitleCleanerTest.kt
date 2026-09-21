/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.VodTitleCleaner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [VodTitleCleaner.parse] — the pieces [VodTitleCleaner.clean] strips and discards, kept
 * instead as independent Source/Quality/language fields. `title` itself is exactly `clean`'s
 * output, so this focuses on what's new: quality, year, language, codec.
 */
class VodTitleCleanerTest {

    @Test
    fun `plain quality tag is extracted and the title stays clean`() {
        val p = VodTitleCleaner.parse("US| The Godfather 1972 HD")

        assertThat(p.title).isEqualTo("The Godfather 1972")
        assertThat(p.year).isEqualTo(1972)
        assertThat(p.qualityLabel).isEqualTo("HD")
        assertThat(p.qualityRank).isEqualTo(200)
    }

    @Test
    fun `a plus-suffixed tier ranks above the plain tag and below the next real tier`() {
        val plain = VodTitleCleaner.parse("Barbie (2023) FHD")
        val plussed = VodTitleCleaner.parse("Barbie (2023) FHD++++")
        val next = VodTitleCleaner.parse("Barbie (2023) 4K")

        assertThat(plussed.qualityLabel).isEqualTo("FHD++++")
        assertThat(plussed.qualityRank).isGreaterThan(plain.qualityRank)
        assertThat(plussed.qualityRank).isLessThan(next.qualityRank)
    }

    @Test
    fun `a leading language tag is captured rather than only discarded`() {
        val p = VodTitleCleaner.parse("EN - Apple Music Live - Ed Sheeran")

        assertThat(p.title).isEqualTo("Apple Music Live - Ed Sheeran")
        assertThat(p.language).isEqualTo("EN")
    }

    @Test
    fun `a hyphen-glued quality-language compound tag yields both fields`() {
        val p = VodTitleCleaner.parse("4K-EN - Barbie (2023)")

        assertThat(p.title).isEqualTo("Barbie (2023)")
        assertThat(p.qualityLabel).isEqualTo("4K")
        assertThat(p.language).isEqualTo("EN")
    }

    @Test
    fun `a codec or HDR marker is captured as codec, not folded into quality`() {
        val p = VodTitleCleaner.parse("The Matrix 1999 FHD HEVC HDR")

        assertThat(p.qualityLabel).isEqualTo("FHD")
        assertThat(p.codec).contains("HEVC")
        assertThat(p.codec).contains("HDR")
    }

    @Test
    fun `a pipe-wrapped leading quality tag is stripped from the title`() {
        assertThat(VodTitleCleaner.clean("|FHD| Creepshow 2 (1987)")).isEqualTo("Creepshow 2 (1987)")
        assertThat(VodTitleCleaner.clean("|FHD| The Get Out (2026 MULTI)")).isEqualTo("The Get Out (2026 MULTI)")
    }

    @Test
    fun `an episode title repeating the series name and SxxExx marker is reduced to just the name`() {
        assertThat(VodTitleCleaner.cleanEpisodeTitle("|4K| MobLand GB (2025 MULTI) - S01E08 - Helter Skelter"))
            .isEqualTo("Helter Skelter")
        assertThat(VodTitleCleaner.cleanEpisodeTitle("|4K| MobLand GB (2025 MULTI) - S01E10 |FIN| - The Beast in Me"))
            .isEqualTo("The Beast in Me")
    }

    @Test
    fun `an episode title with no SxxExx marker is left alone, even with a real dash in it`() {
        assertThat(VodTitleCleaner.cleanEpisodeTitle("Spider-Man Returns - Part 1"))
            .isEqualTo("Spider-Man Returns - Part 1")
        assertThat(VodTitleCleaner.cleanEpisodeTitle("Épisode 1")).isEqualTo("Épisode 1")
    }

    @Test
    fun `a title repeating its own year is stripped of it, wherever it sits`() {
        assertThat(VodTitleCleaner.stripRedundantYear("Oklahoma Honey (2026) The Rivals of Amziah King", 2026))
            .isEqualTo("Oklahoma Honey The Rivals of Amziah King")
        assertThat(VodTitleCleaner.stripRedundantYear("Our Sticky Love (2026)", 2026))
            .isEqualTo("Our Sticky Love")
    }

    @Test
    fun `a year that is genuinely part of the title is left alone`() {
        assertThat(VodTitleCleaner.stripRedundantYear("Blade Runner 2049", 2017)).isEqualTo("Blade Runner 2049")
        assertThat(VodTitleCleaner.stripRedundantYear("2001: A Space Odyssey", 1968))
            .isEqualTo("2001: A Space Odyssey")
    }

    @Test
    fun `no known year leaves the title untouched`() {
        assertThat(VodTitleCleaner.stripRedundantYear("Our Sticky Love (2026)", null))
            .isEqualTo("Our Sticky Love (2026)")
    }

    @Test
    fun `no quality or language tag leaves both fields empty and unknown`() {
        val p = VodTitleCleaner.parse("Our Sticky Love")

        assertThat(p.qualityLabel).isEmpty()
        assertThat(p.qualityRank).isEqualTo(0)
        assertThat(p.language).isNull()
        assertThat(p.codec).isNull()
        assertThat(p.year).isNull()
    }
    @Test
    fun `release year inference does not eat years that are part of the title`() {
        assertThat(VodTitleCleaner.inferReleaseYear("2001: A Space Odyssey (1968)")).isEqualTo(1968)
        assertThat(VodTitleCleaner.inferReleaseYear("Blade Runner 2049")).isNull()
        assertThat(VodTitleCleaner.inferReleaseYear("1917")).isNull()
        assertThat(VodTitleCleaner.inferReleaseYear("The Godfather 1972 HD")).isEqualTo(1972)
    }

    @Test
    fun `release year extraction works across common dirty provider forms`() {
        assertThat(VodTitleCleaner.inferReleaseYear("FR| Oppenheimer (2023) FHD")).isEqualTo(2023)
        assertThat(VodTitleCleaner.inferReleaseYear("[MULTI] Oppenheimer 2023 4K")).isEqualTo(2023)
        assertThat(VodTitleCleaner.inferReleaseYear("NF - Dark (2017) (DE)")).isEqualTo(2017)
    }


}
