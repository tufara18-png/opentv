/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Telly/TiviMate-style fullscreen quick bar.
 *
 * It deliberately sits on top of the video rather than changing the player layout. Long-OK/MENU
 * opens it, D-pad moves horizontally, OK performs the action and BACK dismisses it.
 */
@Composable
internal fun TellyQuickBar(
    hasSchedule: Boolean,
    hasQualityVariants: Boolean,
    pipSupported: Boolean,
    recording: Boolean,
    onChannels: () -> Unit,
    onSchedule: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onQuality: () -> Unit,
    onAspect: () -> Unit,
    onRecord: () -> Unit,
    onPip: () -> Unit,
) {
    data class Item(
        val label: String,
        val icon: ImageVector,
        val enabled: Boolean = true,
        val selected: Boolean = false,
        val action: () -> Unit,
    )

    val items = buildList {
        add(Item("Chaînes", Icons.Filled.List, action = onChannels))
        add(Item("Programme", Icons.Filled.Schedule, enabled = hasSchedule, action = onSchedule))
        add(Item("Sous-titres", Icons.Filled.Subtitles, action = onSubtitles))
        add(Item("Audio", Icons.Filled.Audiotrack, action = onAudio))
        add(Item("Qualité", Icons.Filled.HighQuality, enabled = hasQualityVariants, action = onQuality))
        add(Item("Format", Icons.Filled.AspectRatio, action = onAspect))
        add(Item(if (recording) "Arrêter" else "Enregistrer", Icons.Filled.FiberManualRecord, selected = recording, action = onRecord))
        if (pipSupported) add(Item("PiP", Icons.Filled.PictureInPictureAlt, action = onPip))
    }

    val firstFocus = FocusRequester()
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
                ),
            )
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEachIndexed { index, item ->
                QuickBarSlot(
                    label = item.label,
                    icon = item.icon,
                    enabled = item.enabled,
                    selected = item.selected,
                    onClick = item.action,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun QuickBarSlot(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    Column(
        modifier
            .focusable(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Black.copy(alpha = 0.42f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
