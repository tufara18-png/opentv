package app.tufaratv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tufaratv.data.db.OpenTvDatabase
import app.tufaratv.data.model.Channel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChannelStreamingBatchRoomTest {
    private lateinit var db: OpenTvDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db =
            Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun twoThousandChannelBatchPersistsWithoutSqliteBindOverflow() = runTest {
        val sourceId = 99L
        val stamp = 123456L
        val incoming =
            (1..2_000).map { index ->
                Channel(
                    sourceId = sourceId,
                    streamId = index.toString(),
                    name = "Channel $index",
                    categoryId = "category-${index % 20}",
                    logoUrl = null,
                    epgChannelId = null,
                    number = index,
                    streamUrl = "http://example.test/live/$index",
                    sortIndex = index,
                )
            }

        db.channels().upsertCatalogueBatch(sourceId, incoming, stamp)

        assertThat(db.channels().countForSource(sourceId)).isEqualTo(2_000)
        assertThat(db.channels().byStreamUrl("http://example.test/live/2000")?.lastSeenMillis)
            .isEqualTo(stamp)
    }
    @Test
    fun logicalSnapshotKeepsOnlyBestQualityVariant() = runTest {
        val sourceId = 7L
        val rows =
            listOf(
                Channel(
                    sourceId = sourceId,
                    streamId = "sd",
                    name = "News SD",
                    displayName = "News",
                    groupKey = "news",
                    qualityRank = 10,
                    qualityLabel = "SD",
                    categoryId = "1",
                    logoUrl = null,
                    epgChannelId = "news",
                    number = 1,
                    streamUrl = "http://example.test/live/sd",
                    sortIndex = 1,
                ),
                Channel(
                    sourceId = sourceId,
                    streamId = "hd",
                    name = "News HD",
                    displayName = "News",
                    groupKey = "news",
                    qualityRank = 20,
                    qualityLabel = "HD",
                    categoryId = "1",
                    logoUrl = null,
                    epgChannelId = "news",
                    number = 1,
                    streamUrl = "http://example.test/live/hd",
                    sortIndex = 1,
                ),
                Channel(
                    sourceId = sourceId,
                    streamId = "fhd",
                    name = "News FHD",
                    displayName = "News",
                    groupKey = "news",
                    qualityRank = 30,
                    qualityLabel = "FHD",
                    categoryId = "1",
                    logoUrl = null,
                    epgChannelId = "news",
                    number = 1,
                    streamUrl = "http://example.test/live/fhd",
                    sortIndex = 1,
                ),
                Channel(
                    sourceId = sourceId,
                    streamId = "unique",
                    name = "Unique",
                    displayName = "Unique",
                    groupKey = "",
                    qualityRank = 0,
                    qualityLabel = "",
                    categoryId = "1",
                    logoUrl = null,
                    epgChannelId = "unique",
                    number = 2,
                    streamUrl = "http://example.test/live/unique",
                    sortIndex = 2,
                ),
            )

        db.channels().upsertCatalogueBatch(sourceId, rows, 999L)

        val logical = db.channels().visibleLogicalSnapshot()

        assertThat(logical).hasSize(2)
        assertThat(logical.map { it.streamUrl })
            .containsExactly("http://example.test/live/fhd", "http://example.test/live/unique")
    }

}
