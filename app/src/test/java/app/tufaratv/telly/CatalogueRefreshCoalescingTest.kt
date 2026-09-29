package app.tufaratv.telly

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueRefreshCoalescingTest {
    @Test
    fun firstPositiveCountEmitsImmediatelyAndBurstIsCollapsed() = runTest {
        val counts = MutableStateFlow(0)
        val seen = mutableListOf<Int>()

        val job =
            backgroundScope.launch {
                counts.coalescedCatalogueCounts(delayMillis = 5_000).toList(seen)
            }
        runCurrent()

        advanceUntilIdle()
        assertThat(seen).containsExactly(0)

        counts.value = 2_000
        advanceUntilIdle()
        assertThat(seen).containsExactly(0, 2_000)

        counts.value = 4_000
        advanceTimeBy(1_000)
        counts.value = 6_000
        advanceTimeBy(1_000)
        counts.value = 8_000
        advanceTimeBy(4_999)

        assertThat(seen).containsExactly(0, 2_000)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertThat(seen).containsExactly(0, 2_000, 8_000)

        job.cancel()
    }
}
