package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.fixDirectory
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool fix-srt` — reformat legacy-format `.srt` files in the current directory into `<name>.srt.fixed`.
 * A verbatim port of `src/fix_srt.groovy`. Thin by design: it takes its renderer from [OutputOptions] and
 * delegates to [fixDirectory], returning 1 if any file failed, else 0. Exit codes flow through picocli's
 * `Callable<Int>` return; there is no input-validation (exit-2) path here.
 */
@Command(
    name = "fix-srt",
    mixinStandardHelpOptions = true,
    description = ["Reformat legacy-format SRT files in the current directory into <name>.srt.fixed."],
)
class FixSrtCommand : Callable<Int> {

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer()
        val run = fixDirectory(File("."), renderer)
        // Non-zero on failure so this is usable from a shell script (matching v1's exit discipline).
        return if (run.failed > 0) 1 else 0
    }
}
