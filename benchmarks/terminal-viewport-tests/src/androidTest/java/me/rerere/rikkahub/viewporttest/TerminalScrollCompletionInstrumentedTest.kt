package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.ViewportAnchor
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalVirtualHistoryTranscript
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.executeTerminalLazyItemScroll
import me.rerere.rikkahub.ui.pages.container.rememberTerminalLazyLayoutPass
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Same mounted production Text/LazyList, old/new executor bodies, alternating order per pair.
 * No production controller is attached here: setup owns positioning and each sampled executor is
 * the sole writer. The separate full-controller suites validate cancellation, IME and concurrent output.
 * Counting delegates to Compose's test-driven frame clock, never changes the await policy.
 * Wall time here includes test scheduling; this is a work-count/geometry comparison, not phone latency.
 */
class TerminalScrollCompletionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)

    private class CountedClock(private val clock: MonotonicFrameClock) : MonotonicFrameClock {
        var waits = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            waits++
            return clock.withFrameNanos(onFrame)
        }
    }

    private data class Sample(val success: Boolean, val waits: Int, val nanos: Long,
        val captured: TerminalItemScrollTarget.Anchor?)

    private class Fixture(styled: Boolean = false) {
        private val terminal = TerminalEmulator(80, 24, 1_000).apply {
            feed("\u001B[?25l" + (0 until 1_024).joinToString("\r\n") { "row-$it 中文 e\u0301" })
        }
        val frame = terminal.renderFrame().let { original ->
            if (!styled) original else original.copy(rows = original.rows.mapIndexed { index, row ->
                if (index % 7 != 0) row else row.copy(text = buildAnnotatedString {
                    append(row.text)
                    addStyle(SpanStyle(fontSize = 25.sp), 0, length)
                })
            })
        }
        val rows = createTerminalRenderedRows(frame)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        val measurements = TerminalLazyItemMeasurements()
        val widths = TerminalTranscriptWidthIndex()
        lateinit var scope: CoroutineScope
        lateinit var pass: TerminalLazyLayoutPass
        var font by mutableIntStateOf(14)
        var cellHeight = 1
        var tail = 0

        @Composable fun Content() {
            scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono, fontSize = font.sp,
                lineHeight = font.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            cellHeight = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            tail = with(density) { 8.dp.roundToPx() }
            pass = rememberTerminalLazyLayoutPass(frame, style)
            TerminalVirtualHistoryTranscript(frame, rows.toList(), style, pass, measurements, lazy,
                horizontal, false, ScrollableDefaults.flingBehavior(), widths, Modifier.size(320.dp, 220.dp))
        }

        fun view(): TerminalItemViewport? = measurements.read(pass, lazy, cellHeight, tail)
        fun anchor(index: Int, clip: Int = 9) = TerminalItemScrollTarget.Anchor(
            ViewportAnchor(frame.historyLineIds[index], clip, null, frame.historyGeneration), 0)
    }

    private fun mount(styled: Boolean = false): Fixture = Fixture(styled).also { f ->
        compose.setContent { f.Content() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requireNotNull(f.view()).ready) }
    }

    private fun <T> coroutine(f: Fixture, block: suspend () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        compose.runOnIdle { f.scope.launch { result.complete(runCatching { block() }) } }
        compose.waitUntil(15_000) { result.isCompleted }
        return runBlocking { result.await().getOrThrow() }
    }

    private fun execute(f: Fixture, target: TerminalItemScrollTarget, old: Boolean = false,
        current: () -> TerminalItemViewport? = { f.view() }, isCurrent: () -> Boolean = { true }): Sample = coroutine(f) {
        val clock = CountedClock(requireNotNull(currentCoroutineContext()[MonotonicFrameClock]))
        val effect = TerminalViewportScrollEffect(1, 0, ViewportScrollOrigin.REDUCER, itemTarget = target)
        val start = System.nanoTime()
        val success = withContext(clock) {
            if (old) executeLegacyTerminalLazyItemScroll(effect, f.lazy, current, isCurrent)
            else executeTerminalLazyItemScroll(effect, f.lazy, current, isCurrent)
        }
        Sample(success, clock.waits, System.nanoTime() - start, f.view()?.capture())
    }

    private fun paired(case: String, f: Fixture, target: TerminalItemScrollTarget, setup: suspend () -> Unit) {
        repeat(12) { pair ->
            val samples = mutableMapOf<Boolean, Sample>()
            for (old in if (pair % 2 == 0) listOf(true, false) else listOf(false, true)) {
                coroutine(f) { setup() }
                compose.waitForIdle()
                compose.runOnIdle { assertFalse("setup already satisfies $case", requireNotNull(f.view()).isSatisfied(target)) }
                val sample = execute(f, target, old)
                samples[old] = sample
                assertTrue(sample.success)
                compose.waitForIdle()
                compose.runOnIdle { assertTrue(requireNotNull(f.view()).isSatisfied(target)) }
                if (pair >= 2) Log.i("TerminalScrollCompletionTest", "SCROLL_COMPLETION_SAMPLE " + JSONObject()
                    .put("case", case).put("pair", pair - 2).put("legacy", old).put("first", pair % 2 == (if (old) 0 else 1))
                    .put("waits", sample.waits).put("nanos", sample.nanos).toString())
            }
            assertEquals("old/new row/clip differ", samples[true]!!.captured, samples[false]!!.captured)
            assertEquals("ready candidate must not wait for another frame", 0, samples[false]!!.waits)
            assertEquals("frozen control's unnecessary final frame disappeared", 1, samples[true]!!.waits)
        }
    }

    @Test fun scrollCompletionPairedVisibleHistoryCorrectionUsesSameGeometryWithoutFinalFrame() {
        val f = mount()
        val target = f.anchor(400, 12)
        paired("historyAnchor", f, target) { f.lazy.scrollToItem(400, 5) }
    }

    @Test fun scrollCompletionPairedScreenFollowUsesWholeNaturalHeightScreen() {
        val f = mount(styled = true)
        paired("screenFollow", f, TerminalItemScrollTarget.Follow) {
            f.lazy.scrollToItem(f.frame.historyCount)
            val observed = requireNotNull(f.view())
            val last = observed.rows.single { it.index == observed.followRowIndex }
            val delta = last.bottomPx + observed.tailPaddingPx - observed.viewportHeightPx
            f.lazy.scrollBy((delta - 11).toFloat())
        }
    }

    @Test fun scrollCompletionOffscreenTargetAndFontChangesStillReachExactAnchor() {
        val f = mount(styled = true)
        for (font in listOf(14, 21, 12)) {
            compose.runOnIdle { f.font = font }
            compose.waitForIdle()
            for (index in listOf(200, 850)) {
                coroutine(f) { f.lazy.scrollToItem(0) }
                compose.waitForIdle()
                val target = f.anchor(index)
                val sample = execute(f, target)
                assertTrue(sample.success)
                assertEquals(target.anchor.lineId, sample.captured?.anchor?.lineId)
                assertEquals(9, sample.captured?.anchor?.clippedTopPx)
                compose.runOnIdle { assertTrue(requireNotNull(f.view()).isSatisfied(target)) }
            }
        }
    }

    @Test fun scrollCompletionMissingMeasurementWaitsButCancellationNeverReportsSuccess() {
        val f = mount()
        var reads = 0
        val ready = execute(f, TerminalItemScrollTarget.Top, current = { if (++reads <= 2) null else f.view() })
        assertTrue(ready.success)
        assertEquals(2, ready.waits)
        reads = 0
        val cancelled = execute(f, TerminalItemScrollTarget.Follow, current = { reads++; null }, isCurrent = { reads < 2 })
        assertFalse(cancelled.success)
        assertEquals(2, cancelled.waits)
        assertEquals(0, f.lazy.firstVisibleItemIndex)
    }

    @Test fun scrollCompletionPostMutationTokenIsCheckedBeforeAcceptingMeasuredSuccess() {
        val f = mount()
        coroutine(f) { f.lazy.scrollToItem(400, 5) }
        compose.waitForIdle()
        var reads = 0
        var active = true
        val result = execute(f, f.anchor(400, 12), current = {
            if (++reads == 2) active = false
            f.view()
        }, isCurrent = { active })
        assertFalse(result.success)
        assertEquals(0, result.waits)
        assertEquals(2, reads)
    }

    @Test fun scrollCompletionPostMutationMissingOrChangedGeometryStillYields() {
        val f = mount()
        for (changed in 0..3) {
            coroutine(f) { f.lazy.scrollToItem(400, 5) }
            compose.waitForIdle()
            var reads = 0
            val result = execute(f, f.anchor(400, 12), current = {
                val current = requireNotNull(f.view())
                if (++reads != 2) current else when (changed) {
                    0 -> null
                    1 -> current.copy(frame = current.frame.copy())
                    2 -> current.copy(layoutKey = "changed-metrics")
                    else -> current.copy(viewportHeightPx = current.viewportHeightPx + 1)
                }
            })
            assertTrue(result.success)
            assertEquals("post-mutation guard skipped changed geometry case=$changed", 1, result.waits)
            assertEquals(12, result.captured?.anchor?.clippedTopPx)
        }
    }

    @Test fun scrollCompletionStalledLayoutIsBoundedAndNeverSuccess() {
        val f = mount()
        val stalled = execute(f, TerminalItemScrollTarget.Follow, current = { null })
        assertFalse(stalled.success)
        assertEquals(16, stalled.waits)
        val cancelled = execute(f, TerminalItemScrollTarget.Follow, isCurrent = { false })
        assertFalse(cancelled.success)
        assertEquals(0, cancelled.waits)
    }
}
