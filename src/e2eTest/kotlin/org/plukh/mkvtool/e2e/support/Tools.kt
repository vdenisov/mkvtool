package org.plukh.mkvtool.e2e.support

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import org.plukh.mkvtool.core.ProbeResult
import org.plukh.mkvtool.core.parseProbe
import java.io.File

/**
 * The external tools this tier needs, and the guards that skip a case rather than failing it when one is
 * missing.
 *
 * Skipping is the Groovy suite's behaviour and worth keeping: `mkvpropedit` is genuinely optional on a
 * developer machine, and a red case there says nothing about the code. A missing *binary under test* is
 * the opposite — that is a setup mistake, and it fails loudly, naming the build step.
 */

/**
 * Resolve an MKVToolNix executable: the bare name if it runs, then the Windows default install location.
 * Returns the name to invoke, or null.
 *
 * The bare name is returned rather than an absolute path when `PATH` resolves it, which is deliberate —
 * it is also what gets written into every generated `config.yaml`, and a config naming a bare `mkvmerge`
 * is what a user would write.
 */
private fun findMkvTool(name: String): String? {
    val runnable = try {
        ProcessBuilder(name, "--version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: java.io.IOException) {
        false
    }
    if (runnable) return name

    if (System.getProperty("os.name").lowercase().contains("win")) {
        val fallback = File("C:\\Program Files\\MKVToolNix\\$name.exe")
        if (fallback.isFile) return fallback.absolutePath
    }
    return null
}

val mkvmergeExe: String? by lazy { findMkvTool("mkvmerge") }
val mkvpropeditExe: String? by lazy { findMkvTool("mkvpropedit") }

/** Skip a case that needs mkvmerge, saying why. */
val needsMkvmerge: (TestCase) -> Enabled = {
    if (mkvmergeExe != null) Enabled.enabled else Enabled.disabled("mkvmerge not available")
}

/** Skip a case that needs mkvpropedit, saying why. */
val needsMkvpropedit: (TestCase) -> Enabled = {
    if (mkvpropeditExe != null) Enabled.enabled else Enabled.disabled("mkvpropedit not available")
}

/**
 * Probe [file] with the real mkvmerge and return the typed record.
 *
 * This is where the tier is straightforwardly better than the Groovy suite rather than merely tidier:
 * that one parsed `-J` into untyped maps and asserted `map.get('properties').language`, which is both
 * unreadable and unchecked. Here the *production* parser runs, so a case asserts `ProbedTrack.language`
 * and any drift between what mkvmerge emits and what the tool understands shows up as a test failure
 * rather than as a map lookup quietly returning null.
 */
fun probe(file: File): ProbeResult.Probed {
    val exe = mkvmergeExe ?: error("mkvmerge is required to probe $file")
    val run = exec(listOf(exe, "-J", file.absolutePath))
    check(run.exitCode == 0) { "mkvmerge -J failed on $file (exit ${run.exitCode}):\n${run.output}" }

    val result = parseProbe(file, run.output)
    check(result is ProbeResult.Probed) { "mkvmerge could not read $file: $result" }
    return result
}
