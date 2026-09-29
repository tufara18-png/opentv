package app.tufaratv

import app.tufaratv.data.model.Source
import app.tufaratv.data.model.SourceKind
import app.tufaratv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class XtreamStreamingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fortyThousandChannelCatalogueIsDeliveredInBoundedBatches() = runBlocking {
        val total = 40_000
        val body = buildString {
            append('[')
            repeat(total) { i ->
                if (i > 0) append(',')
                append(
                    """{"stream_id":$i,"name":"Channel $i","category_id":"1","stream_icon":"","epg_channel_id":"epg$i","tv_archive":0,"tv_archive_duration":0,"num":${i + 1}}"""
                )
            }
            append(']')
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )

        val api = XtreamApi(OkHttpClient())
        val source =
            Source(
                id = 42,
                name = "Test",
                kind = SourceKind.XTREAM,
                url = server.url("/").toString().trimEnd('/'),
                username = "user",
                password = "pass",
            )

        var seen = 0
        var maxBatch = 0
        val count =
            api.streamLiveStreams(source, batchSize = 250) { batch ->
                seen += batch.size
                maxBatch = maxOf(maxBatch, batch.size)
                assertThat(batch).isNotEmpty()
            }

        assertThat(count).isEqualTo(total)
        assertThat(seen).isEqualTo(total)
        assertThat(maxBatch).isAtMost(250)

        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/player_api.php")
        assertThat(request.requestUrl?.queryParameter("action")).isEqualTo("get_live_streams")
        assertThat(request.requestUrl?.queryParameter("username")).isEqualTo("user")
        assertThat(request.requestUrl?.queryParameter("password")).isEqualTo("pass")
    }

    @Test
    fun slowServerRespectsClientTimeoutInsteadOfHangingForever() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody("[]"),
        )

        val api =
            XtreamApi(
                OkHttpClient.Builder()
                    .callTimeout(300, TimeUnit.MILLISECONDS)
                    .build(),
            )
        val source =
            Source(
                id = 8,
                name = "Slow",
                kind = SourceKind.XTREAM,
                url = server.url("/").toString().trimEnd('/'),
                username = "user",
                password = "pass",
            )

        val started = System.nanoTime()
        val error =
            runCatching {
                api.streamLiveStreams(source) {}
            }.exceptionOrNull()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(error).isNotNull()
        assertThat(elapsedMs).isLessThan(2_000)
    }

    @Test
    fun httpFailureIsSurfacedWithoutHanging() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        val api = XtreamApi(OkHttpClient())
        val source =
            Source(
                id = 7,
                name = "Denied",
                kind = SourceKind.XTREAM,
                url = server.url("/").toString().trimEnd('/'),
                username = "bad",
                password = "bad",
            )

        val error =
            runCatching {
                api.streamLiveStreams(source) {}
            }.exceptionOrNull()

        assertThat(error).isInstanceOf(XtreamApi.XtreamException::class.java)
        assertThat(error?.message).contains("403")
    }
}
