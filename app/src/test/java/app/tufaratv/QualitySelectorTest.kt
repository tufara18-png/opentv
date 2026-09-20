/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.core.AppSettings
import app.tufaratv.data.repo.QualitySelector
import app.tufaratv.data.repo.SourceVariant
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualitySelectorTest {

    private fun variant(quality: String, rank: Int, streamId: String = quality) = SourceVariant(
        sourceId = 1, sourceName = "King", streamId = streamId, quality = quality,
        qualityRank = rank, language = null, codec = null, streamUrl = "https://x/$streamId",
        userAgent = "TufaraTV/0.1 (Android)",
    )

    @Test
    fun `Auto picks the highest-ranked listed quality per the preference order`() {
        val variants = listOf(variant("SD", 100), variant("FHD", 300), variant("HD", 200))

        val picked = QualitySelector.pick(variants, AppSettings.DEFAULT_QUALITY_ORDER)

        assertThat(picked?.quality).isEqualTo("FHD")
    }

    @Test
    fun `the user's default order puts 4K last, even though its numeric rank is highest`() {
        val variants = listOf(variant("4K", 400), variant("HD", 200))

        val picked = QualitySelector.pick(variants, AppSettings.DEFAULT_QUALITY_ORDER)

        assertThat(picked?.quality).isEqualTo("HD")
    }

    @Test
    fun `an explicit override wins over the preference order when still available`() {
        val variants = listOf(variant("4K", 400), variant("HD", 200))

        val picked = QualitySelector.pick(variants, AppSettings.DEFAULT_QUALITY_ORDER, override = "hd")

        assertThat(picked?.quality).isEqualTo("HD")
    }

    @Test
    fun `an override naming a quality no longer available falls back to the preference order`() {
        val variants = listOf(variant("4K", 400), variant("HD", 200))

        val picked = QualitySelector.pick(variants, AppSettings.DEFAULT_QUALITY_ORDER, override = "FHD++++")

        assertThat(picked?.quality).isEqualTo("HD")
    }

    @Test
    fun `an unrecognised label ranks alongside inconnu, not last`() {
        val variants = listOf(variant("XYZ123", 0), variant("4K", 400))

        val picked = QualitySelector.pick(variants, AppSettings.DEFAULT_QUALITY_ORDER)

        // "inconnu" sits ahead of 4K in the default order, and an unlisted label buckets there.
        assertThat(picked?.quality).isEqualTo("XYZ123")
    }

    @Test
    fun `an empty variant list returns null rather than throwing`() {
        assertThat(QualitySelector.pick(emptyList(), AppSettings.DEFAULT_QUALITY_ORDER)).isNull()
    }
}
