package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.benchmark.TerminalBenchmarkWidthIndex
import me.rerere.rikkahub.benchmark.TerminalIntrinsicWidthMeasurer
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportAnchor
import me.rerere.rikkahub.data.container.ViewportMode
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.data.container.terminalScaledItemClip
import me.rerere.rikkahub.ui.pages.container.TERMINAL_LAZY_SCREEN_KEY
import me.rerere.rikkahub.ui.pages.container.TERMINAL_LAZY_TAIL_KEY
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.executeTerminalLazyItemScroll
import me.rerere.rikkahub.ui.pages.container.rememberTerminalViewportGestures
import me.rerere.rikkahub.ui.pages.container.runTerminalViewportScrollEffects
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** Real Text heights + the production controller and sole executor. Never supplies a total height. */
class TerminalItemViewportInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(45)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "item lazy=true failed ${description.methodName}", e)
        }
    }

    private fun mount(f: Fixture = Fixture()): Fixture {
        compose.setContent { f.Content() }
        settle(f)
        return f
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(10_000) { f.controller.state.value.initialized && f.activeWriters == 0 }
        compose.waitForIdle()
        f.failure?.let { throw AssertionError("Item executor failed", it) }
        compose.runOnIdle {
            assertNotNull("missing completed item layout", f.observation())
            assertNull("unfinished scroll effect", f.controller.state.value.scrollEffect)
            assertTrue("not virtualized: ${f.measurements.retainedRows}", f.measurements.retainedRows < 100)
            assertTrue("overlapping writers: ${f.maximumWriters}", f.maximumWriters <= 1)
        }
    }

    @Test fun itemNativeSavedHistoryAnchorRestoresWithVariableNaturalHeights() {
        val f = mount()
        compose.runOnIdle {
            val captured = f.observation()!!.capture()!!
            assertEquals(f.saved.anchorLineId, captured.anchor.lineId)
            assertEquals(7, captured.anchor.clippedTopPx)
            assertFalse(f.controller.state.value.autoScroll)
            assertTrue(f.observation()!!.rows.map { it.heightPx }.distinct().size > 1)
        }
    }

    @Test fun itemNativeThirtyTrimsPreserveIdAndActualClipping() {
        val f = mount()
        repeat(30) {
            compose.runOnIdle { f.append() }
            settle(f)
            compose.runOnIdle {
                assertEquals(f.saved.anchorLineId, f.observation()!!.capture()!!.anchor.lineId)
                assertEquals(7, f.observation()!!.capture()!!.anchor.clippedTopPx)
            }
        }
    }

    @Test fun itemNativeFontChangesUseOriginalMeasuredCaptureWithoutDrift() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(false, f.inputPx()) }
        val original = f.controller.state.value
        repeat(3) {
            compose.runOnIdle { f.fontSp = 21 }
            settle(f)
            compose.runOnIdle {
                val actual = f.observation()!!.capture()!!
                assertEquals(original.anchor!!.lineId, actual.anchor.lineId)
                assertEquals(terminalScaledItemClip(7, original.anchorRowHeightPx, actual.capturedRowHeightPx),
                    actual.anchor.clippedTopPx)
            }
            compose.runOnIdle { f.fontSp = 14 }
            settle(f)
            compose.runOnIdle { assertEquals(7, f.observation()!!.capture()!!.anchor.clippedTopPx) }
        }
    }

    @Test fun itemNativeFollowAndScreenAnchorUseOneWholeMeasuredScreenItem() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
            assertEquals(1, f.lazy.layoutInfo.visibleItemsInfo.count { it.key == TERMINAL_LAZY_SCREEN_KEY })
            val id = f.frame.screenLineIds[8]
            f.controller.restoreItemAnchor(ViewportAnchor(id, 9, f.frame.screenGeneration, null), 0)
        }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.frame.screenLineIds[8], f.observation()!!.capture()!!.anchor.lineId)
            assertEquals(9, f.observation()!!.capture()!!.anchor.clippedTopPx)
        }
    }

    @Test fun itemNativeContractionFollowsLatestOutputWithoutRestoringOldPixels() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()); f.heightDp = 140 }
        settle(f)
        repeat(4) { compose.runOnIdle { f.append() }; settle(f) }
        compose.runOnIdle { f.heightDp = 220 }
        settle(f)
        compose.runOnIdle { assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow)) }
    }

    @Test fun itemNativeNewDragCancelsOldAnimationAndRetainsItsFinalConsumedAnchor() {
        val f = mount()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { f.controller.jumpToBottom(f.inputPx()) }
            compose.mainClock.advanceTimeBy(32)
            lateinit var old: TerminalViewportScrollEffect
            compose.runOnUiThread { old = requireNotNull(f.controller.state.value.scrollEffect) }
            val node = compose.onNodeWithTag("item-output")
            val size = node.fetchSemanticsNode().size
            node.performTouchInput {
                down(Offset(size.width / 2f, size.height * .2f))
                repeat(3) { moveBy(Offset(0f, 70f), delayMillis = 32) }
            }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread {
                assertFalse(f.controller.isCurrent(old))
                assertEquals(ViewportMode.LOCKED, f.controller.state.value.mode)
            }
            node.performTouchInput { advanceEventTime(500); up() }
        } finally { compose.mainClock.autoAdvance = true }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.controller.state.value.anchor!!.lineId, f.observation()!!.capture()!!.anchor.lineId)
            assertFalse(f.controller.state.value.autoScroll)
        }
    }

    @Test fun itemNativeSlowSwipesAndFastTopJumpUseRealGeometryAndOneWriter() {
        val f = mount()
        val node = compose.onNodeWithTag("item-output")
        val size = node.fetchSemanticsNode().size
        val a = Offset(size.width / 2f, size.height * .2f)
        val b = Offset(size.width / 2f, size.height * .8f)
        node.performTouchInput { swipe(b, a, 600) }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.controller.state.value.anchor!!.lineId, f.observation()!!.capture()!!.anchor.lineId)
            f.config = f.config.copy(fastFlingRequiredCount = 1)
        }
        node.performTouchInput { swipeWithVelocity(a, b, endVelocity = 6000f) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.atTop)
            assertFalse(f.controller.state.value.autoScroll)
            assertEquals(f.frame.historyLineIds.first(), f.controller.state.value.anchor!!.lineId)
        }
    }

    @Test fun itemNativeConcurrentOutputDuringBottomJumpReachesCurrentTail() {
        val f = mount()
        compose.runOnIdle {
            f.outputDuringJump = true
            f.controller.jumpToBottom(f.inputPx())
        }
        compose.waitUntil(10_000) { f.appendCount == 30 }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
            assertTrue(f.controller.state.value.autoScroll)
        }
    }

    private class Fixture {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 24, maxScrollbackLines = 1_000).apply {
            feed("\u001B[?25l" + (0 until 1024).joinToString("\r\n") { "r$it 中文 e\u0301 " + "x".repeat(it % 20 + 20) })
        }
        private fun render(): TerminalEmulator.RenderFrame {
            val raw = terminal.renderFrame()
            val ids = raw.historyLineIds + raw.screenLineIds
            return raw.copy(rows = raw.rows.mapIndexed { i, row ->
                if (ids[i] % 7L != 0L) row else TerminalEmulator.RenderedRow(buildAnnotatedString {
                    append(row.text); addStyle(SpanStyle(fontSize = 29.sp), 0, length)
                })
            })
        }
        var frame by mutableStateOf(render(), referentialEqualityPolicy())
        val saved = TerminalViewportState(autoScroll = false, anchorLineId = frame.historyLineIds[500],
            anchorClippedTopPx = 7, anchorHistoryGeneration = frame.historyGeneration)
        val controller = TerminalViewportController(saved)
        val rows = createTerminalRenderedRows(frame)
        private val sync = createTerminalRenderedRowsSyncState(frame, rows)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        val measurements = TerminalLazyItemMeasurements()
        val widthIndex = TerminalBenchmarkWidthIndex()
        var fontSp by mutableIntStateOf(14)
        var heightDp by mutableIntStateOf(220)
        var config by mutableStateOf(TerminalViewportGestureConfig(true, false, 2))
        var pass: TerminalLazyLayoutPass? = null
        var cellHeight = 1
        var tailPadding = 1
        @Volatile var activeWriters = 0
        @Volatile var maximumWriters = 0
        @Volatile var failure: Throwable? = null
        var outputDuringJump = false
        @Volatile var appendCount = 0
        private lateinit var scope: CoroutineScope

        fun observation(): TerminalItemViewport? = pass?.takeIf { it.frame === frame }?.let {
            measurements.read(it, lazy, cellHeight, tailPadding)
        }

        fun inputPx(): Int {
            controller.observeItemViewport(observation())
            return 0 // Intentionally no global pixel estimate; the item controller never uses this value.
        }

        fun append() {
            terminal.feed("\r\nappend-${appendCount++} 中文")
            val next = render()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, forcePendingGridBlanks = true,
                    nowMs = 1_000L, syncState = sync)
                frame = next
            }
        }

        @Composable fun Content() {
            scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val resolver = LocalFontFamilyResolver.current
            val style = TextStyle(fontFamily = JetbrainsMono, fontSize = fontSp.sp, lineHeight = fontSp.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            val textMeasurer = rememberTextMeasurer()
            cellHeight = textMeasurer.measure("W", style).size.height.coerceAtLeast(1)
            tailPadding = with(density) { 8.dp.roundToPx() }
            val nextPass = remember(frame.revision, style, density) { TerminalLazyLayoutPass(frame, style to density) }
            pass = nextPass
            // Same proven scalar-width index/intrinsics as the benchmark; no benchmark source fork.
            // This fixture uses the bundled synchronous JetBrains font, not downloaded async fonts.
            val widthMeasurer = remember(style, density, direction, resolver) {
                TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
            }
            val width = widthIndex.width(frame, widthMeasurer) { widthMeasurer.width(it) }
            val gestures = rememberTerminalViewportGestures(this, controller, config, lazy.interactionSource,
                currentScrollPx = { inputPx() }, isScrollInProgress = { lazy.isScrollInProgress })
            LaunchedEffect(controller) {
                snapshotFlow { observation() }.collect { controller.updateItemViewport(frame, it) }
            }
            LaunchedEffect(controller) {
                runTerminalViewportScrollEffects(controller, { inputPx() }, { error("No global range") },
                    { lazy.isScrollInProgress }, scrollToItemTarget = { effect ->
                        activeWriters++
                        maximumWriters = maxOf(maximumWriters, activeWriters)
                        try {
                            if (effect.animated && outputDuringJump) {
                                outputDuringJump = false
                                scope.launch { repeat(30) { withFrameNanos { }; append() } }
                            }
                            executeTerminalLazyItemScroll(effect, lazy, { observation() }, { controller.isCurrent(effect) })
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Throwable) { failure = e; throw e
                        } finally { inputPx(); activeWriters-- }
                    }) { _, _ -> error("Item target was sent to pixel executor") }
            }
            MaterialTheme {
                Box(Modifier.size(320.dp, heightDp.dp).testTag("item-output").nestedScroll(gestures.connection)) {
                    LazyColumn(Modifier.size(320.dp, heightDp.dp).horizontalScroll(horizontal)
                        .widthIn(min = with(density) { width.toDp() }), state = lazy,
                        flingBehavior = gestures.flingBehavior, userScrollEnabled = config.panEnabled || config.selectionMode) {
                        items(frame.historyCount, key = { rows[it].lineId }, contentType = { "history" }) { index ->
                            measurements.Row(nextPass, rows[index], style)
                        }
                        item(key = TERMINAL_LAZY_SCREEN_KEY, contentType = "screen") {
                            Column {
                                for (index in frame.historyCount until rows.size) key(rows[index].lineId) {
                                    measurements.Row(nextPass, rows[index], style)
                                }
                            }
                        }
                        item(key = TERMINAL_LAZY_TAIL_KEY, contentType = "tail") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}
