/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Immutable
data class TvShellDestination(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

object TvShellDestinations {
    val live = TvShellDestination("live", "Live", Icons.Default.LiveTv)
    val guide = TvShellDestination("guide", "Guide", Icons.Default.Tv)
    val movies = TvShellDestination("movies", "Movies", Icons.Default.Movie)
    val series = TvShellDestination("series", "Series", Icons.Default.VideoLibrary)
    val search = TvShellDestination("search", "Search", Icons.Default.Search)
    val settings = TvShellDestination("settings", "Settings", Icons.Default.Settings)

    val default = listOf(live, guide, movies, series, search, settings)
}

/**
 * Remote-first application shell.
 *
 * It deliberately owns navigation/focus presentation only. Channel ordering, quality grouping,
 * EPG, VOD canonicalization and playback stay in the existing OpenTV domain/data layers.
 */
@Composable
fun TvShellScaffold(
    selectedId: String,
    onDestinationSelected: (TvShellDestination) -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<TvShellDestination> = TvShellDestinations.default,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        TvShellRail(
            destinations = destinations,
            selectedId = selectedId,
            onDestinationSelected = onDestinationSelected,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}

@Composable
private fun TvShellRail(
    destinations: List<TvShellDestination>,
    selectedId: String,
    onDestinationSelected: (TvShellDestination) -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val width = if (railFocused) 220.dp else 76.dp

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 20.dp, horizontal = 10.dp)
            .onFocusChanged { railFocused = it.hasFocus },
    ) {
        destinations.forEach { destination ->
            val selected = destination.id == selectedId

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .selectable(
                        selected = selected,
                        onClick = { onDestinationSelected(destination) },
                        role = Role.Tab,
                    )
                    .focusable()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.label,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                if (railFocused) {
                    Text(
                        text = destination.label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}
