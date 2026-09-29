/*
 * OpenTV-backed dependency graph for the Telly frontend.
 */
package app.tufaratv.telly

import android.content.Context
import app.tufaratv.core.ServiceLocator as OpenTvServiceLocator
import com.johncorser.telly.core.ServiceLocator as TellyServiceLocator
import com.johncorser.telly.core.bridgedPlaybackDeps
import com.johncorser.telly.core.guideDeps
import com.johncorser.telly.features.guide.GuideDeps
import com.johncorser.telly.features.playback.PlaybackDeps

/**
 * The first production bridge between the two apps.
 *
 * OpenTV owns sources, channels and EPG persistence. Telly owns the TV
 * presentation and playback interaction model.
 */
class OpenTvTellyGraph(context: Context) {
    private val appContext = context.applicationContext
    private val openTv = OpenTvServiceLocator.get(appContext)

    val channels =
        OpenTvChannelDaoAdapter(
            channels = openTv.database.channels(),
            categories = openTv.database.categories(),
        )

    val programmes =
        OpenTvProgramDaoAdapter(
            programmes = openTv.database.programmes(),
        )

    val playback: PlaybackDeps =
        TellyServiceLocator.bridgedPlaybackDeps(
            context = appContext,
            channelDao = channels,
            programDao = programmes,
        )

    val guide: GuideDeps =
        TellyServiceLocator.guideDeps(
            context = appContext,
            playback = playback,
        )
}
