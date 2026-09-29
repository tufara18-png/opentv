package app.tufaratv.telly

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueRefreshCoalescingTest {
    @Test
    fun firstPositiveCountEmitsImmediatelyAndBurstIsCollapsed() = runTest {
        val counts = MutableSharedFlow<Int>(extraBufferCapacity = 32)
        val seen = mutableListOf<Int>()

        val job =
            backgroundScope.launch {
                counts.coalescedCatalogueCounts(delayMillis = 5_000).toList(seen)
            }

        counts.emit(0)
        advanceUntilIdle()
        assertThat(seen).containsExactly(0)

        counts.emit(2_000)
        advanceUntilIdle()
        assertThat(seen).containsExactly(0, 2_000)

        counts.emit(4_000)
        advanceTimeBy(1_000)
        counts.emit(6_000)
        advanceTimeBy(1_000)
        counts.emit(8_000)
        advanceTimeBy(4_999)

        assertThat(seen).containsExactly(0, 2_000)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertThat(seen).containsExactly(0, 2_000, 8_000)

        job.cancel()
    }
}
