package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.MkvToolNotFoundException
import org.plukh.mkvtool.core.findMkvTool
import org.plukh.mkvtool.core.retitleDirectory
import org.plukh.mkvtool.out.Error
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool filename-to-title` — set each `.mkv`'s segment title and video track name from its file
 * name, via mkvpropedit. A port of `src/filename_to_title.groovy`.
 *
 * v1 parsed no arguments at all (an implementation consequence, not a contract as it is in `propedit`,
 * where an intercepted option would break the passthrough): it silently ignored anything passed. This
 * port declares `--color` and standard help, as `find-unused-fonts` does — `--color auto` is the
 * default, so the v1 path is the default one — which also means a stray argument is now a picocli usage
 * error. No harness case passes arguments here.
 *
 * Exit codes: 2 when mkvpropedit is not found (before touching anything), 1 if any file failed, else 0.
 */
@Command(
    name = "filename-to-title",
    mixinStandardHelpOptions = true,
    description = ["Set each .mkv's segment title and video track name from its file name."],
)
class FilenameToTitleCommand : Callable<Int> {

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer()

        val exe = try {
            findMkvTool("mkvpropedit")
        } catch (e: MkvToolNotFoundException) {
            renderer.render(Error(e.message!!))
            return 2
        }

        val run = retitleDirectory(File("."), exe, renderer)
        // Non-zero on failure so this is usable from a shell script (matching v1's exit discipline).
        return if (run.failed > 0) 1 else 0
    }
}
