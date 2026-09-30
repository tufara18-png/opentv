package app.tufaratv.ui.nativev4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.tufaratv.telly.OpenTvTellyHost
import com.johncorser.telly.core.navigation.Navigator
import com.johncorser.telly.core.navigation.Route

/**
 * Compatibility island for feature surfaces that are not performance-critical. Live playback,
 * guide, VOD and settings stay in the native shell; this keeps search/multiview/DVR reachable
 * while their native equivalents are migrated.
 */
class TellyFeatureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = when (intent.getStringExtra(EXTRA_ROUTE)) {
            "search" -> Route.Search
            "multiview" -> Route.Multiview
            "recordings" -> Route.Recordings
            "history" -> Route.History
            "mylist" -> Route.MyList
            else -> Route.Search
        }
        val navigator = Navigator(start = route)
        setContent {
            OpenTvTellyHost(
                navigator = navigator,
                onEnterPip = {},
            )
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
