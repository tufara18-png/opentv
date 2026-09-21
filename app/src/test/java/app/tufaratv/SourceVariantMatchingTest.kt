/*
 * This file is part of TufaraTV, a fork of OpenTV.
 */
package app.tufaratv

import app.tufaratv.data.model.Movie
import app.tufaratv.data.repo.collapseMovieVariants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceVariantMatchingTest {

    private fun movie(
        id: Long,
        sourceId: Long,
        name: String,
        year: Int? = null,
        canonicalId: Long? = null,
    ) = Movie(
        id = id,
        sourceId = sourceId,
        streamId = "stream-$id",
        name = name,
        categoryId = "movies",
        posterUrl = null,
        rating = null,
        year = year,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.invalid/$id",
        canonicalId = canonicalId,
    )

    @Test
    fun `canonical identity merges localized titles from different providers`() {
        val variants = listOf(
            movie(1, 10, "FR| Le Voyage de Chihiro FHD", 2001, canonicalId = 77),
            movie(2, 20, "Spirited Away 4K", 2001, canonicalId = 77),
            movie(3, 30, "Sen to Chihiro no kamikakushi HD", 2001, canonicalId = 77),
        )

        val groups = collapseMovieVariants(variants)

        assertThat(groups).hasSize(1)
        assertThat(groups.single().variants).hasSize(3)
    }

    @Test
    fun `different canonical ids never collapse even with identical names`() {
        val variants = listOf(
            movie(1, 10, "Dune FHD", 1984, canonicalId = 84),
            movie(2, 20, "Dune 4K", 2021, canonicalId = 21),
        )

        val groups = collapseMovieVariants(variants)

        assertThat(groups).hasSize(2)
    }

    @Test
    fun `pre-link fallback still merges dirty quality variants with the same real identity`() {
        val variants = listOf(
            movie(1, 10, "FR| Oppenheimer (2023) FHD"),
            movie(2, 20, "[MULTI] Oppenheimer 2023 4K"),
            movie(3, 30, "AMZ - Oppenheimer (2023) HEVC HDR"),
        )

        val groups = collapseMovieVariants(variants)

        assertThat(groups).hasSize(1)
        assertThat(groups.single().variants).hasSize(3)
    }

    @Test
    fun `title numbers survive fallback grouping`() {
        val variants = listOf(
            movie(1, 10, "Blade Runner 2049 FHD", canonicalId = null),
            movie(2, 20, "Blade Runner 2049 4K", canonicalId = null),
        )

        val groups = collapseMovieVariants(variants)

        assertThat(groups).hasSize(1)
        assertThat(groups.single().primary.name).contains("4K")
    }
}
