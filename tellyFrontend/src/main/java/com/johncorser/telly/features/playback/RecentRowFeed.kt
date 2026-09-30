package com.johncorser.telly.features.playback

import com.johncorser.telly.features.epg.EpgRepository
import com.johncorser.telly.features.epg.NowNext
import com.johncorser.telly.features.epg.ProgramTitle
import com.johncorser.telly.features.history.WatchHistory
import com.johncorser.telly.features.playlist.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RecentRowFeed(
    sources: RecentRowSources,
    private val epgRepository: EpgRepository,
    private val style: ClockStyle,
    scope: CoroutineScope,
) {
    private val channelsByKey =
        sources.channels
            .map { channels -> channels.associateBy(WatchHistory::identityOf) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val cards: StateFlow<List<RecentCard>> =
        combine(channelsByKey, sources.events, sources.current, sources.instant) { byKey, events, current, at ->
            val recent =
                events
                    .mapNotNull { event -> byKey[event.channelKey] }
                    .filter { it.id != current?.id }
            recent to at
        }.flatMapLatest { (recent, at) ->
            epgRepository
                .nowNext(recent.mapNotNull { it.epgId }, at)
                .map { guide -> build(recent, guide) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun build(
        recent: List<ChannelEntity>,
        guide: Map<String, NowNext>,
    ): List<RecentCard> =
        recent.map { channel ->
            val now = guide[channel.epgId]?.now
            RecentCard(
                channel = channel,
                nowTitle = now?.details?.let(ProgramTitle::of),
                nowRange = now?.let { ProgramTimes.range(it.startMs, it.endMs, style) },
            )
        }
}
