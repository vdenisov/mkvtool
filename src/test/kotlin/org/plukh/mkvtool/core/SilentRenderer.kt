package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.OutputEvent
import org.plukh.mkvtool.out.ProgressHandle
import org.plukh.mkvtool.out.Renderer

/**
 * A renderer that discards everything, for the orchestrators that take one.
 *
 * Per the seam, a core's assertion surface is the model it returns — not what it emitted along the way,
 * which is the renderer tier's business. Every spec in this package that runs an orchestrator wants the
 * same do-nothing renderer, so it is declared once.
 */
internal object SilentRenderer : Renderer {
    override fun render(event: OutputEvent) {}

    override fun render(result: CommandResult) {}

    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle =
        object : ProgressHandle {
            override fun tick() {}
            override fun finish() {}
        }
}
