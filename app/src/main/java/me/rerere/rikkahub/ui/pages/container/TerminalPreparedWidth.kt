package me.rerere.rikkahub.ui.pages.container

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnitType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import java.lang.ref.WeakReference

/** Weak identity registry: font/Activity/resolver objects never escape through a checkpoint. */
private object TerminalFontIdentities {
    private var serial = 0L
    private val entries = ArrayList<Pair<WeakReference<Any>, Long>>()
    @Synchronized fun token(value: Any): Long {
        entries.removeAll { it.first.get() == null }
        entries.firstOrNull { it.first.get() === value }?.let { return it.second }
        val id = ++serial
        // Eviction only loses reuse, never makes a different resolver/font appear identical.
        if (entries.size == 64) entries.removeAt(0)
        entries.add(WeakReference(value) to id)
        return id
    }
}

private data class PackagedWidthProof(
    val fontSize: Long,
    val density: Int,
    val scale: Int,
    val direction: Int,
    val resolver: Long,
    val fonts: List<Long>,
)

/** Deliberately limited to the exact production style. Unknown/custom/async styles stay synchronous. */
internal fun TerminalTextMetrics.checkpointProof(): Any? {
    val canonical = TextStyle(color = Color(0xFF00E676), fontFamily = JetbrainsMono,
        fontSize = style.fontSize, lineHeight = style.fontSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    if (style != canonical || style.fontFamily !== JetbrainsMono || style.fontSize.type != TextUnitType.Sp ||
        resolvedFonts.size != 4) return null
    return PackagedWidthProof(style.fontSize.value.toRawBits().toLong(), density.density.toRawBits(),
        density.fontScale.toRawBits(), direction.ordinal, TerminalFontIdentities.token(resolver),
        resolvedFonts.map { TerminalFontIdentities.token(it) })
}

private data class WidthHistoryKey(val owner: Any, val generation: Long, val revision: Long, val columns: Int)
private val terminalColdWidthWorker = Mutex()

/** One immutable work item at a time. New active-row output cannot endlessly cancel a cold history scan. */
@Composable
internal fun rememberTerminalPreparedWidth(
    frame: TerminalEmulator.RenderFrame,
    style: TextStyle,
    index: TerminalTranscriptWidthIndex,
    checkpoint: TerminalWidthCheckpointStore?,
): Int? {
    val metrics = terminalTextMetrics(style)
    val proof = remember(metrics) { metrics.checkpointProof() }
    // Reading uses the binding's lease; a new mount cannot be overwritten by an old disposal.
    val saved = remember(index, checkpoint) { checkpoint?.peek() }
    var restored by remember(index, metrics) { mutableStateOf(false) }
    var completed by remember(index) { mutableIntStateOf(0) }
    var failed by remember(index, metrics) { mutableStateOf(false) }
    val owned = frame.ownedRows()
    val key = owned?.let {
        WidthHistoryKey(it.history.owner, it.history.generation, it.history.renderRevision, it.columns)
    }
    val requestToken = remember(index, key, metrics) { Any() }
    val latestRequest = androidx.compose.runtime.rememberUpdatedState(requestToken)
    SideEffect {
        if (!restored) {
            if (!index.hasRetainedState && proof != null) index.restore(saved, proof, metrics)
            restored = true
        }
    }
    val cold = proof != null && key != null && !failed &&
        ((!restored && !index.hasRetainedState && saved != null) ||
            index.requiredHistoryMeasurements(frame, metrics) > 128)
    if (cold) {
        LaunchedEffect(index, key, metrics, restored, completed) {
            if (!restored) return@LaunchedEffect
            val expectedEpoch = index.epoch
            val candidate = index.fork()
            try {
                withContext(Dispatchers.Default) {
                    terminalColdWidthWorker.withLock {
                        val coroutine = currentCoroutineContext()
                        val measurer = TerminalRowWidthMeasurer(frame, resolveDefaults(style, metrics.direction),
                            metrics.density, metrics.resolver)
                        Trace.beginSection("Terminal.productionWidth")
                        try {
                            candidate.width(frame, metrics) { text ->
                                coroutine.ensureActive()
                                measurer.width(text)
                            }
                        } finally { Trace.endSection() }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (latestRequest.value !== requestToken) return@LaunchedEffect
                index.acceptComputed(candidate, expectedEpoch, frame)
                // Re-evaluate against the latest frame even when a committed fallback revoked this result.
                completed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the original synchronous path for a resolver that rejects off-thread use.
                failed = true
            }
        }
        return null
    }
    // This path is bounded incremental work, or the untouched compatibility resolver path.
    val width = remember(index, TerminalFrameReference(frame), metrics, completed) {
        Trace.beginSection("Terminal.productionWidth")
        try {
            val measurer = TerminalRowWidthMeasurer(frame, resolveDefaults(style, metrics.direction),
                metrics.density, metrics.resolver)
            index.width(frame, metrics, measurer::width)
        } finally { Trace.endSection() }
    }
    return width
}
