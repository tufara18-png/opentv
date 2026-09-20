/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

/**
 * What a live-TV bouquet is *about*, independent of which country it belongs to — the axis a
 * country's channels get ordered on once every one of its bouquets folds into a single shelf
 * (see [app.tufaratv.ui.OpenTvViewModels.ChannelsViewModel.foldCategories]): general/terrestrial
 * channels first, sport next, cinema after that, the rest trailing behind. [rank] is that order.
 */
enum class CategoryContentType(val rank: Int) {
    GENERAL(0), SPORT(1), CINEMA(2), DOCUMENTARY(3), KIDS(4), NEWS(5), MUSIC(6), OTHER(7)
}

/**
 * Classifies a bouquet's content by keyword, from whatever's left of its name once
 * [ChannelNameNormalizer] and [CategoryNameCleaner] have stripped the country/quality/decoration
 * off it — so it works on any provider's wording for the same idea (`"CINEMA"`, `"Cine"`,
 * `"Films"` all read as [CategoryContentType.CINEMA]) rather than one hardcoded per-country list.
 * A blank string — a bare country bouquet with nothing left to say what it's about — is
 * [CategoryContentType.GENERAL], since that's exactly what an undifferentiated "France" or "TNT"
 * shelf is.
 */
object CategoryContentClassifier {

    private val WORD = Regex("""[^\p{L}0-9+]+""")

    private val GENERAL = setOf("GENERAL", "GENERALISTE", "GENERALISTA", "TNT", "PRINCIPALE", "PRINCIPAL", "NATIONALE")
    private val SPORT = setOf(
        "SPORT", "SPORTS", "BEIN", "DAZN", "LIGA", "FOOT", "FOOTBALL", "MOTOGP", "F1", "NBA",
        "UFC", "RUGBY", "TENNIS", "BASKET", "BASKETBALL", "BOXE", "BOXING",
    )
    private val CINEMA = setOf("CINEMA", "CINE", "FILM", "FILMS", "MOVIE", "MOVIES", "SERIE", "SERIES")
    private val DOCUMENTARY = setOf("DOCUMENTAIRE", "DOCUMENTAIRES", "DOCUMENTARY", "DOC", "DOCS")
    private val KIDS = setOf(
        "ENFANT", "ENFANTS", "KIDS", "DESSIN", "DESSINS", "ANIME", "ANIMES", "ANIMATION", "MANGA", "CARTOON",
    )
    private val NEWS = setOf("INFO", "INFOS", "INFORMATION", "INFORMATIONS", "NEWS", "ACTU", "ACTUALITE", "ACTUALITES")
    private val MUSIC = setOf("MUSIQUE", "MUSIQUES", "MUSIC", "CLIP", "CLIPS")

    fun classify(content: String): CategoryContentType {
        if (content.isBlank()) return CategoryContentType.GENERAL
        val tokens = content.uppercase().split(WORD).filter { it.isNotEmpty() }.toSet()
        fun any(words: Set<String>) = tokens.any { it in words }
        return when {
            any(GENERAL) -> CategoryContentType.GENERAL
            any(SPORT) -> CategoryContentType.SPORT
            any(CINEMA) -> CategoryContentType.CINEMA
            any(DOCUMENTARY) -> CategoryContentType.DOCUMENTARY
            any(KIDS) -> CategoryContentType.KIDS
            any(NEWS) -> CategoryContentType.NEWS
            any(MUSIC) -> CategoryContentType.MUSIC
            else -> CategoryContentType.OTHER
        }
    }
}
