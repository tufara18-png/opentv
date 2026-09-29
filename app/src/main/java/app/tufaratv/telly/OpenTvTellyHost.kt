package app.tufaratv.telly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.johncorser.telly.RootScreen
import com.johncorser.telly.core.navigation.Navigator
import com.johncorser.telly.core.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-TV host for the real Telly frontend over OpenTV data.
 *
 * Telly owns the complete TV surface, including first-run onboarding.
 * OpenTV remains behind it as the source/catalogue/EPG backend.
 */
@Composable
fun OpenTvTellyHost(
    navigator: Navigator,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var graph by remember(context) { mutableStateOf<OpenTvTellyGraph?>(null) }

    LaunchedEffect(context) {
        val built =
            withContext(Dispatchers.Default) {
                OpenTvTellyGraph(context)
            }
        graph = built
        val hasSources =
            withContext(Dispatchers.IO) {
                built.hasSources()
            }
        navigator.replaceAll(if (hasSources) Route.Guide else Route.Welcome)
    }

    val ready = graph ?: return

    RootScreen(
        navigator = navigator,
        repository = ready.playlists,
        fetchPlaylist = ready::fetchPlaylist,
        playbackDeps = ready.playback,
        guideDeps = ready.guide,
        settingsGraph = ready.settings,
        searchDeps = ready.search,
        multiviewDeps = ready.multiview,
        vodDeps = ready.vod,
        onEnterPip = onEnterPip,
        reminders = ready.reminders,
        recordingDeps = ready.recording,
        onboardingRestore = null,
    )
}
