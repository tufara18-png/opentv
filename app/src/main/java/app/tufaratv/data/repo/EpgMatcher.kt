/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.repo

import app.tufaratv.data.parser.ChannelNameNormalizer
import java.text.Normalizer

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). The only
 * bridge between them is the name, so both sides are pushed through
 * [ChannelNameNormalizer.groupKeyOf] and matched on the result.
 *
 * ## The design rule: prefer no match to a wrong match
 *
 * A missing guide entry reads as "provider doesn't do EPG" and can be fixed with a manual
 * override. A *wrong* one shows EastEnders against a sports channel, and the user reasonably
 * concludes the whole guide is broken. So the fuzzy tier only accepts a prefix match when it
 * is **unambiguous** — exactly one guide channel fits. `bbcone` will claim `BBC One` when
 * that is the only candidate, but if the guide carries `BBC One London` *and* `BBC One
 * Wales` and nothing plain, we refuse to guess between regions.
 */
object EpgMatcher {

    /** One guide channel, pre-normalised. */
    data class Alias(
        val epgId: String,
        /** Normalised display-name or id: `bbcone`, `bbc1uk`. */
        val key: String,
    )

    class Index internal constructor(
        private val exact: Map<String, String>,
        private val fullKeyOwners: Map<String, String>,
        private val prefixOwners: Map<String, String>,
        private val providerIds: Map<String, List<Alias>>,
    ) {
        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { return it }

            // Prefix tier, resolved through prebuilt maps. This used to scan every guide alias
            // for every provider channel: 12k channels × 3k aliases was enough CPU work to make
            // playback stutter while a background refresh ran. Lookup is now bounded by the
            // channel-name length, independent of guide size.
            var found: String? = null
            fun accept(owner: String?): Boolean {
                if (owner == null) return true
                if (owner == AMBIGUOUS) return false
                if (found == null) found = owner else if (found != owner) return false
                return true
            }
            if (!accept(prefixOwners[groupKey])) return null
            for (length in MIN_KEY_LENGTH until groupKey.length) {
                if (!accept(fullKeyOwners[groupKey.substring(0, length)])) {
                    return null
                }
            }
            return found
        }

        /**
         * Matches the join id supplied by an IPTV provider against an XMLTV id.
         *
         * Provider ids are often abbreviated (`RDSHD`, `CFTM`) while public XMLTV feeds use
         * decorated ids (`Réseau.des.Sports.(RDS).HD.ca2`, `CFTM-DT.ca2`). Name matching cannot
         * bridge that gap. The provider id is stronger evidence than a display name, so accept a
         * compact containment match and prefer the shortest guide id when several share the same
         * station prefix. This makes `CFTM` choose `CFTM-DT` rather than an arbitrary distant
         * station, while still refusing ids shorter than two characters.
         */
        fun matchProviderId(providerId: String?): String? {
            val key = compact(providerId.orEmpty())
            if (key.length < MIN_KEY_LENGTH) return null

            val candidates = providerIds[key].orEmpty()
            val shortest = candidates.minOfOrNull { it.key.length } ?: return null
            return candidates
                .filter { it.key.length == shortest }
                .map(Alias::epgId)
                .distinct()
                .singleOrNull()
        }
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index {
        val exact = HashMap<String, String>()
        val fullKeyOwners = HashMap<String, String>()
        val prefixOwners = HashMap<String, String>()
        val providerIds = HashMap<String, MutableList<Alias>>()

        fun recordOwner(map: MutableMap<String, String>, key: String, epgId: String) {
            val previous = map[key]
            map[key] = when {
                previous == null || previous == epgId -> epgId
                else -> AMBIGUOUS
            }
        }

        for ((epgId, name) in aliases) {
            val rawKey = compact(epgId)
            val tokens = rawTokens(epgId)
            for (start in tokens.indices) {
                var joined = ""
                for (end in start until tokens.size) {
                    joined += tokens[end]
                    if (joined.length >= MIN_KEY_LENGTH) {
                        providerIds.getOrPut(joined) { mutableListOf() } += Alias(epgId, rawKey)
                    }
                }
            }
            for (candidate in listOf(name, epgId)) {
                // Full normalise, not bare groupKeyOf: free guides append 'HD' to display
                // names ('BBC One East HD'), which would otherwise never key-match a
                // provider's 'BBC ONE EAST'. normalize() strips the quality token.
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < MIN_KEY_LENGTH) continue
                recordOwner(fullKeyOwners, key, epgId)
                for (length in MIN_KEY_LENGTH..key.length) {
                    recordOwner(prefixOwners, key.substring(0, length), epgId)
                }
            }
        }
        fullKeyOwners.forEach { (key, owner) ->
            if (owner != AMBIGUOUS) exact[key] = owner
        }

        return Index(exact, fullKeyOwners, prefixOwners, providerIds)
    }

    private fun compact(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter(Char::isLetterOrDigit)

    private fun rawTokens(value: String): List<String> =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotEmpty)

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2
    private const val AMBIGUOUS = ""
}
