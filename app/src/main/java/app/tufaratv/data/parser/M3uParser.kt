/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.data.parser

import app.tufaratv.data.model.Channel
import app.tufaratv.data.model.Episode
import app.tufaratv.data.model.Movie
import app.tufaratv.data.model.Series
import java.io.BufferedReader
import java.io.InputStream

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * A provider-facing "M3U" is often actually an Xtream m3u_plus export containing live TV,
 * /movie/ VOD and /series/ episode URLs in the same file. Parse the file once, line-by-line, and
 * split those strong URL shapes into the same domain models the native Xtream/Stalker paths use.
 * Ambiguous entries stay LIVE. This is deliberately conservative: a group called "Cinema" is not
 * enough to turn a linear cinema channel into a Movie.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")
    private val EPISODE_MARKER = Regex("""(?i)\bS(\d{1,3})\s*E(\d{1,4})\b""")

    data class Result(
        val channels: List<Channel>,
        val movies: List<Movie>,
        val series: List<Series>,
        val episodes: List<Episode>,
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result =
        input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result =
        parse(text.reader().buffered(), sourceId)

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val episodes = ArrayList<Episode>()
        val seriesById = LinkedHashMap<String, Series>()
        val seenStreamUrls = HashSet<String>()
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        var liveIndex = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        skipped++
                    } else if (seenStreamUrls.add(line)) {
                        val attributes = pendingAttributes
                        val group = attributes["group-title"]?.takeIf { it.isNotBlank() }
                        val logo = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() }
                        val urlLower = line.lowercase()

                        when {
                            "/series/" in urlLower -> {
                                val descriptor = episodeDescriptor(name)
                                if (descriptor == null) {
                                    channels += channelOf(
                                        sourceId, name, line, attributes, group, logo, pendingNumber, liveIndex++,
                                    )
                                } else {
                                    val seriesId = "m3u-series:${stableHash(descriptor.seriesTitle.lowercase())}"
                                    seriesById.putIfAbsent(
                                        seriesId,
                                        Series(
                                            sourceId = sourceId,
                                            seriesId = seriesId,
                                            name = descriptor.seriesTitle,
                                            categoryId = group,
                                            posterUrl = logo,
                                            rating = null,
                                            year = VodTitleCleaner.inferReleaseYear(descriptor.seriesTitle),
                                            plot = null,
                                        ),
                                    )
                                    episodes += Episode(
                                        sourceId = sourceId,
                                        seriesId = seriesId,
                                        episodeId = "url:${stableHash(line)}",
                                        season = descriptor.season,
                                        episodeNumber = descriptor.episode,
                                        title = descriptor.episodeTitle,
                                        plot = null,
                                        durationSeconds = null,
                                        stillUrl = logo,
                                        streamUrl = line,
                                    )
                                }
                            }
                            "/movie/" in urlLower -> {
                                val extension = line.substringBefore('?')
                                    .substringAfterLast('.', missingDelimiterValue = "")
                                    .takeIf { it.length in 2..5 }
                                movies += Movie(
                                    sourceId = sourceId,
                                    streamId = "url:${stableHash(line)}",
                                    name = name,
                                    categoryId = group,
                                    posterUrl = logo,
                                    rating = null,
                                    year = VodTitleCleaner.inferReleaseYear(name),
                                    plot = null,
                                    durationSeconds = null,
                                    containerExtension = extension,
                                    streamUrl = line,
                                )
                            }
                            else -> channels += channelOf(
                                sourceId, name, line, attributes, group, logo, pendingNumber, liveIndex++,
                            )
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        return Result(
            channels = channels,
            movies = movies,
            series = seriesById.values.toList(),
            episodes = episodes,
            declaredEpgUrl = declaredEpgUrl,
            skippedEntries = skipped,
        )
    }

    private fun channelOf(
        sourceId: Long,
        name: String,
        url: String,
        attributes: Map<String, String>,
        group: String?,
        logo: String?,
        number: Int?,
        sortIndex: Int,
    ): Channel {
        val tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
        val streamId = tvgId?.let { "tvg:$it:url:${stableHash(url)}" } ?: "url:${stableHash(url)}"
        return Channel(
            sourceId = sourceId,
            streamId = streamId,
            name = name,
            categoryId = group,
            logoUrl = logo,
            epgChannelId = tvgId,
            number = number,
            streamUrl = url,
            sortIndex = sortIndex,
        )
    }

    private data class EpisodeDescriptor(
        val seriesTitle: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String,
    )

    private fun episodeDescriptor(raw: String): EpisodeDescriptor? {
        val marker = EPISODE_MARKER.find(raw) ?: return null
        val season = marker.groupValues[1].toIntOrNull() ?: return null
        val episode = marker.groupValues[2].toIntOrNull() ?: return null
        val before = raw.substring(0, marker.range.first)
            .trim(' ', '-', '|', ':', '.', '_')
            .takeIf { it.isNotBlank() } ?: return null
        val after = raw.substring(marker.range.last + 1)
            .trim(' ', '-', '|', ':', '.', '_')
        return EpisodeDescriptor(
            seriesTitle = VodTitleCleaner.clean(before),
            season = season,
            episode = episode,
            episodeTitle = after.takeIf { it.isNotBlank() } ?: "Episode $episode",
        )
    }

    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }
}
