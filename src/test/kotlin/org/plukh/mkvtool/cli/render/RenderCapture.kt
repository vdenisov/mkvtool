package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Runs one result through one renderer over captured streams, returning `(stdout, stderr)`. Shared by
 * every renderer spec, since the routing half of what they assert — which stream a line lands on — needs
 * the two streams kept apart.
 */
internal fun <T : CommandResult> renderResult(
    renderer: ResultTextRenderer<T>,
    result: T,
    colorEnabled: Boolean = false,
): Pair<String, String> {
    val outBytes = ByteArrayOutputStream()
    val errBytes = ByteArrayOutputStream()
    val style = TextStyle(
        colorEnabled = colorEnabled,
        out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
        err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
    )
    renderer.render(result, style)
    return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
}
