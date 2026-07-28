package org.plukh.mkvtool.e2e.support

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Running the binary under test.
 *
 * This tier exists to exercise a *packaged* mkvtool — the installDist launcher, the fat jar through its
 * generated launcher, or the native binary — so everything here goes through a subprocess. Which one is
 * named by the `mkvtool.bin` system property, set by the `e2eTest` Gradle task from `-PmkvtoolBin`.
 */

/** What a run said: its exit code, and stdout and stderr interleaved as the console would show them. */
data class Run(val exitCode: Int, val output: String)

/**
 * The binary under test. Absolute, because every case runs in a temp directory of its own and a relative
 * path would resolve against the wrong place.
 */
val mkvtoolBin: File = File(
    System.getProperty("mkvtool.bin")
        ?: error("mkvtool.bin is not set — run this tier through `./gradlew e2eTest`"),
)

/** The one input every media fixture is derived from. */
val testMkv: File = File(
    System.getProperty("mkvtool.testMkv")
        ?: error("mkvtool.testMkv is not set — run this tier through `./gradlew e2eTest`"),
)

/**
 * Run one mkvtool subcommand in [workDir].
 *
 * Four details here are load-bearing rather than incidental, and all four are inherited from the Groovy
 * suite this tier replaces:
 *
 * - **stderr is merged into stdout**, so a case asserts on what a user would see in one stream, in order.
 *   The two are also drained on this thread *before* `waitFor`, because two undrained pipes is how a
 *   chatty child fills a buffer and blocks forever.
 * - **`JAVA_HOME` is forced** to the JVM running this suite. The generated launchers prefer it over a
 *   bare `java` on `PATH`, and pinning it is what keeps the fat-jar target running on the same JDK the
 *   tests do.
 * - **A `.bat` target is routed through `cmd /c`**, because `ProcessBuilder` cannot launch a batch file
 *   directly; anything else — a shell script, a native `.exe` — is launched as it stands.
 * - **The child's output is decoded as explicit UTF-8.** The Groovy suite used the platform default,
 *   which on a Windows console is a legacy codepage; this tier asserts on non-ASCII output, so the
 *   charset is a decision rather than an inheritance.
 */
fun mkvtool(
    subcommand: String,
    vararg args: String,
    workDir: File,
    env: Map<String, String> = emptyMap(),
): Run {
    val prefix = if (mkvtoolBin.name.lowercase().endsWith(".bat")) listOf("cmd", "/c") else emptyList()
    return exec(prefix + mkvtoolBin.absolutePath + subcommand + args, workDir, env)
}

/** Run an arbitrary command, on the same terms as [mkvtool]. Used for mkvmerge and mkvpropedit. */
fun exec(command: List<String>, workDir: File? = null, env: Map<String, String> = emptyMap()): Run {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
    builder.environment().putAll(env)
    workDir?.let { builder.directory(it) }

    val process = builder.start()
    val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    return Run(process.waitFor(), output)
}
