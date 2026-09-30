package com.johncorser.telly.features.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.johncorser.telly.core.design.TELLY_NOW_LINE

/**
 * The virtualized grid: LazyColumn virtualizes channels vertically, each
 * row materializes only the cells the shared horizontal scroll exposes
 * (window math in [GuideCellLayout]); the timeline header and every row
 * pan in lockstep off the controller's single scroll offset. The thin
 * now-line runs down the grid at the sampled "now".
 */
@Composable
internal fun GuideScreenGrid(
    controller: GuideController,
    dimFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val rows by controller.rows.collectAsState()
    val focus by controller.focus.collectAsState()
    val scrollX by controller.scrollX.collectAsState()
    val firstRow by controller.firstVisibleRow.collectAsState()
    val preview by controller.preview.collectAsState()
    val now by controller.now.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(firstRow) { listState.scrollToItem(firstRow) }
    Box(modifier) {
        // The controller owns the vertical position (firstVisibleRow): with
        // user scrolling on, the list also exposes scroll semantics actions,
        // so any external scroll (accessibility/test scroll-to-node, a touch
        // fling) desyncs the list from the controller FOREVER — the
        // LaunchedEffect above only re-scrolls when firstRow CHANGES. The
        // grid renders no focusable rows, so scrolling is programmatic only.
        LazyColumn(state = listState, userScrollEnabled = false) {
            itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                GuideScreenRow(
                    row = row,
                    focusedCell = focus?.takeIf { it.rowIndex == index }?.cell,
                    playing = row.channel.id == preview?.id,
                    dimFocus = dimFocus,
                    scrollXDp = scrollX,
                    originMs = controller.originMs,
                    nowMs = now,
                )
            }
        }
        GuideScreenNowLine(controller, scrollX, now)
    }
}

@Composable
private fun GuideScreenNowLine(
    controller: GuideController,
    scrollX: Float,
    nowMs: Long,
) {
    val offset =
        GuideTimeline.nowLineOffset(
            nowMs,
            controller.originMs,
            scrollX,
            GuideGeometry.TIME_VIEWPORT_DP,
        ) ?: return
    Box(
        Modifier
            .padding(start = (GuideGeometry.CHANNEL_COLUMN_DP + offset).dp)
            .fillMaxHeight()
            .width(1.dp)
            .background(Color(TELLY_NOW_LINE)),
    )
}
