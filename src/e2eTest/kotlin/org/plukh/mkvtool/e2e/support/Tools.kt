package org.plukh.mkvtool.e2e.support

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import org.plukh.mkvtool.core.ProbeResult
import org.plukh.mkvtool.core.parseProbe
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The external tools this tier needs, and the guards that skip a case rather than failing it when one is
 * missing.
 *
 * Skipping is deliberate: `mkvpropedit` is genuinely optional on a developer machine, and a red case there
 * says nothing about the code. A missing *binary under test* is
 * the opposite — that is a setup mistake, and it fails loudly, naming the build step.
 */

private val onWindows = System.getProperty("os.name").lowercase().contains("win")

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

    if (onWindows) {
        val fallback = File("C:\\Program Files\\MKVToolNix\\$name.exe")
        if (fallback.isFile) return fallback.absolutePath
    }
    return null
}

val mkvmergeExe: String? by lazy { findMkvTool("mkvmerge") }
val mkvpropeditExe: String? by lazy { findMkvTool("mkvpropedit") }

// Set by runs whose environment always carries both tools, where a skip could only mean that environment
// broke — and a skipped case is one the run still reports green.
private val toolsRequired = System.getProperty("mkvtool.requireTools").toBoolean()

/** Skip a case that needs mkvmerge, saying why; under `mkvtool.requireTools`, run it to fail instead. */
val needsMkvmerge: (TestCase) -> Enabled = { needs("mkvmerge", mkvmergeExe) }

/** Skip a case that needs mkvpropedit, saying why; under `mkvtool.requireTools`, run it to fail instead. */
val needsMkvpropedit: (TestCase) -> Enabled = { needs("mkvpropedit", mkvpropeditExe) }

// Kotest reports an exception thrown from here as a plain skip, so a required tool that is missing lets the
// case run and fail at the first step that needs it.
private fun needs(tool: String, exe: String?): Enabled =
    if (exe != null || toolsRequired) Enabled.enabled else Enabled.disabled("$tool not available")

/**
 * Skip a case that has to hide MKVToolNix from mkvtool, which mkvtool's Windows default-location fallback
 * rules out.
 */
val posixOnly: (TestCase) -> Enabled = {
    if (onWindows) {
        Enabled.disabled("the Windows default-location fallback cannot be hidden from findMkvTool")
    } else {
        Enabled.enabled
    }
}

/**
 * A `PATH` value that puts a [name] exiting 1 ahead of the real tool, written into [dir]. That is the same
 * "not usable" branch an absent tool takes; `PATH` cannot simply be emptied, because the POSIX launchers
 * need sed, xargs and dirname. Pair it with [posixOnly].
 */
fun pathShadowing(name: String, dir: File): String {
    val stub = File(dir, name)
    stub.writeText("#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8)
    check(stub.setExecutable(true)) { "could not make $stub executable" }
    // A stub the system will not execute (a noexec temp directory) is passed over silently in favour of the
    // real tool, which would fail the case for a reason that has nothing to do with mkvtool.
    check(exec(listOf(stub.absolutePath)).exitCode == 1) { "$stub does not run; is $dir mounted noexec?" }
    return dir.absolutePath + File.pathSeparator + System.getenv("PATH")
}

/**
 * Probe [file] with the real mkvmerge and return the typed record.
 *
 * The *production* parser runs, so a case asserts `ProbedTrack.language` and any drift between what
 * mkvmerge emits and what the tool understands shows up as a test failure rather than as a map lookup
 * quietly returning null.
 */
fun probe(file: File): ProbeResult.Probed {
    val exe = mkvmergeExe ?: error("mkvmerge is required to probe $file")
    val run = exec(listOf(exe, "-J", file.absolutePath))
    check(run.exitCode == 0) { "mkvmerge -J failed on $file (exit ${run.exitCode}):\n${run.output}" }

    val result = parseProbe(file, run.output)
    check(result is ProbeResult.Probed) { "mkvmerge could not read $file: $result" }
    return result
}
