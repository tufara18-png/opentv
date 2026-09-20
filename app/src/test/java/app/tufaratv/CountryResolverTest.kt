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

    @Test
    fun `resolveAnyToken finds a country buried in a free-text bouquet name`() {
        val (country, leftover) = CountryResolver.resolveAnyToken("Elite Cinema FR")!!
        assertThat(country.code).isEqualTo("FR")
        assertThat(leftover).isEqualTo("Elite Cinema")

        assertThat(CountryResolver.resolveAnyToken("DAZN PORTUGAL")!!.first.code).isEqualTo("PT")
        assertThat(CountryResolver.resolveAnyToken("LOCALS USA")!!.first.code).isEqualTo("US")
    }

    @Test
    fun `resolveAnyToken never partial-matches a word that only contains a code`() {
        assertThat(CountryResolver.resolveAnyToken("FRANCE24")).isNull()
        assertThat(CountryResolver.resolveAnyToken("INFO CHANNEL")).isNull()
    }

    @Test
    fun `newly added Latin American and other countries resolve`() {
        assertThat(CountryResolver.resolve("Colombia")?.code).isEqualTo("CO")
        assertThat(CountryResolver.resolve("Venezuela")?.code).isEqualTo("VE")
        assertThat(CountryResolver.resolve("Chile")?.code).isEqualTo("CL")
        assertThat(CountryResolver.resolve("Peru")?.code).isEqualTo("PE")
        assertThat(CountryResolver.resolve("Republica Dominicana")?.code).isEqualTo("DO")
        assertThat(CountryResolver.resolve("Haiti")?.code).isEqualTo("HT")
        assertThat(CountryResolver.resolve("Israel")?.code).isEqualTo("IL")
        assertThat(CountryResolver.resolve("Filipino")?.code).isEqualTo("PH")
        assertThat(CountryResolver.resolve("Greek")?.code).isEqualTo("GR")
        assertThat(CountryResolver.resolve("Nederland")?.code).isEqualTo("NL")
        assertThat(CountryResolver.resolve("Portuguese")?.code).isEqualTo("PT")
    }

    @Test
    fun `curated bouquet-name overrides resolve from real content inspection`() {
        assertThat(CountryResolver.resolve("MAJIK FILM")?.code).isEqualTo("FR")
        assertThat(CountryResolver.resolveKnownBouquet("DISNEY+")?.code).isEqualTo("FR")
        assertThat(CountryResolver.resolveKnownBouquet("TSN+")?.code).isEqualTo("CA")
        assertThat(CountryResolver.resolve("LATINO LOCAL")?.displayName).isEqualTo("Latino")
        assertThat(CountryResolver.resolve("MAJIK PELICULAS")?.displayName).isEqualTo("Latino")
    }

    @Test
    fun `a qualified Disney bouquet is untouched by the bare Disney override`() {
        // The bare-brand override is whole-string-only and its own map — it must never leak into
        // resolve() or resolveAnyToken()'s per-token scan, or "DISNEY+ SPAIN" would wrongly
        // resolve as France via its first token instead of Spain via its second.
        assertThat(CountryResolver.resolve("DISNEY+ SPAIN")).isNull()
        assertThat(CountryResolver.resolveKnownBouquet("DISNEY+ SPAIN")).isNull()
        assertThat(CountryResolver.resolveAnyToken("DISNEY+ SPAIN")!!.first.code).isEqualTo("ES")
    }
}
