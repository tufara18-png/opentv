/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

/**
 * Strips a trailing spelled-out country/language name that just repeats the leading code
 * [ChannelNameNormalizer] already stripped into its `region` — the piece that normaliser doesn't
 * cover, since it only ever looks at the *front* of a name.
 *
 * Real category names come back from [ChannelNameNormalizer.normalize] already missing their
 * provider-side leading tag and any bracketed quality suffix — `"FR| GENERAL France [SD]"` folds
 * to `baseName = "GENERAL France"`, `region = "FR"` — but the trailing `"France"` is still there,
 * because normalizing a *channel* name never had a reason to know `"FR"` and `"France"` are the
 * same fact stated twice. This is the one extra step: given that same `(baseName, region)` pair,
 * drop a trailing word that spells the region back out.
 */
object CategoryNameCleaner {

    /** Region code -> the trailing spelled-out names (upper-cased, letters only) that repeat it. */
    private val TRAILING_NAMES: Map<String, Set<String>> = mapOf(
        "FR" to setOf("FRANCE"),
        "US" to setOf("USA", "UNITEDSTATES", "AMERICA"),
        "UK" to setOf("UK", "UNITEDKINGDOM", "BRITAIN", "GB"),
        "DE" to setOf("GERMANY", "DEUTSCHLAND"),
        "ES" to setOf("SPAIN", "ESPANA", "ESPAÑA"),
        "IT" to setOf("ITALY", "ITALIA"),
        "PT" to setOf("PORTUGAL"),
        "NL" to setOf("NETHERLANDS", "HOLLAND"),
        "BE" to setOf("BELGIUM", "BELGIQUE"),
        "CA" to setOf("CANADA"),
        "DZ" to setOf("ALGERIA", "ALGERIE", "ALGÉRIE", "الجزائر"),
        "MA" to setOf("MOROCCO", "MAROC"),
        "TN" to setOf("TUNISIA", "TUNISIE"),
        "TR" to setOf("TURKEY", "TURKIYE", "TÜRKIYE"),
        "AR" to setOf("ARGENTINA"),
        "BR" to setOf("BRAZIL", "BRASIL"),
        "MX" to setOf("MEXICO", "MÉXICO"),
        "IN" to setOf("INDIA"),
        "AU" to setOf("AUSTRALIA"),
        "PL" to setOf("POLAND", "POLSKA"),
        "RU" to setOf("RUSSIA"),
        "NZ" to setOf("NEWZEALAND"),
    )

    // \p{Z} (Unicode "separator" category) on top of \s: real provider text uses non-breaking and
    // other exotic spaces \s alone doesn't match, and missing that silently no-ops this whole pass.
    private val WHITESPACE = Regex("""[\s\p{Z}]+""")
    private val NON_LETTER = Regex("""[^\p{L}]""")

    /**
     * @param baseName [ChannelNameNormalizer.Normalized.baseName] — the name with its leading tag
     *   and any quality/decoration already stripped.
     * @param region [ChannelNameNormalizer.Normalized.region] — the code stripped from the front,
     *   or null if there wasn't one (in which case there is nothing to compare the trailing word
     *   against, and [baseName] is returned unchanged).
     */
    fun stripRedundantRegion(baseName: String, region: String?): String {
        if (region == null) return baseName
        val trailingNames = TRAILING_NAMES[region.uppercase()] ?: return baseName

        val words = baseName.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val lastWord = words.lastOrNull()?.uppercase()?.let { NON_LETTER.replace(it, "") }
        if (words.size <= 1 || lastWord !in trailingNames) return baseName

        return words.dropLast(1).joinToString(" ")
    }
}
