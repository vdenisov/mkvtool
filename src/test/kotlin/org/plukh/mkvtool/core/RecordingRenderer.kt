package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.OutputEvent
import org.plukh.mkvtool.out.ProgressHandle
import org.plukh.mkvtool.out.Renderer

/**
 * A renderer that remembers what it was told, for the few assertions that are genuinely about the
 * diagnostics channel rather than the returned model — an advisory's wording, or whether the progress
 * meter's total matched the work that actually ran.
 *
 * The companion of [SilentRenderer], and declared once for the same reason: the specs in this package
 * kept growing private copies of it under different names.
 */
internal class RecordingRenderer : Renderer {
    val events = mutableListOf<OutputEvent>()

    /** Results in emission order, for the specs that care *when* a child was emitted rather than what it
     *  says — a per-file result arriving after its header, and before the next file's. */
    val results = mutableListOf<CommandResult>()

    /** What the meter was told to expect, or -1 when no meter was ever started. */
    var progressTotal: Int = -1
        private set

    var ticks: Int = 0
        private set

    override fun render(event: OutputEvent) {
        events += event
    }

    override fun render(result: CommandResult) {
        results += result
    }

    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle {
        progressTotal = total
        return object : ProgressHandle {
            override fun tick() {
                ticks++
            }

            override fun finish() {}
        }
    }
}
