@file:Suppress("DEPRECATION")

package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.collectLatest
import me.rerere.rikkahub.data.container.TerminalSelectionPoint
import me.rerere.rikkahub.data.container.TerminalVirtualSelection
import me.rerere.rikkahub.utils.TerminalEmulator
import kotlin.math.roundToInt
import java.security.MessageDigest

/** Only currently attached rows own coordinates/TextLayoutResult. Stable endpoint data lives separately. */
internal class TerminalVirtualSelectionUi(val binding: TerminalViewportBinding) {
    val selection = TerminalVirtualSelection()
    data class Row(val owner: Any, val id: Long, val index: Int, val text: AnnotatedString,
        val coordinates: LayoutCoordinates?, val layout: TextLayoutResult?, val bounds: Rect?)
    private val rows = HashMap<Long, Row>()
    private var frame: TerminalEmulator.RenderFrame? = null
    private var physical: List<TerminalRenderedRowState> = emptyList()
    var enabled by mutableStateOf(false)
        private set
    var version by mutableIntStateOf(0)
        private set
    var bounds by mutableStateOf<Rect?>(null)
        private set
    var coordinates: LayoutCoordinates? = null
        private set
    var dragging by mutableStateOf<Boolean?>(null)
        private set
    var dragPosition by mutableStateOf<Offset?>(null)
        private set
    private var draggedHandle: Handle? = null
    var showToolbar by mutableStateOf(false)
    var focused by mutableStateOf(false)
    var copyAction: () -> Boolean = { false }
    val focusRequester = FocusRequester()
    val retainedLayouts: Int get() = rows.size

    /** No-op without a diagnostic observer; only bounded scalar state and SHA256 text proofs. */
    fun trace(stage: String, vararg fields: Pair<String, Any?>) {
        val observer = binding.onSelectionTrace ?: return
        val range = selection.range
        observer(stage, linkedMapOf<String, Any?>(
            "enabled" to enabled, "focused" to focused, "toolbar" to showToolbar, "dragging" to dragging,
            "startId" to range?.start?.id, "startIndex" to range?.start?.index, "startOffset" to range?.start?.offset,
            "endId" to range?.end?.id, "endIndex" to range?.end?.index, "endOffset" to range?.end?.offset,
            "reversed" to range?.reversed, "collapsed" to range?.collapsed, "layouts" to rows.size,
            "dragX" to dragPosition?.x, "dragY" to dragPosition?.y,
            "viewLeft" to bounds?.left, "viewTop" to bounds?.top,
            "viewWidth" to bounds?.width, "viewHeight" to bounds?.height,
            "firstIndex" to binding.lazy.firstVisibleItemIndex, "firstOffset" to binding.lazy.firstVisibleItemScrollOffset,
            "effect" to binding.controller.state.value.scrollEffect?.id,
            "gesture" to binding.controller.state.value.gesture?.id, "writers" to binding.activeWriters,
        ).apply { putAll(fields) })
    }

    fun textProof(value: String?): Map<String, Any?> = if (binding.onSelectionTrace == null) emptyMap() else mapOf(
        "length" to value?.length, "sha256" to value?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        })

    fun focusChanged(isFocused: Boolean, hasFocus: Boolean) {
        trace("focus-before", "isFocused" to isFocused, "hasFocus" to hasFocus)
        focused = isFocused
        if (!hasFocus) clear("focus-lost")
        trace("focus-after", "isFocused" to isFocused, "hasFocus" to hasFocus)
    }

    fun bind(next: TerminalEmulator.RenderFrame, screen: List<TerminalRenderedRowState>, active: Boolean) {
        frame = next
        physical = screen
        enabled = active
        if (active) selection.bind(next) { physical[it].text } else selection.dispose()
        if (!active || selection.range == null) {
            selection.clear()
            showToolbar = false
            finishDrag(false)
            trace("bind-clear", "active" to active, "revision" to next.revision)
        }
    }

    fun handlePointer(source: LayoutCoordinates, position: Offset): Offset? {
        val target = coordinates?.takeIf { it.isAttached } ?: return null
        if (!source.isAttached) return null
        val screen = source.localToScreen(position)
        if (!screen.isSpecified) return null
        val local = target.screenToLocal(screen).takeIf { it.isSpecified && it.x.isFinite() && it.y.isFinite() }
        trace("handle-coordinate", "sourceX" to position.x, "sourceY" to position.y,
            "screenX" to screen.x, "screenY" to screen.y, "localX" to local?.x, "localY" to local?.y)
        return local
    }

    fun beginMouse(position: Offset, wholeWord: Boolean = false) {
        if (wholeWord) { selectWord(position); return }
        val (row, offset) = hit(position, false) ?: return
        selection.select(row.index, offset, offset)
        focusRequester.requestFocus()
        binding.controller.beginVirtualSelection()
        dragging = false
        dragPosition = position
        showToolbar = false
        trace("mouse-start")
    }

    fun selectedRange(index: Int, id: Long, text: AnnotatedString): TextRange? {
        val current = frame ?: return null
        if (index !in current.rows.indices) return null
        val currentId = if (index < current.historyCount) current.historyLineIds[index]
            else current.screenLineIds[index - current.historyCount]
        val currentText = if (index < current.historyCount) current.rows[index].text else physical[index-current.historyCount].text
        if (currentId != id || currentText != text) return null
        return selection.rowRange(index)?.takeIf { it.min >= 0 && it.max <= text.length }
    }

    fun position(value: LayoutCoordinates) {
        coordinates = value
        val current = value.boundsInRoot()
        if (bounds != current) { bounds = current; version++ }
    }

    fun record(owner: Any, id: Long, index: Int, text: AnnotatedString,
        layout: TextLayoutResult? = null, coordinates: LayoutCoordinates? = null) {
        val previous = rows[id]?.takeIf { it.owner === owner }
        val sameText = previous?.text == text
        val measured = layout ?: previous?.layout?.takeIf { sameText }
        val positioned = coordinates ?: previous?.coordinates
        val box = positioned?.takeIf { it.isAttached }?.let {
            val top = it.positionInRoot()
            Rect(top, Size(it.size.width.toFloat(), it.size.height.toFloat()))
        }
        val next = Row(owner, id, index, text, positioned, measured, box)
        rows[id] = next
        if (previous == null || previous.index != index || previous.text != text ||
            previous.bounds != box || previous.layout !== measured) version++
    }

    fun release(owner: Any, id: Long) {
        if (rows[id]?.owner === owner) { rows.remove(id); version++ }
    }

    private fun valid(row: Row): Boolean {
        val current = frame ?: return false
        if (row.index !in current.rows.indices || row.coordinates?.isAttached != true || row.bounds == null || row.layout == null) return false
        val id = if (row.index < current.historyCount) current.historyLineIds[row.index]
            else current.screenLineIds[row.index - current.historyCount]
        val text = if (row.index < current.historyCount) current.rows[row.index].text else physical[row.index - current.historyCount].text
        return row.id == id && row.text == text && row.layout.layoutInput.text == text
    }

    private fun hit(local: Offset, nearest: Boolean): Pair<Row, Int>? {
        val view = bounds ?: return null
        val root = local + view.topLeft
        val candidates = rows.values.filter { row ->
            val box = row.bounds
            valid(row) && box != null && box.bottom > view.top && box.top < view.bottom
        }
        val row = candidates.firstOrNull { val box = checkNotNull(it.bounds); root.y >= box.top && root.y < box.bottom }
            ?: if (nearest) candidates.minByOrNull {
                val box = checkNotNull(it.bounds)
                when { root.y < box.top -> box.top - root.y
                    root.y >= box.bottom -> root.y - box.bottom
                    else -> 0f }
            } else null
        row ?: return null
        val box = checkNotNull(row.bounds)
        val layout = checkNotNull(row.layout)
        val position = Offset(root.x - box.left, (root.y - box.top).coerceIn(0f, (box.height - 1).coerceAtLeast(0f)))
        val offset = layout.getOffsetForPosition(position).coerceIn(0, row.text.length)
        trace("hit", "localX" to local.x, "localY" to local.y, "nearest" to nearest,
            "rowId" to row.id, "rowIndex" to row.index, "rowLeft" to box.left, "rowTop" to box.top,
            "textX" to position.x, "textY" to position.y, "offset" to offset,
            "layoutWidth" to layout.size.width, "layoutHeight" to layout.size.height)
        return row to offset
    }

    fun selectWord(position: Offset): Boolean {
        if (!enabled) return false
        val (row, offset) = hit(position, false) ?: return false
        val word = if (row.text.isEmpty()) TextRange.Zero else checkNotNull(row.layout).getWordBoundary(offset.coerceAtMost(row.text.lastIndex))
        trace("word-boundary", "inputX" to position.x, "inputY" to position.y, "rowId" to row.id,
            "rowIndex" to row.index, "offset" to offset, "from" to word.min, "to" to word.max,
            "rowText" to textProof(row.text.text), "wordText" to textProof(row.text.text.substring(word.min, word.max)))
        selection.select(row.index, word.min, word.max)
        focusRequester.requestFocus()
        binding.controller.beginVirtualSelection()
        dragging = false
        dragPosition = position
        draggedHandle = handle(false)
        showToolbar = false
        trace("word-selected")
        return true
    }

    fun beginHandle(start: Boolean, position: Offset) {
        trace("handle-down", "startHandle" to start, "inputX" to position.x, "inputY" to position.y)
        if (selection.range == null || !enabled) return
        focusRequester.requestFocus()
        binding.controller.beginVirtualSelection()
        dragging = start
        dragPosition = position
        draggedHandle = handle(start)
        showToolbar = false
        trace("handle-start", "startHandle" to start)
    }

    fun extend(position: Offset) {
        val start = dragging ?: return
        dragPosition = position
        val (row, offset) = hit(position, true) ?: return
        selection.move(start, row.index, offset)
        trace("extended", "startHandle" to start, "targetIndex" to row.index, "targetOffset" to offset)
    }

    fun finishDrag(toolbar: Boolean = true) {
        trace("drag-finish-before", "requestToolbar" to toolbar)
        val wasDragging = dragging != null
        dragging = null
        dragPosition = null
        draggedHandle = null
        if (wasDragging) binding.controller.endVirtualSelection()
        if (toolbar) showToolbar = selection.range?.collapsed == false
        trace("drag-finish-after")
    }

    fun clear(reason: String = "clear") { trace("clear", "reason" to reason); selection.clear(); finishDrag(false); showToolbar = false }

    fun selectAll() {
        if (!enabled) return
        selection.selectAll()
        focusRequester.requestFocus()
        showToolbar = selection.range?.collapsed == false
        trace("all-selected")
    }

    fun selectOffsets(index: Int, from: Int, to: Int): Boolean {
        if (!enabled) return false
        selection.select(index, from, to)
        focusRequester.requestFocus()
        showToolbar = selection.range?.collapsed == false
        return true
    }

    data class Handle(val point: Offset, val left: Boolean, val height: Float)
    fun handle(start: Boolean): Handle? {
        val selected = selection.range?.takeUnless { it.collapsed } ?: return null
        val endpoint: TerminalSelectionPoint = if (start) selected.start else selected.end
        val row = rows[endpoint.id]?.takeIf(::valid) ?: return draggedHandle?.takeIf { dragging == start }
        val view = bounds ?: return null
        val box = checkNotNull(row.bounds)
        val layout = checkNotNull(row.layout)
        val offset = endpoint.offset.coerceIn(0, row.text.length)
        val line = layout.getLineForOffset(offset)
        val local = Offset(box.left - view.left + layout.getHorizontalPosition(offset, true),
            box.top - view.top + layout.getLineBottom(line))
        if (dragging != start && (local.x !in 0f..view.width || local.y !in 0f..view.height)) return null
        val direction = if (row.text.isEmpty()) ResolvedTextDirection.Ltr
            else layout.getBidiRunDirection((if (start) offset else offset-1).coerceIn(0,row.text.lastIndex))
        val normal = (direction == ResolvedTextDirection.Ltr) != selected.reversed
        val result = Handle(local, if (start) normal else !normal, layout.getLineBottom(line) - layout.getLineTop(line))
        return result
    }

    fun menuBounds(): Rect? {
        val view = bounds ?: return null
        val a = handle(true)?.point
        val b = handle(false)?.point
        val points = listOfNotNull(a,b)
        return if (points.isEmpty()) view else Rect(view.left + points.minOf { it.x },
            view.top + (points.minOf { it.y } - 24).coerceAtLeast(0f),
            view.left + points.maxOf { it.x } + 1, view.top + points.maxOf { it.y } + 1)
    }

    fun edgeStep() {
        val point = dragPosition ?: return
        val view = bounds ?: return
        if (dragging == null) return
        val edge = minOf(48f, view.height / 4)
        val delta = when {
            point.y < edge -> -((edge-point.y).coerceAtMost(edge) / 2).roundToInt().coerceAtLeast(1)
            point.y > view.height-edge -> ((point.y-view.height+edge).coerceAtMost(edge) / 2).roundToInt().coerceAtLeast(1)
            else -> 0
        }
        if (delta != 0) {
            binding.currentScrollPx()
            val accepted = binding.controller.scrollSelectionBy(delta)
            trace("edge-request", "delta" to delta, "accepted" to accepted,
                "canBackward" to binding.lazy.canScrollBackward, "canForward" to binding.lazy.canScrollForward)
            extend(point)
        }
    }

    fun dispose() { clear(); selection.dispose(); rows.clear(); frame = null; physical = emptyList(); coordinates = null; bounds = null }
}

@Composable
internal fun TerminalVirtualSelectableRow(ui: TerminalVirtualSelectionUi, index: Int, row: TerminalRenderedRowState,
    style: TextStyle, metric: Any, measurements: TerminalLazyItemMeasurements, enabled: Boolean) {
    if (!enabled) {
        measurements.Row(metric, row, style)
        return
    }
    val owner = remember(ui, row.lineId, metric) { Any() }
    val text = row.text
    var layout by remember(owner) { mutableStateOf<TextLayoutResult?>(null) }
    val color = LocalTextSelectionColors.current.backgroundColor
    val context = LocalContext.current
    val latestIndex by rememberUpdatedState(index)
    DisposableEffect(owner) { onDispose { ui.release(owner, row.lineId) } }
    // State/Text/Layout identity must agree; binding a newer frame never validates old layout.
    SideEffect { ui.record(owner, row.lineId, index, text) }
    val selected = Modifier.drawWithContent {
        val range = ui.selectedRange(latestIndex, row.lineId, text)
        val result = layout?.takeIf { it.layoutInput.text == text }
        if (range != null && !range.collapsed && result != null) drawPath(result.getPathForRange(range.min,range.max),color)
        drawContent()
    }.semantics {
        textSelectionRange = ui.selectedRange(index, row.lineId, text) ?: TextRange.Zero
        setSelection { start, end, _ -> ui.selectOffsets(latestIndex, start, end) }
        copyText { ui.copyAction() }
        customActions = listOf(CustomAccessibilityAction(context.getString(android.R.string.selectAll)) { ui.selectAll(); true })
    }
    measurements.VirtualRow(metric,row,style,selected.onGloballyPositioned {
        ui.record(owner,row.lineId,latestIndex,text,coordinates=it)
    },onTextLayout={ result ->
        layout=result
        ui.record(owner,row.lineId,latestIndex,text,layout=result)
    })
}

@Composable
internal fun TerminalVirtualSelectionContainer(ui: TerminalVirtualSelectionUi, enabled: Boolean,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val toolbar = LocalTextToolbar.current
    val haptic = LocalHapticFeedback.current
    val range = ui.selection.range
    val version = ui.version
    val copy: () -> Boolean = {
        ui.trace("copy-request")
        ui.selection.copy()?.let {
            ui.trace("copy-value", "text" to ui.textProof(it.text))
            clipboard.setText(it)
            if (ui.binding.onSelectionTrace != null) ui.trace("clipboard-set", "text" to ui.textProof(clipboard.getText()?.text))
            ui.clear("copied")
            true
        } ?: false.also { ui.trace("copy-null") }
    }
    SideEffect { ui.copyAction = copy }
    DisposableEffect(ui, toolbar) { onDispose { toolbar.hide(); ui.copyAction = { false } } }
    LaunchedEffect(ui, enabled) {
        snapshotFlow { ui.dragging }.collectLatest { dragging ->
            if (enabled && dragging != null) while (ui.dragging != null) { withFrameNanos { }; ui.edgeStep() }
        }
    }
    LaunchedEffect(ui, range, version, ui.showToolbar, ui.focused, enabled) {
        if (enabled && ui.focused && ui.showToolbar && range?.collapsed == false) {
            ui.menuBounds()?.let { rect -> toolbar.showMenu(rect, onCopyRequested = { copy() },
                onSelectAllRequested = { ui.selectAll() }) }
            ui.trace("menu-show")
        } else { toolbar.hide(); ui.trace("menu-hide") }
    }
    val gestures = if (!enabled) Modifier else Modifier
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when {
                it.isCtrlPressed && it.key == Key.C -> copy()
                it.isCtrlPressed && it.key == Key.A -> { ui.selectAll(); true }
                it.key == Key.Escape && ui.selection.range != null -> { ui.clear(); true }
                else -> false
            }
        }
        .focusRequester(ui.focusRequester)
        .onFocusChanged { ui.focusChanged(it.isFocused, it.hasFocus) }
        .focusable()
        .magnifier(sourceCenter = { ui.dragPosition ?: Offset.Unspecified })
        .pointerInput(ui) { detectTapGestures(onTap = { ui.clear("tap") }, onDoubleTap = {
            if (ui.selectWord(it)) ui.finishDrag()
        }) }
        .pointerInput(ui) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Mouse) return@awaitEachGesture
                if (currentEvent.buttons.isSecondaryPressed) {
                    if (ui.selection.range == null) ui.selectWord(down.position)
                    ui.finishDrag()
                } else {
                    ui.beginMouse(down.position)
                }
                down.consume()
                drag(down.id) { change -> ui.extend(change.position); change.consume() }
                currentEvent.changes.forEach { it.consume() }
                ui.finishDrag()
            }
        }
        .pointerInput(ui) {
            detectDragGesturesAfterLongPress(
                onDragStart = { if (ui.selectWord(it)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onDragEnd = { ui.finishDrag() }, onDragCancel = { ui.finishDrag() },
                onDrag = { change, _ -> if (ui.dragging != null) { ui.extend(change.position); change.consume() } },
            )
        }
    Box(modifier.onGloballyPositioned(ui::position).then(gestures)) {
        content()
        if (enabled && ui.focused && range?.collapsed == false) {
            for (start in listOf(true,false)) ui.handle(start)?.let { TerminalVirtualSelectionHandle(ui,start,it) }
        }
    }
}

@Composable
private fun TerminalVirtualSelectionHandle(ui: TerminalVirtualSelectionUi, start: Boolean,
    handle: TerminalVirtualSelectionUi.Handle) {
    val latest by rememberUpdatedState(handle)
    val width = with(LocalDensity.current) { 25.dp.roundToPx() }
    val provider = remember(ui,start,width) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val point = latest.point
                return anchorBounds.topLeft + IntOffset(point.x.roundToInt() - if (latest.left) width else 0,
                    point.y.roundToInt())
            }
        }
    }
    val color = LocalTextSelectionColors.current.handleColor
    val handleCoordinates = remember(ui, start) { arrayOfNulls<LayoutCoordinates>(1) }
    Popup(popupPositionProvider=provider,properties=PopupProperties(clippingEnabled=false,excludeFromSystemGesture=true)) {
        Canvas(Modifier.size(25.dp).testTag(if (start) "terminal-selection-start" else "terminal-selection-end")
            .onGloballyPositioned { handleCoordinates[0] = it }
            .pointerInput(ui,start) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    ui.trace("handle-platform-down", "inputX" to down.position.x, "inputY" to down.position.y,
                        "startHandle" to start)
                    val initial = handleCoordinates[0]?.let { ui.handlePointer(it, down.position) }
                        ?: return@awaitEachGesture
                    val anchor = latest.point.copy(y = latest.point.y - 1)
                    val adjustment = anchor - initial
                    ui.beginHandle(start, anchor)
                    down.consume()
                    try {
                        drag(down.id) { change ->
                            handleCoordinates[0]?.let { ui.handlePointer(it, change.position) }?.let { ui.extend(it + adjustment) }
                            change.consume()
                        }
                    } finally { ui.finishDrag() }
                }
            }) {
            val radius=size.width/2
            drawCircle(color,radius,Offset(radius,radius))
            drawRect(color,Offset(if(latest.left) radius else 0f,0f),Size(radius,radius))
        }
    }
}
