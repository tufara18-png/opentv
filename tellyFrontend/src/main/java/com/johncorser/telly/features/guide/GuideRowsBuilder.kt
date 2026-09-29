package com.johncorser.telly.features.guide

import com.johncorser.telly.features.epg.db.ProgramEntity
import com.johncorser.telly.features.panel.PanelRows
import com.johncorser.telly.features.panel.PanelViewModel

object GuideRowsBuilder {
    private const val VERTICAL_PREFETCH_ROWS = 4

    fun build(
        input: GuideRowsInput,
        programs: List<ProgramEntity>,
    ): List<GuideRow> {
        val byTvgId = programs.groupBy { it.channelTvgId }
        val groupChannels = PanelRows.channelsIn(input.channels, input.group, input.custom)
        val from = (input.firstVisibleRow - VERTICAL_PREFETCH_ROWS).coerceAtLeast(0)
        val to =
            (input.firstVisibleRow + input.visibleRowCount + VERTICAL_PREFETCH_ROWS)
                .coerceAtMost(groupChannels.size)

        return groupChannels.mapIndexed { index, channel ->
            val materializeCells = index in from until to
            GuideRow(
                channel = channel,
                displayNumber = if (input.group == PanelViewModel.ALL_CHANNELS) channel.number else index + 1,
                cells =
                    if (materializeCells) {
                        GuideCellsBuilder.build(byTvgId[channel.epgId].orEmpty(), input.span)
                    } else {
                        emptyList()
                    },
            )
        }
    }
}
