/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv

import app.tufaratv.core.AppSettings
import app.tufaratv.data.repo.EngineKind
import app.tufaratv.data.repo.FailoverStrategy
import app.tufaratv.data.repo.PlayerEngineChoice
import app.tufaratv.data.repo.PlayerLadder
import app.tufaratv.data.repo.SourceVariant
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerLadderTest {

    private fun variant(sourceId: Long, sourceName: String, quality: String, rank: Int) = SourceVariant(
        sourceId = sourceId, sourceName = sourceName, streamId = "$sourceId-$quality", quality = quality,
        qualityRank = rank, language = null, codec = null, streamUrl = "https://x/$sourceId/$quality",
        userAgent = "TufaraTV/0.1 (Android)",
    )

    private val king1 = variant(1, "King", "FHD", 300)
    private val king2 = variant(1, "King", "HD", 200)
    private val render1 = variant(2, "Render", "FHD", 300)
    private val render2 = variant(2, "Render", "4K", 400)
    private val all = listOf(king1, king2, render1, render2)

    @Test
    fun `ranked variants group by source, best source first, best quality within each`() {
        val ranked = PlayerLadder.rankedVariants(all, AppSettings.DEFAULT_QUALITY_ORDER)

        // Render's group wins the SOURCE ordering on raw numeric rank (its 4K variant is 400 vs
        // King's best of 300) — group ordering is a numeric "Auto" default, not preference-order
        // aware. But *within* Render's own group, the user's preference order (4K ranked last)
        // still puts Render's FHD ahead of Render's own 4K.
        assertThat(ranked.map { it.sourceName }).containsExactly("Render", "Render", "King", "King").inOrder()
        assertThat(ranked.first().quality).isEqualTo("FHD")
    }

    @Test
    fun `a source override pins that source first regardless of numeric rank`() {
        val ranked = PlayerLadder.rankedVariants(all, AppSettings.DEFAULT_QUALITY_ORDER, sourceOverride = "1")

        assertThat(ranked.first().sourceName).isEqualTo("King")
    }

    @Test
    fun `a quality override applies only within the active (first) source group`() {
        val ranked = PlayerLadder.rankedVariants(
            all,
            AppSettings.DEFAULT_QUALITY_ORDER,
            sourceOverride = "1",
            qualityOverride = "HD",
        )

        assertThat(ranked[0]).isEqualTo(king2) // King/HD pinned first within King's group
        assertThat(ranked[1]).isEqualTo(king1) // King's other quality follows
        assertThat(ranked.map { it.sourceName }.drop(2)).containsExactly("Render", "Render")
    }

    @Test
    fun `explicit MEDIA3 engine choice never produces a VLC attempt`() {
        val attempts = PlayerLadder.buildAttempts(
            all, AppSettings.DEFAULT_QUALITY_ORDER, null, null,
            PlayerEngineChoice.MEDIA3, FailoverStrategy.FAST_AUTO, vlcAvailable = true,
        )

        assertThat(attempts.map { it.engine }.distinct()).containsExactly(EngineKind.MEDIA3)
        assertThat(attempts).hasSize(all.size)
    }

    @Test
    fun `FAST_AUTO exhausts every combo on Media3 before any VLC attempt`() {
        val attempts = PlayerLadder.buildAttempts(
            all, AppSettings.DEFAULT_QUALITY_ORDER, null, null,
            PlayerEngineChoice.AUTO, FailoverStrategy.FAST_AUTO, vlcAvailable = true,
        )

        val media3Count = attempts.count { it.engine == EngineKind.MEDIA3 }
        assertThat(media3Count).isEqualTo(all.size)
        assertThat(attempts.take(media3Count).all { it.engine == EngineKind.MEDIA3 }).isTrue()
        assertThat(attempts.drop(media3Count).all { it.engine == EngineKind.VLC }).isTrue()
    }

    @Test
    fun `MAX_COMPATIBILITY tries VLC on each combo immediately after Media3`() {
        val attempts = PlayerLadder.buildAttempts(
            all, AppSettings.DEFAULT_QUALITY_ORDER, null, null,
            PlayerEngineChoice.AUTO, FailoverStrategy.MAX_COMPATIBILITY, vlcAvailable = true,
        )

        // Every pair is (variant, MEDIA3) then (same variant, VLC).
        for (i in attempts.indices step 2) {
            assertThat(attempts[i].engine).isEqualTo(EngineKind.MEDIA3)
            assertThat(attempts[i + 1].engine).isEqualTo(EngineKind.VLC)
            assertThat(attempts[i + 1].variant).isEqualTo(attempts[i].variant)
        }
    }

    @Test
    fun `no VLC engine available degrades every attempt to Media3`() {
        val attempts = PlayerLadder.buildAttempts(
            all, AppSettings.DEFAULT_QUALITY_ORDER, null, null,
            PlayerEngineChoice.AUTO, FailoverStrategy.MAX_COMPATIBILITY, vlcAvailable = false,
        )

        assertThat(attempts.map { it.engine }.distinct()).containsExactly(EngineKind.MEDIA3)
        assertThat(attempts).hasSize(all.size)
    }

    @Test
    fun `no variants at all yields an empty ladder, not an error`() {
        val attempts = PlayerLadder.buildAttempts(
            emptyList(), AppSettings.DEFAULT_QUALITY_ORDER, null, null,
            PlayerEngineChoice.AUTO, FailoverStrategy.FAST_AUTO, vlcAvailable = true,
        )

        assertThat(attempts).isEmpty()
    }
}
