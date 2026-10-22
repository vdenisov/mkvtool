package org.plukh.mkvtool.core

import java.io.File

/**
 * Locating the MKVToolNix executables.
 *
 * This is pure resolution logic — no presentation — so it lives in `core`; the caller catches
 * [MkvToolNotFoundException] and decides what to say and which exit code to use. `propedit` resolves
 * `mkvpropedit` through it, `mux` and `inspect` resolve `mkvmerge`.
 */

/**
 * Raised when a tool cannot be run. [message] is the whole sentence — the caller renders it (with the
 * shared `*** Error: ` prefix) and exits 2.
 */
class MkvToolNotFoundException(message: String) : RuntimeException(message)

/**
 * Resolve an MKVToolNix executable [name] (e.g. `mkvpropedit`): try `PATH` first by probing
 * `<name> --version`, then — on Windows only — the default install location
 * `C:\Program Files\MKVToolNix\<name>.exe`. Returns the bare name on a `PATH` hit (so the OS resolves
 * it at run time) or the full path on the fallback. Throws [MkvToolNotFoundException] when neither works.
 */
fun findMkvTool(name: String): String {
    if (runs(name, "--version")) return name

    if (System.getProperty("os.name").lowercase().contains("win")) {
        val path = "C:\\Program Files\\MKVToolNix\\$name.exe"
        if (File(path).exists()) return path
    }

    throw MkvToolNotFoundException("'$name' not found on PATH or in default install location. Install MKVToolNix.")
}

/**
 * The mkvmerge to run: [configured] (`general.mkvmergeExe`) when set, else whatever [findMkvTool] finds.
 *
 * A configured one is probed rather than trusted, so a stale path fails here, before anything is read or
 * written. It is never replaced by auto-detection: a config naming a specific build means that build.
 * Throws [MkvToolNotFoundException] when the chosen executable cannot be run.
 */
fun resolveMkvmerge(configured: String?): String {
    if (configured == null) return findMkvTool("mkvmerge")
    if (runs(configured, "--version")) return configured
    throw MkvToolNotFoundException(
        "general.mkvmergeExe '$configured' cannot be run. Fix the path, or remove it to detect mkvmerge " +
            "automatically.",
    )
}

/**
 * Whether [exe] run with [args] exits 0. Output is discarded, as v1 did, and a launch failure is simply
 * false, so the caller's fallback gets its turn.
 */
internal fun runs(exe: String, vararg args: String): Boolean =
    try {
        ProcessBuilder(exe, *args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (_: Exception) {
        false
    }
