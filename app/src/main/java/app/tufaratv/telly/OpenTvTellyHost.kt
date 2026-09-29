package app.tufaratv.telly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.tufaratv.MainActivity
import com.johncorser.telly.RootScreen
import com.johncorser.telly.core.navigation.Navigator
import com.johncorser.telly.core.navigation.Route
import kotlinx.coroutines.flow.first

/**
 * Android-TV host for the real Telly frontend over OpenTV data.
 *
 * Telly owns the complete TV surface, including first-run onboarding.
 * OpenTV remains behind it as the source/catalogue/EPG backend.
 */
@Composable
fun OpenTvTellyHost() {
    val context = LocalContext.current
    val graph = remember(context.applicationContext) { OpenTvTellyGraph(context.applicationContext) }
    val navigator = remember { Navigator(start = Route.Boot) }

    LaunchedEffect(graph) {
        val hasSources = graph.playlists.playlists.first().isNotEmpty()
        navigator.replaceAll(if (hasSources) Route.Guide else Route.Welcome)
    }

    RootScreen(
        navigator = navigator,
        repository = graph.playlists,
        fetchPlaylist = graph::fetchPlaylist,
        playbackDeps = graph.playback,
        guideDeps = graph.guide,
        settingsGraph = graph.settings,
        searchDeps = graph.search,
        multiviewDeps = graph.multiview,
        vodDeps = graph.vod,
        onEnterPip = { (context as? MainActivity)?.enterPipNow() },
        reminders = graph.reminders,
        recordingDeps = graph.recording,
        onboardingRestore = null,
    )
}
