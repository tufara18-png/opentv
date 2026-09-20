/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.parser.ChannelNameNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every case in here is a naming style seen on a real provider. When a channel groups or
 * matches wrongly in the wild, the fix starts with a new test named after it.
 */
class ChannelNameNormalizerTest {

    @Test
    fun `strips a UK pipe prefix and an FHD tag`() {
        val n = ChannelNameNormalizer.normalize("UK| BBC ONE FHD")

        assertThat(n.baseName).isEqualTo("BBC ONE")
        assertThat(n.groupKey).isEqualTo("bbc1")
        assertThat(n.qualityRank).isEqualTo(300)
        assertThat(n.qualityLabel).isEqualTo("FHD")
        assertThat(n.region).isEqualTo("UK")
    }

    @Test
    fun `quality variants of one channel share a group key`() {
        val variants = listOf(
            "UK| BBC ONE SD",
            "UK| BBC ONE HD",
            "UK| BBC ONE FHD",
            "UK - BBC One UHD",
        ).map { ChannelNameNormalizer.normalize(it) }

        assertThat(variants.map { it.groupKey }.distinct()).hasSize(1)
        // And ranks order them correctly for "best plays by default".
        assertThat(variants.map { it.qualityRank }).isEqualTo(listOf(100, 200, 300, 400))
    }

    @Test
    fun `provider name meets the guide name in the middle`() {
        val provider = ChannelNameNormalizer.normalize("UK| BBC ONE FHD").groupKey
        val guide = ChannelNameNormalizer.groupKeyOf("BBC One")

        assertThat(provider).isEqualTo(guide)
    }

    @Test
    fun `word and digit channel numbers fold together`() {
        assertThat(ChannelNameNormalizer.groupKeyOf("BBC One"))
            .isEqualTo(ChannelNameNormalizer.groupKeyOf("BBC 1"))
        assertThat(ChannelNameNormalizer.groupKeyOf("Channel Four"))
            .isEqualTo(ChannelNameNormalizer.groupKeyOf("Channel 4"))
    }

    @Test
    fun `RAW and fps markers strip into the label without ranking`() {
        val n = ChannelNameNormalizer.normalize("UK| PRIME RAW 60fps")

        assertThat(n.baseName).isEqualTo("PRIME")
        assertThat(n.qualityRank).isEqualTo(0)
        assertThat(n.qualityLabel).isEqualTo("RAW 60fps")
    }

    @Test
    fun `hash decorations are stripped`() {
        val n = ChannelNameNormalizer.normalize("####  PRIME RAW 60fps #####")

        assertThat(n.baseName).isEqualTo("PRIME")
    }

    @Test
    fun `a pipe-wrapped country code on both sides is stripped, not leaked into the name`() {
        val n = ChannelNameNormalizer.normalize("|FR| GENERAL France [SD]")

        assertThat(n.region).isEqualTo("FR")
        assertThat(n.baseName).isEqualTo("GENERAL France")
        assertThat(n.qualityLabel).isEqualTo("SD")
    }

    @Test
    fun `a pipe-wrapped country code carrying a MULTI qualifier still resolves to the plain code`() {
        assertThat(ChannelNameNormalizer.normalize("|FR - MULTI| Informations").region).isEqualTo("FR")
        assertThat(ChannelNameNormalizer.normalize("|FR+MULTI| CDM 2026").region).isEqualTo("FR")
    }

    @Test
    fun `bracketed country prefixes work too`() {
        assertThat(ChannelNameNormalizer.normalize("[UK] ITV 1 HD").region).isEqualTo("UK")
        assertThat(ChannelNameNormalizer.normalize("(US) CNN HD").region).isEqualTo("US")
        assertThat(ChannelNameNormalizer.normalize("USA - ESPN").region).isEqualTo("USA")
    }

    @Test
    fun `superscript HD decorations are understood`() {
        val n = ChannelNameNormalizer.normalize("UK| SKY ATLANTIC ᴴᴰ")

        assertThat(n.baseName).isEqualTo("SKY ATLANTIC")
        assertThat(n.qualityRank).isEqualTo(200)
    }

    @Test
    fun `a superscript decoration glued directly onto the name with no separator still folds`() {
        // Real provider data, no space at all before the decoration — confirmed the reason
        // "France 2" and "France 2ᴴᴰ" used to land in two different groups instead of one.
        val plain = ChannelNameNormalizer.normalize("FRANCE 2")
        val glued = ChannelNameNormalizer.normalize("France 2ᴴᴰ")

        assertThat(glued.groupKey).isEqualTo(plain.groupKey)
        assertThat(glued.qualityRank).isEqualTo(200)

        val tf1Plain = ChannelNameNormalizer.normalize("TF1")
        val tf1Glued = ChannelNameNormalizer.normalize("TF1ᴴᴰ")
        assertThat(tf1Glued.groupKey).isEqualTo(tf1Plain.groupKey)
        assertThat(tf1Glued.qualityRank).isEqualTo(200)
    }

    @Test
    fun `WEB is recognised as a stripped stream marker, not part of the channel name`() {
        // Real provider data: "RDS WEB" next to "RDS HD" — both must fold to the same channel.
        val web = ChannelNameNormalizer.normalize("RDS WEB")
        val hd = ChannelNameNormalizer.normalize("RDS HD")
        assertThat(web.groupKey).isEqualTo(hd.groupKey)
        assertThat(web.baseName).isEqualTo("RDS")
    }

    @Test
    fun `a plain camelCase quality suffix with no separator at all still folds`() {
        // No superscript involved this time — literal ASCII glued with zero separator.
        val plain = ChannelNameNormalizer.normalize("Canada")
        val glued = ChannelNameNormalizer.normalize("CanadaHD")
        assertThat(glued.groupKey).isEqualTo(plain.groupKey)
        assertThat(glued.qualityRank).isEqualTo(200)
    }

    @Test
    fun `broadcaster names that look like prefixes are left alone`() {
        // Four-letter and pipe-less names must not lose their first word.
        assertThat(ChannelNameNormalizer.normalize("BEIN| SPORTS 1 HD").baseName)
            .contains("SPORTS")
        val sky = ChannelNameNormalizer.normalize("Sky Sports Main Event")
        assertThat(sky.baseName).isEqualTo("Sky Sports Main Event")
        assertThat(sky.region).isNull()
    }

    @Test
    fun `hevc and h265 are stream tags not part of the name`() {
        val n = ChannelNameNormalizer.normalize("UK| GENERAL hevc")

        assertThat(n.baseName).isEqualTo("GENERAL")
        assertThat(n.qualityLabel).isEqualTo("hevc")
    }

    @Test
    fun `a name that is nothing but junk still yields a usable key`() {
        val n = ChannelNameNormalizer.normalize("HD")

        // Everything stripped: fall back to the raw name rather than an empty key that
        // would group all such channels into one giant accidental family.
        assertThat(n.groupKey).isNotEmpty()
    }

    @Test
    fun `group keys ignore case punctuation and spacing`() {
        val keys = listOf("BBC One", "bbc-one", "B B C ONE", "BbcOne")
            .map { ChannelNameNormalizer.groupKeyOf(it) }

        assertThat(keys.distinct()).hasSize(1)
    }
}
