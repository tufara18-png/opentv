/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

/**
 * Identifies the country — or, for content that genuinely isn't one country's, the cross-country
 * group (see [DISPLAY_NAMES]) — a live-TV category or channel name refers to, regardless of which
 * provider wrote it or which spelling/code they used for it.
 *
 * A category rail built purely from [ChannelNameNormalizer.groupKey] folds two providers'
 * categories together only when they spelled the name identically. In practice they don't:
 * one provider's top-level bouquet is `"France"`, another's is `"FR"`, a third's is
 * `"🇫🇷 FRANCE"` or `"FR| GENERAL"` — four different group keys for the same shelf, so the
 * rail shows the same country twice (or four times) once a second playlist is added. This
 * object gives every one of those spellings the same canonical [Country], so the category
 * fold can key on *country*, not on raw text, exactly the way a person mentally groups them.
 *
 * Deliberately conservative: it only resolves a name that (once cleaned of accents/case) is
 * itself a known code, name or flag — never a substring match — so a compound bouquet like
 * `"FRANCE SPORT"` is left alone rather than being silently merged into plain `"France"`.
 */
object CountryResolver {

    data class Country(val code: String, val displayName: String)

    /**
     * ISO-3166 alpha-2 code (or, for the two entries below, a made-up code) -> the French display
     * name shown on the category rail.
     *
     * `"AR"` and `"MULTI"` aren't countries — they're the two cross-country groupings real IPTV
     * bouquets actually use and that a bouquet can't be filed under any one country: `"AR"` is
     * overwhelmingly "Arabe" (Arabic-language content spanning the Arab world: `"AR| beIN
     * SPORTS"`, `"AR| GENERAL Arabe"`), not Argentina, which barely appears in this catalogue's
     * real data — Argentina still resolves, just via its spelled-out name, under code `"ARG"` so
     * it doesn't collide with this one. `"MULTI"` is a bouquet mixing languages by design
     * (`"MULTI Chaines TV"`, `"MULTI Sport TV International"`). Giving both their own shelf, the
     * same way a real country gets one, is what keeps them from being left as one stray rail
     * entry per bouquet instead of folding together like everything else.
     */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "AR" to "Monde arabe",
        "MULTI" to "Multi-langue",
        "FR" to "France",
        "GB" to "Royaume-Uni",
        "US" to "États-Unis",
        "DE" to "Allemagne",
        "IT" to "Italie",
        "ES" to "Espagne",
        "PT" to "Portugal",
        "NL" to "Pays-Bas",
        "BE" to "Belgique",
        "LU" to "Luxembourg",
        "CH" to "Suisse",
        "CA" to "Canada",
        "BR" to "Brésil",
        "PL" to "Pologne",
        "CZ" to "Tchéquie",
        "RO" to "Roumanie",
        "GR" to "Grèce",
        "AL" to "Albanie",
        "TR" to "Turquie",
        "MA" to "Maroc",
        "TN" to "Tunisie",
        "DZ" to "Algérie",
        "ARG" to "Argentine",
        "MX" to "Mexique",
        "IN" to "Inde",
        "AU" to "Australie",
        "RU" to "Russie",
        "NZ" to "Nouvelle-Zélande",
        "SE" to "Suède",
        "NO" to "Norvège",
        "DK" to "Danemark",
        "FI" to "Finlande",
        "IE" to "Irlande",
        "AT" to "Autriche",
        "HU" to "Hongrie",
        "BG" to "Bulgarie",
        "HR" to "Croatie",
        "RS" to "Serbie",
        "UA" to "Ukraine",
        "SA" to "Arabie Saoudite",
        "AE" to "Émirats Arabes Unis",
        "EG" to "Égypte",
        "QA" to "Qatar",
        "JP" to "Japon",
        "KR" to "Corée du Sud",
        "AF" to "Afrique",
    )

    /** Every alias a provider might spell a code as -> the ISO code, matched via [normalizeKey]. */
    private val ALIASES: Map<String, String> = buildMap {
        fun alias(code: String, vararg names: String) {
            put(code, code)
            for (name in names) put(normalizeKey(name), code)
        }
        alias("AR", "ARABE", "ARABIC", "ARAB")
        alias("MULTI", "MULTILANGUAGE", "MULTILANGUE")
        alias("FR", "FRANCE", "FRA")
        alias("GB", "UK", "GBR", "UNITEDKINGDOM", "ROYAUMEUNI", "BRITAIN", "GREATBRITAIN", "ANGLETERRE", "ENGLAND")
        alias("US", "USA", "UNITEDSTATES", "AMERICA", "ETATSUNIS")
        alias("DE", "GERMANY", "DEUTSCHLAND", "ALLEMAGNE", "GER")
        alias("IT", "ITALY", "ITALIA", "ITALIE")
        alias("ES", "SPAIN", "ESPANA", "ESPAGNE")
        alias("PT", "PORTUGAL")
        alias("NL", "NETHERLANDS", "HOLLAND", "PAYSBAS")
        alias("BE", "BELGIUM", "BELGIQUE")
        alias("LU", "LUXEMBOURG", "LUXEMBURG")
        alias("CH", "SWITZERLAND", "SUISSE", "SCHWEIZ")
        // "QC" (Québec) has no country of its own — its IPTV content is Canadian, so it folds into
        // the same shelf as a bare "CA" tag rather than scattering as its own ungrouped entries.
        alias("CA", "CANADA", "QC")
        alias("BR", "BRAZIL", "BRASIL", "BRESIL")
        alias("PL", "POLAND", "POLSKA", "POLOGNE")
        alias("CZ", "CZECHIA", "CZECHREPUBLIC", "TCHEQUIE", "REPUBLIQUETCHEQUE")
        alias("RO", "ROMANIA", "ROUMANIE")
        alias("GR", "GREECE", "GRECE", "HELLAS")
        alias("AL", "ALBANIA", "ALBANIE")
        alias("TR", "TURKEY", "TURKIYE", "TURQUIE")
        alias("MA", "MOROCCO", "MAROC")
        alias("TN", "TUNISIA", "TUNISIE")
        alias("DZ", "ALGERIA", "ALGERIE")
        alias("ARG", "ARGENTINA", "ARGENTINE")
        alias("MX", "MEXICO", "MEXIQUE")
        alias("IN", "INDIA", "INDE")
        alias("AU", "AUSTRALIA", "AUSTRALIE")
        alias("RU", "RUSSIA", "RUSSIE")
        alias("NZ", "NEWZEALAND", "NOUVELLEZELANDE")
        alias("SE", "SWEDEN", "SUEDE")
        alias("NO", "NORWAY", "NORVEGE")
        alias("DK", "DENMARK", "DANEMARK")
        alias("FI", "FINLAND", "FINLANDE")
        // "IR" is ISO's code for Iran, but real IPTV bouquets overwhelmingly use it for Ireland
        // instead (Iran doesn't appear in this catalogue's real provider data at all) — the same
        // "go with what real bouquets actually mean" call already made for "AR" above.
        alias("IE", "IRELAND", "IRLANDE", "IR")
        alias("AT", "AUSTRIA", "AUTRICHE")
        alias("HU", "HUNGARY", "HONGRIE")
        alias("BG", "BULGARIA", "BULGARIE")
        alias("HR", "CROATIA", "CROATIE")
        alias("RS", "SERBIA", "SERBIE")
        alias("UA", "UKRAINE")
        alias("SA", "SAUDIARABIA", "ARABIESAOUDITE")
        alias("AE", "UAE", "EMIRATES", "EMIRATSARABESUNIS")
        alias("EG", "EGYPT", "EGYPTE")
        alias("QA", "QATAR")
        alias("JP", "JAPAN", "JAPON")
        alias("KR", "SOUTHKOREA", "COREEDUSUD", "COREE")
        // Real providers spell this pseudo-region's tag both ways — "AF|" on VOD/series, "AFR|" on
        // live TV, seen from the same catalogue — so both need to land on the same code.
        alias("AF", "AFRICA", "AFRIQUE", "AFR")
    }

    private fun normalizeKey(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return decomposed.filter { it.isLetterOrDigit() }.uppercase()
    }

    /** `🇫🇷` -> `"FR"`: a flag emoji is a pair of regional-indicator codepoints, A-Z each. */
    private fun codeFromFlagEmoji(text: String): String? {
        val indicators = text.codePoints().toArray()
            .filter { it in 0x1F1E6..0x1F1FF }
        if (indicators.size != 2) return null
        return indicators.joinToString("") { ('A' + (it - 0x1F1E6)).toString() }
    }

    /**
     * Resolves [text] to a [Country] only when, once cleaned, it names a country outright — never
     * a substring or partial match, so a bouquet like `"France Sport"` or `"General FR"` is left
     * for the caller's existing text-based grouping instead of being folded into plain `"France"`.
     */
    fun resolve(text: String): Country? {
        if (text.isBlank()) return null
        codeFromFlagEmoji(text)?.let { code -> DISPLAY_NAMES[code]?.let { return Country(code, it) } }
        val code = ALIASES[normalizeKey(text)] ?: return null
        return Country(code, DISPLAY_NAMES.getValue(code))
    }
}
