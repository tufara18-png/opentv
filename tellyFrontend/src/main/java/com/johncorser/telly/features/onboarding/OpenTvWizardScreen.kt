package com.johncorser.telly.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.johncorser.telly.R
import com.johncorser.telly.core.design.TELLY_GUIDANCE_PANE
import com.johncorser.telly.core.design.TELLY_ONBOARDING_BACKGROUND
import com.johncorser.telly.core.design.TELLY_TEXT_GUIDANCE_MUTED
import com.johncorser.telly.core.design.TELLY_TEXT_PRIMARY
import com.johncorser.telly.core.ui.ScreenCrossfade
import com.johncorser.telly.features.playlist.PlaylistRepository
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Telly-native onboarding with OpenTV-backed Xtream and Stalker support.
 * M3U keeps the upstream Telly flow unchanged.
 */
@Composable
fun WizardScreen(
    repository: PlaylistRepository,
    fetchPlaylist: suspend (String) -> String,
    onExit: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { AddPlaylistViewModel(scope, fetchPlaylist, repository) }
    val state by viewModel.state.collectAsState()

    var providerType by remember { mutableStateOf<PlaylistType?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var providerError by remember { mutableStateOf<String?>(null) }

    fun backProvider() {
        if (busy) return
        providerType = null
        name = ""
        url = ""
        username = ""
        password = ""
        mac = ""
        providerError = null
    }

    BackHandler {
        if (providerType != null) {
            backProvider()
        } else if (!viewModel.back()) {
            onExit()
        }
    }

    LaunchedEffect(state.step) {
        if (state.step == WizardStep.DONE) onComplete()
    }

    if (providerType != null) {
        ProviderWizardScreen(
            type = providerType!!,
            name = name,
            url = url,
            username = username,
            password = password,
            mac = mac,
            busy = busy,
            error = providerError,
            onName = { name = it },
            onUrl = { url = it },
            onUsername = { username = it },
            onPassword = { password = it },
            onMac = { mac = it },
            onBack = ::backProvider,
            onSubmit = {
                if (busy) return@ProviderWizardScreen
                busy = true
                providerError = null
                scope.launch {
                    val kind =
                        if (providerType == PlaylistType.XTREAM_CODES) {
                            OpenTvProviderKind.XTREAM
                        } else {
                            OpenTvProviderKind.STALKER
                        }
                    val result =
                        OpenTvProviderBridge.add(
                            OpenTvProviderDraft(
                                kind = kind,
                                name = name.trim(),
                                url = url.trim(),
                                username = username.trim(),
                                password = password,
                                macAddress = mac.trim(),
                            ),
                        )
                    result
                        .onSuccess { onComplete() }
                        .onFailure {
                            providerError = it.message ?: "Could not connect to provider"
                            busy = false
                        }
                }
            },
        )
        return
    }

    ScreenCrossfade(
        target = state.step,
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(TELLY_ONBOARDING_BACKGROUND)),
    ) { step ->
        Row(Modifier.fillMaxSize()) {
            WizardScreenGuidance(state.copy(step = step))
            when (step) {
                WizardStep.TYPE_CHOOSER ->
                    WizardScreenTypeStep(
                        onChoose = { type ->
                            when (type) {
                                PlaylistType.M3U -> viewModel.chooseType(type)
                                PlaylistType.XTREAM_CODES, PlaylistType.STALKER_PORTAL -> {
                                    providerType = type
                                    providerError = null
                                }
                            }
                        },
                        onCancel = onExit,
                        modifier = Modifier.weight(1f),
                    )
                WizardStep.URL_ENTRY ->
                    WizardScreenUrlStep(
                        url = state.url,
                        error = state.error,
                        onUrlChange = viewModel::setUrl,
                        onNext = {
                            val detected = detectXtreamGetPhp(state.url)
                            if (detected != null) {
                                providerType = PlaylistType.XTREAM_CODES
                                url = detected.baseUrl
                                username = detected.username
                                password = detected.password
                                providerError = null
                            } else {
                                viewModel.submitUrl()
                            }
                        },
                        onBack = { if (!viewModel.back()) onExit() },
                        modifier = Modifier.weight(1f),
                    )
                WizardStep.PROCESSING -> WizardScreenProcessing(Modifier.weight(1f))
                WizardStep.EPG_URL ->
                    WizardScreenEpgStep(
                        epgUrl = state.epgUrl,
                        error = state.error,
                        onEpgUrlChange = viewModel::setEpgUrl,
                        onPastePlaylistUrl = viewModel::pastePlaylistUrl,
                        onDone = viewModel::finishEpg,
                        onBack = { if (!viewModel.back()) onExit() },
                        modifier = Modifier.weight(1f),
                    )
                else ->
                    WizardScreenNameStep(
                        name = state.name,
                        kind = state.kind,
                        onNameChange = viewModel::setName,
                        onKindChange = viewModel::chooseKind,
                        onNext = viewModel::confirm,
                        onBack = { if (!viewModel.back()) onExit() },
                        modifier = Modifier.weight(1f),
                    )
            }
        }
    }
}

@Composable
private fun ProviderWizardScreen(
    type: PlaylistType,
    name: String,
    url: String,
    username: String,
    password: String,
    mac: String,
    busy: Boolean,
    error: String?,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMac: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val canSubmit =
        url.isNotBlank() &&
            when (type) {
                PlaylistType.XTREAM_CODES -> username.isNotBlank() && password.isNotBlank()
                PlaylistType.STALKER_PORTAL -> mac.isNotBlank()
                PlaylistType.M3U -> false
            }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color(TELLY_ONBOARDING_BACKGROUND)),
    ) {
        ProviderGuidance(type = type, busy = busy)
        WizardScreenActionsPane(Modifier.weight(1f)) {
            WizardScreenFieldEditor(
                label = "Playlist name",
                value = name,
                onValueChange = onName,
                onCommit = {},
                modifier = Modifier.width(WizardScreenDims.actionWidth),
            )
            WizardScreenFieldEditor(
                label = if (type == PlaylistType.XTREAM_CODES) "Server address" else "Portal URL",
                value = url,
                onValueChange = onUrl,
                onCommit = {},
                modifier = Modifier.width(WizardScreenDims.actionWidth),
                uriInput = true,
            )
            if (type == PlaylistType.XTREAM_CODES) {
                WizardScreenFieldEditor(
                    label = "Username",
                    value = username,
                    onValueChange = onUsername,
                    onCommit = {},
                    modifier = Modifier.width(WizardScreenDims.actionWidth),
                )
                WizardScreenFieldEditor(
                    label = "Password",
                    value = password,
                    onValueChange = onPassword,
                    onCommit = {},
                    modifier = Modifier.width(WizardScreenDims.actionWidth),
                )
            } else {
                WizardScreenFieldEditor(
                    label = "MAC address",
                    value = mac,
                    onValueChange = onMac,
                    onCommit = {},
                    modifier = Modifier.width(WizardScreenDims.actionWidth),
                )
            }
            error?.let {
                Text(
                    text = it,
                    color = Color(TELLY_TEXT_GUIDANCE_MUTED),
                    fontSize = 13.sp,
                    modifier = Modifier.width(WizardScreenDims.actionWidth).padding(top = 8.dp),
                )
            }
        }
        WizardScreenButtonsPane {
            WizardScreenActionRow(
                text = if (busy) "Connecting..." else stringResource(R.string.wizard_next),
                onClick = onSubmit,
                enabled = canSubmit && !busy,
                modifier = Modifier.width(WizardScreenDims.buttonWidth),
                trailingIcon = if (busy) null else painterResource(R.drawable.ic_wizard_next),
            )
            WizardScreenActionRow(
                text = stringResource(R.string.wizard_back),
                onClick = onBack,
                enabled = !busy,
                modifier = Modifier.width(WizardScreenDims.buttonWidth),
            )
        }
    }
}

@Composable
private fun ProviderGuidance(
    type: PlaylistType,
    busy: Boolean,
) {
    val title =
        when (type) {
            PlaylistType.XTREAM_CODES -> "Xtream Codes"
            PlaylistType.STALKER_PORTAL -> "Stalker Portal"
            PlaylistType.M3U -> "M3U playlist"
        }
    Row(
        modifier =
            Modifier
                .width(WizardScreenDims.guidanceWidth)
                .fillMaxHeight()
                .background(Color(TELLY_GUIDANCE_PANE))
                .padding(start = 56.dp, top = 152.dp),
    ) {
        Icon(
            painter = painterResource(if (busy) R.drawable.ic_wizard_download else R.drawable.ic_wizard_link),
            contentDescription = null,
            tint = Color(TELLY_TEXT_PRIMARY),
            modifier = Modifier.size(128.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.width(198.dp).padding(top = 28.dp)) {
            Text(
                text = title,
                color = Color(TELLY_TEXT_PRIMARY),
                fontSize = 36.sp,
                lineHeight = 48.sp,
                letterSpacing = (-0.01).em,
            )
            Text(
                text = if (busy) "Connecting and loading channels..." else "Enter the details from your IPTV provider",
                color = Color(TELLY_TEXT_GUIDANCE_MUTED),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}


private data class DetectedXtream(
    val baseUrl: String,
    val username: String,
    val password: String,
)

private fun detectXtreamGetPhp(raw: String): DetectedXtream? {
    val value = raw.trim()
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val path = uri.path.orEmpty()
    if (!path.endsWith("/get.php", ignoreCase = true) && !path.equals("get.php", ignoreCase = true)) {
        return null
    }

    val params =
        uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.isEmpty()) return@mapNotNull null
                val key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name())
                val decoded =
                    URLDecoder.decode(
                        pieces.getOrElse(1) { "" },
                        StandardCharsets.UTF_8.name(),
                    )
                key to decoded
            }
            ?.toMap()
            .orEmpty()

    val username = params["username"]?.takeIf { it.isNotBlank() } ?: return null
    val password = params["password"]?.takeIf { it.isNotBlank() } ?: return null
    val scheme = uri.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: return null
    val host = uri.host ?: return null
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return DetectedXtream(
        baseUrl = "$scheme://$host$port",
        username = username,
        password = password,
    )
}
