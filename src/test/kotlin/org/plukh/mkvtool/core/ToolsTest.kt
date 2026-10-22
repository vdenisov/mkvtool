package org.plukh.mkvtool.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The not-found paths, which are deterministic on every OS: a clearly-bogus name is neither on PATH nor at
 * the Windows default location, and a path into an empty directory names nothing to run. The positive path
 * (a real tool resolves) needs a real MKVToolNix install and is covered by the end-to-end tier.
 */
class ToolsTest : FunSpec({

    test("an unresolvable tool throws with the verbatim v1 message") {
        val e = shouldThrow<MkvToolNotFoundException> {
            findMkvTool("mkvtool-definitely-not-a-real-executable-xyz")
        }
        e.message shouldBe
            "'mkvtool-definitely-not-a-real-executable-xyz' not found on PATH or in default install location. Install MKVToolNix."
    }

    test("a configured mkvmerge that cannot be run throws, naming it, rather than falling back to PATH") {
        val configured = File(tempdir(), "mkvmerge").absolutePath
        val e = shouldThrow<MkvToolNotFoundException> { resolveMkvmerge(configured) }
        e.message shouldBe
            "general.mkvmergeExe '$configured' cannot be run. Fix the path, or remove it to detect mkvmerge " +
            "automatically."
    }
})
