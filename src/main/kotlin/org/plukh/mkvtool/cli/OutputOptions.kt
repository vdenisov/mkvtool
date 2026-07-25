package org.plukh.mkvtool.cli

import org.plukh.mkvtool.cli.render.textResultRenderers
import org.plukh.mkvtool.out.RenderHints
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.TextRenderer
import org.plukh.mkvtool.out.colorModeOf
import picocli.CommandLine.Option

/**
 * The options every command shares about its own output, and the one place a rendering medium is chosen.
 *
 * Mixed into each command (`@Mixin`) so the option is declared once rather than copied, and — the reason
 * this exists rather than a bare option — so [renderer] is the single site that decides *what kind* of
 * renderer the process uses. When `--json` lands it is a flag here and a branch in [renderer]; no command
 * changes, which is the whole point of the seam.
 *
 * A command may pass [RenderHints] to tune presentation and may do nothing else about it: which renderer
 * draws which result is the binding table's business (see `render/TextResultRenderers.kt`).
 */
class OutputOptions {

    @Option(
        names = ["--color"],
        paramLabel = "WHEN",
        description = ["Colorize output: auto (default, only on a terminal and not under NO_COLOR), always, or never"],
    )
    var color: String = "auto"

    /** The renderer this invocation writes through — diagnostics and results alike. */
    fun renderer(hints: RenderHints = RenderHints()): Renderer =
        TextRenderer(colorModeOf(color), results = textResultRenderers(hints))
}
