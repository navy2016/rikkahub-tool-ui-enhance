package me.rerere.rikkahub.ui.pages.container

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import me.rerere.rikkahub.data.container.TerminalPipelineStage
import me.rerere.rikkahub.data.container.TerminalPipelineTrace

/** No extra draw/semantics node in ordinary usage. Install capture before mounting the page. */
internal fun Modifier.terminalPipelineDraw(processId: String, revision: Long, virtual: Boolean): Modifier =
    if (!TerminalPipelineTrace.isRecording(processId)) this else testTag("terminal-trace-output").drawWithContent {
        drawContent()
        TerminalPipelineTrace.record(processId, TerminalPipelineStage.FRAME_DRAWN,
            frameRevision = revision, virtual = virtual)
    }
