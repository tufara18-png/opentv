/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.NationalChannelOrder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NationalChannelOrderTest {

    @Test
    fun `TF1 ranks before France 2, which ranks before France 3, which ranks before Canal+`() {
        val tf1 = NationalChannelOrder.rank("FR", "tf1")!!
        val f2 = NationalChannelOrder.rank("FR", "france2")!!
        val f3 = NationalChannelOrder.rank("FR", "france3")!!
        val canal = NationalChannelOrder.rank("FR", "canal")!!

        assertThat(tf1).isLessThan(f2)
        assertThat(f2).isLessThan(f3)
        assertThat(f3).isLessThan(canal)
    }

    @Test
    fun `a channel not in the lineup ranks null, not last-by-convention`() {
        assertThat(NationalChannelOrder.rank("FR", "somelocalchannel")).isNull()
    }

    @Test
    fun `a country with no curated lineup ranks everything null`() {
        assertThat(NationalChannelOrder.rank("DE", "tf1")).isNull()
    }

    @Test
    fun `a regional feed ranks by its network's prefix, confirmed against real provider names`() {
        // Real channel names from a live Stalker portal's "CANADA" bouquet: "CBC News", "CBC
        // Montreal", "CBC Toronto", "CTV News", "CTV Toronto" — none equal the curated bare "cbc"
        // / "ctv" entries, so before the prefix fallback every one of these ranked null and the
        // shelf fell back to the provider's raw, effectively random order.
        val cbcNews = NationalChannelOrder.rank("CA", "cbcnews")!!
        val cbcMontreal = NationalChannelOrder.rank("CA", "cbcmontreal")!!
        val cbcToronto = NationalChannelOrder.rank("CA", "cbctoronto")!!
        val ctvNews = NationalChannelOrder.rank("CA", "ctvnews")!!
        val ctvToronto = NationalChannelOrder.rank("CA", "ctvtoronto")!!
        val bareCbc = NationalChannelOrder.rank("CA", "cbc")!!
        val bareCtv = NationalChannelOrder.rank("CA", "ctv")!!

        assertThat(cbcNews).isEqualTo(bareCbc)
        assertThat(cbcMontreal).isEqualTo(bareCbc)
        assertThat(cbcToronto).isEqualTo(bareCbc)
        assertThat(ctvNews).isEqualTo(bareCtv)
        assertThat(ctvToronto).isEqualTo(bareCtv)
        assertThat(bareCbc).isLessThan(bareCtv)
    }

    @Test
    fun `a prefix match never fires for an unrelated channel`() {
        assertThat(NationalChannelOrder.rank("CA", "cbsnews")).isNull()
        assertThat(NationalChannelOrder.rank("CA", "somelocalchannel")).isNull()
    }

    @Test
    fun `networkKey works for any country with a curated lineup, not just Canada`() {
        assertThat(NationalChannelOrder.networkKey("FR", "tf1paris")).isEqualTo("tf1")
        assertThat(NationalChannelOrder.networkKey("FR", "france2lyon")).isEqualTo("france2")
        assertThat(NationalChannelOrder.networkKey("GB", "bbc1london")).isEqualTo("bbc1")
        assertThat(NationalChannelOrder.networkKey("CA", "tvasherbrooke")).isEqualTo("tva")
        // Longest-prefix wins: "icitele" (7 chars) beats the shorter "ici" (3 chars).
        assertThat(NationalChannelOrder.networkKey("CA", "icitelequebec")).isEqualTo("icitele")
    }

    @Test
    fun `networkKey is null for a country with no curated lineup`() {
        assertThat(NationalChannelOrder.networkKey("XX", "anything")).isNull()
    }

    @Test
    fun `known Quebec channels are recognized by name regardless of provider category`() {
        assertThat(NationalChannelOrder.isQuebecChannel("radiocanada")).isTrue()
        assertThat(NationalChannelOrder.isQuebecChannel("tva")).isTrue()
        assertThat(NationalChannelOrder.isQuebecChannel("rds")).isTrue()
        assertThat(NationalChannelOrder.isQuebecChannel("addiktv")).isTrue()
        assertThat(NationalChannelOrder.isQuebecChannel("cbc")).isFalse()
        assertThat(NationalChannelOrder.isQuebecChannel("ctv")).isFalse()
    }

    @Test
    fun `canadaTier puts Quebec first regardless of content type`() {
        val quebecGeneral = NationalChannelOrder.canadaTier(isQuebec = true, typeRank = 0)
        val quebecSport = NationalChannelOrder.canadaTier(isQuebec = true, typeRank = 1)
        val angloSport = NationalChannelOrder.canadaTier(isQuebec = false, typeRank = 1)
        val angloGeneral = NationalChannelOrder.canadaTier(isQuebec = false, typeRank = 0)

        assertThat(quebecGeneral).isEqualTo(quebecSport)
        assertThat(quebecGeneral).isLessThan(angloSport)
        assertThat(angloSport).isLessThan(angloGeneral)
    }
}
