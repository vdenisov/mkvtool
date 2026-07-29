package org.plukh.mkvtool.e2e.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The staging and output-inspection helpers.
 *
 * These landed with the tier's foundation and went untested, on the reasonable ground that the three proof
 * cases exercised them. That stops being enough once every remaining translation rests on them: the ones
 * below are small enough that a defect would be a surprising one, and small enough that pinning them costs
 * almost nothing - and two of them (a staged path carrying directories, an empty destination directory)
 * have edges that a case would otherwise discover the hard way.
 */
class FixturesTest : FunSpec({

    context("stageInput") {
        test("copies the fixture under the requested name") {
            val workDir = tempdir()
            val staged = stageInput(workDir, "Show.S01E01.mkv")

            staged.name shouldBe "Show.S01E01.mkv"
            staged.length() shouldBe testMkv.length()
        }

        test("a name carrying directories creates them") {
            val workDir = tempdir()
            // stageTree relies on this for `extras/Sample.mkv`, and it is the kind of thing that works
            // until a helper is rewritten with a plain copy.
            val staged = stageInput(workDir, "extras/Sample.mkv")

            staged.exists() shouldBe true
            staged.relativeTo(workDir).path.replace('\\', '/') shouldBe "extras/Sample.mkv"
        }

        test("the default name is the fixture's own") {
            stageInput(tempdir()).name shouldBe "test.mkv"
        }
    }

    test("writeConfig always writes config.yaml, as UTF-8") {
        val workDir = tempdir()
        // Named rather than parameterised because that is where mux reads it from - a config anywhere
        // else is not a config.
        val file = writeConfig(workDir, "general:\n  title: \"Шоу\"\n")

        file.name shouldBe "config.yaml"
        file.readBytes() shouldBe "general:\n  title: \"Шоу\"\n".toByteArray(StandardCharsets.UTF_8)
    }

    context("writeBytes and writeText") {
        test("bytes land on disk exactly as given") {
            val workDir = tempdir()
            val bytes = UTF8_BOM + "hi".toByteArray(StandardCharsets.UTF_8)

            writeBytes(workDir, "bom.srt", bytes).readBytes() shouldBe bytes
        }

        test("text is encoded in the charset named, not the default") {
            val workDir = tempdir()
            val windows1251: Charset = Charset.forName("windows-1251")
            // The whole point of the to-utf8 cases: what is on disk is legacy single-byte, and a file
            // written as UTF-8 by accident would make the command a no-op and the case meaningless.
            val file = writeText(workDir, "cyr.srt", "Привет", windows1251)

            file.readBytes() shouldBe "Привет".toByteArray(windows1251)
            file.readBytes().size shouldBe 6
        }

        test("the byte-order marks are the ones the charset cases build on") {
            UTF8_BOM.toList() shouldContainExactly listOf(0xEF, 0xBB, 0xBF).map { it.toByte() }
            UTF16_BOM.toList() shouldContainExactly listOf(0xFF, 0xFE).map { it.toByte() }
        }
    }

    context("findOutput and outputNames") {
        test("they see what a mux run left in the destination directory") {
            val workDir = tempdir()
            stageInput(workDir, "mkv/Show.S01E02.mkv")
            stageInput(workDir, "mkv/Show.S01E01.mkv")

            findOutput(workDir).shouldNotBeNullAnd { it.name.endsWith(".mkv") shouldBe true }
            // Sorted, so a batch assertion does not depend on the order the filesystem lists them in.
            outputNames(workDir) shouldContainExactly listOf("Show.S01E01.mkv", "Show.S01E02.mkv")
        }

        test("an absent destination directory is empty, not an error") {
            val workDir = tempdir()

            // What the "nothing was muxed" cases assert, and the reason outputNames answers with a list
            // rather than a null.
            findOutput(workDir).shouldBeNull()
            outputNames(workDir) shouldContainExactly emptyList()
        }

        test("a non-mkv file in the destination directory is not an output") {
            val workDir = tempdir()
            File(workDir, "mkv").mkdirs()
            File(workDir, "mkv/notes.txt").writeText("stray", StandardCharsets.UTF_8)

            // The one divergence from the Groovy original, which listed every name it found. Every
            // assertion resting on this helper is about muxed output; a case wanting to prove the
            // directory holds nothing else needs a plain listing.
            outputNames(workDir) shouldContainExactly emptyList()
        }

        test("a destination directory other than mkv can be named") {
            val workDir = tempdir()
            stageInput(workDir, "out/Show.S01E01.mkv")

            outputNames(workDir, destDir = "out") shouldContainExactly listOf("Show.S01E01.mkv")
        }
    }
})

/** Assert on a value that must not be null, without repeating the null check in every case. */
private inline fun <T : Any> T?.shouldNotBeNullAnd(block: (T) -> Unit) {
    checkNotNull(this) { "expected a value, got null" }
    block(this)
}
