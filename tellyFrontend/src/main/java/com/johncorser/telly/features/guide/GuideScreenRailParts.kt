package com.johncorser.telly.features.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import com.johncorser.telly.R
import com.johncorser.telly.core.design.TELLY_TEXT_PRIMARY
import com.johncorser.telly.core.ui.FocusScreenDefaults

@Composable
internal fun GuideScreenRailLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_rail_tv),
        contentDescription = null,
        modifier = modifier.size(36.dp),
    )
}

@Composable
internal fun GuideScreenRailButton(
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    restingTint: Color = Color(TELLY_TEXT_PRIMARY),
    contentDescription: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = FocusScreenDefaults.shape(),
        scale = FocusScreenDefaults.scale(),
        colors = FocusScreenDefaults.colors(
            restingContainer = Color.Transparent,
            restingContent = restingTint,
        ),
    ) {
        GuideScreenRailIcon(icon, Color.Unspecified, Modifier.padding(8.dp), contentDescription)
    }
}

@Composable
internal fun GuideScreenRailIcon(
    icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}
