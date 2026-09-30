package com.johncorser.telly.features.guide

import com.johncorser.telly.features.epg.db.ProgramEntity
import com.johncorser.telly.features.groups.CustomGroup
import com.johncorser.telly.features.panel.PanelRows
import com.johncorser.telly.features.playback.PlaybackEnv
import com.johncorser.telly.features.playlist.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

class GuideRowsFeed(
    sources: GuideRowsSources,
    scrollX: StateFlow<Float>,
    firstVisibleRow: StateFlow<Int>,
    private val visibleRows: () -> Int,
    private val programsFor: (List<String>, Long, Long) -> Flow<List<ProgramEntity>>,
    private val originMs: Long,
    scope: CoroutineScope,
) {
    private val span =
        scrollX
            .map { GuideWindowMath.materializeSpan(originMs, it, GuideGeometry.TIME_VIEWPORT_DP) }
            .distinctUntilChanged()

    private val verticalWindow =
        firstVisibleRow
            .map { first -> first to visibleRows().coerceAtLeast(1) }
            .distinctUntilChanged()

    private val groupChannels: StateFlow<List<ChannelEntity>> =
        combine(sources.channels, sources.customGroups, sources.selectedGroup) { channels, custom, group ->
            PanelRows.channelsIn(channels, group, custom)
        }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<GuideRow>> =
        combine(
            groupChannels,
            sources.selectedGroup,
            span,
            verticalWindow,
        ) { channels, group, window, vertical ->
            GuideRowsInput(
                channels = channels,
                group = group,
                span = window,
                firstVisibleRow = vertical.first,
                visibleRowCount = vertical.second,
            )
        }.flatMapLatest { input ->
            val from = (input.firstVisibleRow - VERTICAL_PREFETCH_ROWS).coerceAtLeast(0)
            val to =
                (input.firstVisibleRow + input.visibleRowCount + VERTICAL_PREFETCH_ROWS)
                    .coerceAtMost(input.channels.size)
            val epgIds =
                if (from < to) {
                    input.channels.subList(from, to).mapNotNull { it.epgId }
                } else {
                    emptyList()
                }
            programsFor(epgIds, input.span.fromMs, input.span.toMs)
                .map { programs -> GuideRowsPayload(input, programs) }
        }
            .scan(GuideRowsState.EMPTY) { state, payload ->
                GuideRowsBuilder.update(state, payload.input, payload.programs)
            }
            .map { it.rows }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<String>> =
        combine(sources.channels, sources.customGroups, PanelRows::groupNames)
            .stateIn(scope, SharingStarted.Eagerly, PanelRows.groupNames(emptyList()))

    private companion object {
        const val VERTICAL_PREFETCH_ROWS = 4
    }
}

internal fun guideRowsFeed(
    env: PlaybackEnv,
    sources: GuideRowsSources,
    scrollX: StateFlow<Float>,
    firstVisibleRow: StateFlow<Int>,
    visibleRows: () -> Int,
    originMs: Long,
    scope: CoroutineScope,
): GuideRowsFeed =
    GuideRowsFeed(
        sources = sources,
        scrollX = scrollX,
        firstVisibleRow = firstVisibleRow,
        visibleRows = visibleRows,
        programsFor = env.epgRepository::programsFor,
        originMs = originMs,
        scope = scope,
    )

class GuideRowsSources(
    val channels: StateFlow<List<ChannelEntity>>,
    val selectedGroup: StateFlow<String>,
    val customGroups: StateFlow<List<CustomGroup>> = MutableStateFlow(emptyList()),
)

data class GuideRowsInput(
    val channels: List<ChannelEntity>,
    val group: String,
    val span: GuideSpan,
    val firstVisibleRow: Int = 0,
    val visibleRowCount: Int = Int.MAX_VALUE,
)

internal data class GuideRowsPayload(
    val input: GuideRowsInput,
    val programs: List<ProgramEntity>,
)
