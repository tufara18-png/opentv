/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

/**
 * The real-world channel lineup order for a country's terrestrial/general channels — TF1 before
 * France 2 before France 3 before Canal+, the way an actual French remote's number pad reads it —
 * layered on top of [CategoryContentClassifier]'s General/Sport/Cinema/… tiers.
 *
 * The classifier alone sorts a merged country shelf into the right *tiers*, but within the
 * General tier it can only preserve whatever order the provider's own bouquet happened to list
 * channels in, which is usually neither alphabetical nor the order anyone actually expects — so
 * "TNT first" still came out as a random-looking shuffle. This is the fix: a small, explicit,
 * best-known-channels-first list per country, matched by [ChannelNameNormalizer.groupKeyOf] so it
 * survives however a given provider spelled the name.
 *
 * Seeded per country with that country's flagship public broadcaster and best-known private
 * networks — common knowledge, not a provider-specific guess — so it's safe to list broadly:
 * an entry that doesn't match a given provider's exact spelling of a channel simply never
 * matches anything (see [rank]) and that channel keeps its existing order, so a wrong or
 * incomplete guess can't make ordering worse than the classifier-only baseline. France's list is
 * the one actually verified against real provider data in this session; the others are the
 * equivalent well-known lineups for the countries this catalogue's categories cover. A country
 * missing here just keeps the classifier-only ordering it always had — extending it is one more
 * list, matched the same way.
 */
object NationalChannelOrder {

    private val ORDER: Map<String, List<String>> = mapOf(
        "FR" to listOf(
            "tf1", "france2", "france3", "canal", "france5", "m6", "arte", "c8", "w9",
            "tmc", "tfx", "nrj12", "lcp", "franceinfo", "france4", "bfmtv", "cnews", "lci",
            "cstar", "gulli", "tv5monde", "6ter", "equipe",
        ),
        "GB" to listOf("bbc1", "bbc2", "itv", "channel4", "channel5", "five", "skynews", "skyone"),
        "US" to listOf("abc", "cbs", "nbc", "fox", "cnn", "foxnews", "msnbc", "cnbc", "abcnews"),
        "DE" to listOf("ard", "zdf", "rtl", "sat1", "prosieben", "kabeleins", "vox", "rtl2"),
        "IT" to listOf("rai1", "rai2", "rai3", "canale5", "italia1", "rete4", "la7"),
        "ES" to listOf("la1", "la2", "antena3", "telecinco", "cuatro", "lasexta"),
        "PT" to listOf("rtp1", "rtp2", "sic", "tvi"),
        "NL" to listOf("npo1", "npo2", "npo3", "rtl4", "rtl5", "sbs6"),
        "BE" to listOf("rtbf", "vrt", "vtm", "laune", "rtlbelgium"),
        "CH" to listOf("rts", "srf", "rsi"),
        "CA" to listOf("cbc", "radiocanada", "ctv", "global", "tva"),
        "BR" to listOf("globo", "record", "sbt", "band"),
        "PL" to listOf("tvp1", "tvp2", "polsat", "tvn"),
        "RU" to listOf("channel1", "rossiya1", "ntv"),
        "GR" to listOf("ert1", "mega", "antenna", "star"),
        "TR" to listOf("trt1", "atv", "showtv", "kanald"),
        "RO" to listOf("tvr1", "tvr2", "prottv", "antena1"),
        "CZ" to listOf("ct1", "ct2", "nova", "prima"),
        "HU" to listOf("m1", "m2", "rtlklub", "tv2"),
        "AT" to listOf("orf1", "orf2"),
        "SE" to listOf("svt1", "svt2", "tv3", "tv4"),
        "NO" to listOf("nrk1", "nrk2", "tv2"),
        "DK" to listOf("dr1", "dr2", "tv2"),
        "FI" to listOf("yletv1", "yletv2", "mtv3", "nelonen"),
        "IE" to listOf("rte1", "rte2", "tv3", "virginmedia1"),
        "AU" to listOf("abc", "seven", "nine", "ten", "sbs"),
        "MX" to listOf("las2", "canal5", "azteca7", "azteca13"),
        "ARG" to listOf("telefe", "elnuevo", "america"),
        "IN" to listOf("dd1", "startv", "zeetv", "sonytv", "colors"),
        "UA" to listOf("ictv", "1plus1", "inter"),
    )

    /**
     * This channel's position in [countryCode]'s known lineup, or null when the country has no
     * curated list or the channel isn't a listed one — callers should keep such channels in their
     * existing relative order rather than treat null as "last", so an unlisted channel doesn't
     * jump around when the listed ones are reordered.
     */
    fun rank(countryCode: String, groupKey: String): Int? {
        val order = ORDER[countryCode] ?: return null
        val index = order.indexOf(groupKey)
        return index.takeIf { it >= 0 }
    }
}
