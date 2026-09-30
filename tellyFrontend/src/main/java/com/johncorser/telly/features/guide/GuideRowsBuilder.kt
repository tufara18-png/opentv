package com.johncorser.telly.features.guide

import com.johncorser.telly.features.epg.db.ProgramEntity
import com.johncorser.telly.features.panel.PanelViewModel
import com.johncorser.telly.features.playlist.db.ChannelEntity

internal data class GuideRowsState(
    val rows: List<GuideRow>,
    val channels: List<ChannelEntity>,
    val group: String,
    val span: GuideSpan?,
    val materializedFrom: Int,
    val materializedTo: Int,
) {
    companion object {
        val EMPTY = GuideRowsState(emptyList(), emptyList(), "", null, 0, 0)
    }
}

object GuideRowsBuilder {
    private const val VERTICAL_PREFETCH_ROWS = 4

    fun update(
        previous: GuideRowsState,
        input: GuideRowsInput,
        programs: List<ProgramEntity>,
    ): GuideRowsState {
        val from = (input.firstVisibleRow - VERTICAL_PREFETCH_ROWS).coerceAtLeast(0)
        val to =
            (input.firstVisibleRow + input.visibleRowCount + VERTICAL_PREFETCH_ROWS)
                .coerceAtMost(input.channels.size)

        val sameCatalogue =
            previous.channels === input.channels &&
                previous.group == input.group &&
                previous.rows.size == input.channels.size

        val rows =
            if (sameCatalogue) {
                previous.rows.toMutableList()
            } else {
                input.channels.mapIndexed { index, channel ->
                    GuideRow(
                        channel = channel,
                        displayNumber =
                            if (input.group == PanelViewModel.ALL_CHANNELS) channel.number else index + 1,
                        cells = emptyList(),
                    )
                }.toMutableList()
            }

        if (sameCatalogue) {
            for (index in previous.materializedFrom until previous.materializedTo) {
                if (index !in from until to && index in rows.indices && rows[index].cells.isNotEmpty()) {
                    rows[index] = rows[index].copy(cells = emptyList())
                }
            }
        }

        val byTvgId = programs.groupBy { it.channelTvgId }
        for (index in from until to) {
            if (index !in rows.indices) continue
            val current = rows[index]
            rows[index] =
                current.copy(
                    cells = GuideCellsBuilder.build(
                        byTvgId[current.channel.epgId].orEmpty(),
                        input.span,
                    ),
                )
        }

        return GuideRowsState(
            rows = rows,
            channels = input.channels,
            group = input.group,
            span = input.span,
            materializedFrom = from,
            materializedTo = to,
        )
    }
}
