/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

/**
 * One playable source behind a canonical entry — a specific provider's copy at a specific
 * quality, e.g. "Render — FHD". Not a persisted table: it is a projection built by a DAO join
 * query (`CanonicalMovieDao.variantsFor`, `CanonicalSeriesDao.variantsFor`,
 * `CanonicalEpisodeDao.variantsFor`) over the existing per-source `Movie`/`Series`/`Episode` rows
 * and their owning `Source`, so linking to a canonical entry never duplicates data into a second
 * schema. `sourceId`/`streamId` identify the underlying variant row; `cmd` is only ever set for
 * Stalker channel variants (see [app.tufaratv.data.model.SourceKind.STALKER]) — always null for
 * movie/series/episode variants, which have no such resolution step.
 */
data class SourceVariant(
    val sourceId: Long,
    val sourceName: String,
    val streamId: String,
    val quality: String,
    val qualityRank: Int,
    val language: String?,
    val codec: String?,
    val streamUrl: String,
    /** The owning [app.tufaratv.data.model.Source]'s own User-Agent — panels commonly 403 a
     *  client whose UA they don't recognise, per-source, so playback must use this rather than
     *  a screen-wide constant once a title has more than one provider behind it. */
    val userAgent: String,
    val cmd: String? = null,
)

/** The result of a TMDB-catalog availability lookup — see
 *  `CatalogRepository.movieAvailabilityForTmdb`. [canonicalId] is null exactly when [variants] is
 *  empty: nothing synced matches this TMDB title. */
data class MovieAvailability(val canonicalId: Long?, val variants: List<SourceVariant>)
