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
}
