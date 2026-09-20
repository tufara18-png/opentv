/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.CategoryNameCleaner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every case here is a real category seen on a live provider (see the session that added this
 * file — a French Xtream account's own live category list, e.g. the raw `"FR| GENERAL France
 * [SD]"` [ChannelNameNormalizer] already folds to `baseName = "GENERAL France"`, `region = "FR"`).
 */
class CategoryNameCleanerTest {

    @Test
    fun `strips a trailing name that repeats the region`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("GENERAL France", "FR")).isEqualTo("GENERAL")
        assertThat(CategoryNameCleaner.stripRedundantRegion("CINEMA USA", "US")).isEqualTo("CINEMA")
    }

    @Test
    fun `a non-Latin trailing name matching its region is stripped too`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("Algérie الجزائر", "DZ")).isEqualTo("Algérie")
    }

    @Test
    fun `no region means nothing to compare against - unchanged`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("MULTI Chaines TV", null)).isEqualTo("MULTI Chaines TV")
    }

    @Test
    fun `a region with no matching trailing word is left unchanged`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("MULTI Informations", "FR")).isEqualTo("MULTI Informations")
        assertThat(CategoryNameCleaner.stripRedundantRegion("Bein Sports", "FR")).isEqualTo("Bein Sports")
    }

    @Test
    fun `an unrecognised region code is left unchanged`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("Some Category", "ZZ")).isEqualTo("Some Category")
    }

    @Test
    fun `a middle word that happens to be a country name is left alone`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("La Liga Espagne DAZN", "FR")).isEqualTo("La Liga Espagne DAZN")
    }

    @Test
    fun `a base name that IS only the region's spelled-out form is never emptied`() {
        assertThat(CategoryNameCleaner.stripRedundantRegion("France", "FR")).isEqualTo("France")
    }
}
