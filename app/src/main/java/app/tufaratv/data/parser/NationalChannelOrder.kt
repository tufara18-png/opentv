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
     *
     * Exact match first, then falls back to the longest listed entry [groupKey] *starts with* —
     * confirmed necessary against a real provider's Canada bouquet, whose channels are regional
     * feeds of the network, not the bare network name: `"CBC News"`, `"CBC Montreal"`, `"CBC
     * Toronto"` (groupKeys `cbcnews`, `cbcmontreal`, `cbctoronto`…) never equal the curated bare
     * `"cbc"` entry, so nothing matched at all and the shelf silently fell back to the provider's
     * raw, effectively random order. A prefix match co-locates every regional feed of one network
     * at that network's position — the same "can only add a match, never a wrong one" safety as
     * everywhere else here, since these are short, well-known network call signs/brand names, not
     * generic words a channel could coincidentally start with.
     */
    fun rank(countryCode: String, groupKey: String): Int? {
        val order = ORDER[countryCode] ?: return null
        order.indexOf(groupKey).takeIf { it >= 0 }?.let { return it }
        return order.indices
            .filter { groupKey.startsWith(order[it]) }
            .maxByOrNull { order[it].length }
    }

    /**
     * Well-known Québec broadcasters and cable specialty channels, by [ChannelNameNormalizer]
     * group key — the signal Canada's Québec-first tiering (see [canadaTier]) actually keys on.
     * Matched by name rather than by the provider's own category tag on purpose: real catalogues
     * are wildly inconsistent about tagging Québec separately from English Canada at all (one
     * provider's whole Canadian lineup showed up as a single untagged "CANADA" bouquet, no `QC`
     * sub-category anywhere) — the way [ORDER] already has to work for every country's named
     * lineup, not just Canada. An entry that doesn't match a given provider's exact spelling
     * simply never matches (same reasoning as [rank]), so an incomplete list can't make ordering
     * worse than treating everything as English Canada already was.
     */
    // "casa", "historia", "explora", bare "series" and bare "artv" deliberately left out even
    // though they're real Québec channel names too — now that this is checked cross-shelf against
    // every OTHER country's categories as well (see OpenTvViewModels' Québec-reroute filter), a
    // generic word like "Historia" or "Casa" is a real Spanish/Italian channel brand too, and a
    // false hit here wouldn't just mis-order the Canada shelf, it would steal that channel off its
    // real one. Kept to the names distinctive enough that a collision is implausible.
    private val QUEBEC_CHANNELS = setOf(
        "radiocanada", "ici", "icitele", "iciradiocanadatele", "tva", "tvasports", "tvasport",
        "noovo", "telequebec", "rds", "rds2", "rdsinfo", "canalvie",
        "yoopa", "vrak", "unistv", "unis", "moietcie", "seriesplus",
        "iciexplora", "iciartv", "canald", "canalsavoir", "lcn",
        "addiktv", "ztele", "cinepop", "evasion", "musiqueplus", "matv", "prise2",
    )

    /**
     * Whether this channel is a known Québec broadcaster/specialty channel — see
     * [QUEBEC_CHANNELS]. Exact match first, then the same longest-prefix fallback as [rank] and
     * for the same confirmed-real reason: a provider's regional feeds — `"TVA Montreal"`, `"TVA
     * Sherbrooke"`, `"ICI Tele Est-Quebec"`, `"Noovo Sherbrooke"` — never equal the bare curated
     * name, so before this fallback most of a real Québec lineup silently failed to be recognised
     * as Québec at all.
     */
    fun isQuebecChannel(groupKey: String): Boolean =
        groupKey in QUEBEC_CHANNELS || QUEBEC_CHANNELS.any { groupKey.startsWith(it) }

    /**
     * Canada's second ordering axis, ahead of the shared General/Sport/Cinema/… type tiers:
     * Québec first — general and specialized alike, since the type tier still sub-orders within
     * it — then English-Canada sport, then the rest of English-Canada. Requested explicitly,
     * distinct from every other country here, which only ever gets the type-tier-then-named-
     * lineup ordering [rank] provides. [isQuebec] is true when either the channel matched
     * [QUEBEC_CHANNELS] by name or its own category was specifically Québec-tagged (`QC`) —
     * belt and suspenders, since `CountryResolver` folds both Québec and English-Canada bouquets
     * to the same "CA" group code and a given provider might only give a reliable signal on one
     * side or the other.
     */
    fun canadaTier(isQuebec: Boolean, typeRank: Int): Int = when {
        isQuebec -> 0
        typeRank == CategoryContentType.SPORT.rank -> 1
        else -> 2
    }

    /** Every known network brand for [countryCode] — [ORDER]'s own list, plus [QUEBEC_CHANNELS]
     *  too when it's Canada, since a Québécois network is just as real a Canadian brand as an
     *  English one. Used by [networkKey] to consolidate a network's regional repeaters down to
     *  one feed. */
    private fun networkBrands(countryCode: String): List<String> {
        val base = ORDER[countryCode].orEmpty()
        return if (countryCode == "CA") (base + QUEBEC_CHANNELS).distinct() else base
    }

    /**
     * The network brand a channel's [groupKey] belongs to (`"cbc"`, `"tva"`, `"tf1"`…) within
     * [countryCode]'s own curated [ORDER] list, longest match wins, or null when [countryCode]
     * has no curated list at all or the channel isn't a recognised brand — every country here
     * gets this for free the moment it has a named lineup, not just Canada. Confirmed necessary
     * against a real Canadian provider: a single network ships one channel per city — `"CBC
     * Montreal"`, `"CBC Toronto"`, `"CBC Ottawa"`, and likewise for CTV, TVA, ICI/Radio-Canada —
     * a dozen-plus near-duplicate entries per network cluttering the shelf, not distinct channels
     * a viewer wants a choice between the way a Quality switch is; the same regional-repeater
     * pattern is just as real for any other country's network. [OpenTvViewModels]' consolidation
     * step groups by this and keeps only one regional feed per network (see its own doc comment
     * for which one).
     */
    fun networkKey(countryCode: String, groupKey: String): String? =
        networkBrands(countryCode)
            .filter { groupKey == it || groupKey.startsWith(it) }
            .maxByOrNull { it.length }
}
