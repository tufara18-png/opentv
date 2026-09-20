/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.CountryResolver
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CountryResolverTest {

    @Test
    fun `a bare ISO code and its full name resolve to the same country`() {
        val byCode = CountryResolver.resolve("FR")
        val byName = CountryResolver.resolve("France")

        assertThat(byCode).isNotNull()
        assertThat(byCode?.code).isEqualTo("FR")
        assertThat(byCode).isEqualTo(byName)
    }

    @Test
    fun `an accented and a plain spelling resolve the same`() {
        assertThat(CountryResolver.resolve("Espana")).isEqualTo(CountryResolver.resolve("España"))
        assertThat(CountryResolver.resolve("Espagne")?.code).isEqualTo("ES")
    }

    @Test
    fun `alternate English and French names for the same country agree`() {
        assertThat(CountryResolver.resolve("United Kingdom")?.code).isEqualTo("GB")
        assertThat(CountryResolver.resolve("UK")?.code).isEqualTo("GB")
        assertThat(CountryResolver.resolve("Royaume-Uni")?.code).isEqualTo("GB")
    }

    @Test
    fun `a flag emoji resolves to its country`() {
        assertThat(CountryResolver.resolve("🇫🇷")?.code).isEqualTo("FR") // 🇫🇷
    }

    @Test
    fun `a compound bouquet name is not merged into a bare country`() {
        assertThat(CountryResolver.resolve("France Sport")).isNull()
        assertThat(CountryResolver.resolve("FR GENERAL")).isNull()
    }

    @Test
    fun `an unknown name resolves to null`() {
        assertThat(CountryResolver.resolve("Narnia")).isNull()
        assertThat(CountryResolver.resolve("")).isNull()
    }

    @Test
    fun `the bare AR code resolves to the Arabic-world group, not Argentina`() {
        assertThat(CountryResolver.resolve("AR")?.displayName).isEqualTo("Monde arabe")
        assertThat(CountryResolver.resolve("Argentina")?.code).isEqualTo("ARG")
        assertThat(CountryResolver.resolve("Argentine")?.code).isEqualTo("ARG")
    }

    @Test
    fun `the bare MULTI code resolves to the multi-language group`() {
        assertThat(CountryResolver.resolve("MULTI")?.displayName).isEqualTo("Multi-langue")
    }

    @Test
    fun `display names are in French`() {
        assertThat(CountryResolver.resolve("US")?.displayName).isEqualTo("États-Unis")
        assertThat(CountryResolver.resolve("DE")?.displayName).isEqualTo("Allemagne")
    }
}
