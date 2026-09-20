/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.data.repo.SourceSelector
import app.tufaratv.data.repo.SourceVariant
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceSelectorTest {

    private fun variant(sourceId: Long, sourceName: String, quality: String, rank: Int) = SourceVariant(
        sourceId = sourceId, sourceName = sourceName, streamId = "$sourceId-$quality", quality = quality,
        qualityRank = rank, language = null, codec = null, streamUrl = "https://x/$sourceId/$quality",
        userAgent = "TufaraTV/0.1 (Android)",
    )

    @Test
    fun `groups variants by their owning source`() {
        val variants = listOf(
            variant(1, "King", "FHD", 300),
            variant(1, "King", "HD", 200),
            variant(2, "Render", "FHD", 300),
        )

        val groups = SourceSelector.groupBySource(variants)

        assertThat(groups).hasSize(2)
        assertThat(groups.first { it.sourceId == 1L }.variants).hasSize(2)
        assertThat(groups.first { it.sourceId == 2L }.variants).hasSize(1)
    }

    @Test
    fun `Auto picks the source whose best available quality ranks highest`() {
        val variants = listOf(
            variant(1, "King", "HD", 200),
            variant(2, "Render", "FHD", 300),
        )

        val picked = SourceSelector.pick(variants)

        assertThat(picked?.sourceName).isEqualTo("Render")
    }

    @Test
    fun `an override wins over Auto when that source is still available`() {
        val variants = listOf(
            variant(1, "King", "HD", 200),
            variant(2, "Render", "FHD", 300),
        )

        val picked = SourceSelector.pick(variants, override = "1")

        assertThat(picked?.sourceName).isEqualTo("King")
    }

    @Test
    fun `an override naming a source no longer available falls back to Auto`() {
        val variants = listOf(variant(2, "Render", "FHD", 300))

        val picked = SourceSelector.pick(variants, override = "999")

        assertThat(picked?.sourceName).isEqualTo("Render")
    }

    @Test
    fun `an empty variant list returns null rather than throwing`() {
        assertThat(SourceSelector.pick(emptyList())).isNull()
    }
}
