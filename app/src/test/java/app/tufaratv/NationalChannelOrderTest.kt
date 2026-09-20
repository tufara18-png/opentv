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
