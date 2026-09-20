/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.CategoryContentClassifier
import app.tufaratv.data.parser.CategoryContentType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryContentClassifierTest {

    @Test
    fun `a blank bouquet name (bare country) is general`() {
        assertThat(CategoryContentClassifier.classify("")).isEqualTo(CategoryContentType.GENERAL)
    }

    @Test
    fun `general and TNT keywords classify as general`() {
        assertThat(CategoryContentClassifier.classify("GENERAL")).isEqualTo(CategoryContentType.GENERAL)
        assertThat(CategoryContentClassifier.classify("TNT")).isEqualTo(CategoryContentType.GENERAL)
    }

    @Test
    fun `sport keywords classify as sport regardless of provider wording`() {
        assertThat(CategoryContentClassifier.classify("Bein Sports")).isEqualTo(CategoryContentType.SPORT)
        assertThat(CategoryContentClassifier.classify("Canal+ Sports")).isEqualTo(CategoryContentType.SPORT)
        assertThat(CategoryContentClassifier.classify("La Liga Espagne")).isEqualTo(CategoryContentType.SPORT)
    }

    @Test
    fun `cinema keywords classify as cinema`() {
        assertThat(CategoryContentClassifier.classify("CINEMA")).isEqualTo(CategoryContentType.CINEMA)
        assertThat(CategoryContentClassifier.classify("Films")).isEqualTo(CategoryContentType.CINEMA)
    }

    @Test
    fun `general ranks before sport, which ranks before cinema, which ranks before the rest`() {
        assertThat(CategoryContentType.GENERAL.rank).isLessThan(CategoryContentType.SPORT.rank)
        assertThat(CategoryContentType.SPORT.rank).isLessThan(CategoryContentType.CINEMA.rank)
        assertThat(CategoryContentType.CINEMA.rank).isLessThan(CategoryContentType.DOCUMENTARY.rank)
    }

    @Test
    fun `an unrecognized bouquet name falls back to other, last`() {
        val type = CategoryContentClassifier.classify("Bloomberg Business Weekly")
        assertThat(type).isEqualTo(CategoryContentType.OTHER)
        assertThat(type.rank).isEqualTo(CategoryContentType.entries.maxOf { it.rank })
    }
}
