package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.findUnusedFonts
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool find-unused-fonts` — list fonts in `fonts/` that no `.ass` subtitle in the current directory
 * references. A port of `src/find_unused_fonts.groovy`. Thin by design: takes its renderer from
 * [OutputOptions] and delegates to [findUnusedFonts]. Always exits 0 — this is a reporter, not a batch
 * operation with a failure count.
 */
@Command(
    name = "find-unused-fonts",
    mixinStandardHelpOptions = true,
    description = ["List fonts in fonts/ not referenced by any .ass subtitle in the current directory."],
)
class FindUnusedFontsCommand : Callable<Int> {

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        findUnusedFonts(File("."), output.renderer())
        return 0
    }
}
