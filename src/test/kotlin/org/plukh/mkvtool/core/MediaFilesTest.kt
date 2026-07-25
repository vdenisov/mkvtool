package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The extension rule and the file masks, in-process over temp directories.
 *
 * The mask cases mirror harness cases 39-45, which pass their patterns through `ProcessBuilder` precisely
 * so no shell pre-expands them — the same reason the expansion lives in [compileMasks] at all. Getting
 * this wrong passes a Unix-only CI and fails on Windows.
 */
class MediaFilesTest : FunSpec({

    context("extensionOf") {
        data class Case(val name: String, val expected: String)

        withData(
            nameFn = { "${it.name} -> '${it.expected}'" },
            Case("Show.mkv", "mkv"),
            Case("Show.MKV", "mkv"),
            Case("Show.S01E01.mkv", "mkv"),
            Case("noextension", ""),
            // commons-io reads a dot-file as all extension, and v1 inherited that: `.mkv` is media.
            Case(".mkv", "mkv"),
            Case("trailing.", ""),
        ) { extensionOf(it.name) shouldBe it.expected }
    }

    context("compileMasks") {
        test("expands a glob itself, since cmd.exe passes the literal through") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv", "notes.txt").forEach { File(dir, it).writeText("x") }

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("*.mkv"))

            selection.selected.map { it.name } shouldContainExactly listOf("a.mkv", "b.mkv")
        }

        test("a pattern naming an existing file is matched literally, not as a glob") {
            val dir = tempdir()
            // As a glob, `Odd[1].mkv` is a character class and also matches `Odd1.mkv` — so a file whose
            // own name holds metacharacters could never be selected without this rule.
            listOf("Odd[1].mkv", "Odd1.mkv").forEach { File(dir, it).writeText("x") }

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("Odd[1].mkv"))

            selection.selected.map { it.name } shouldContainExactly listOf("Odd[1].mkv")
        }

        test("the same pattern is a glob again once no such file exists") {
            val dir = tempdir()
            File(dir, "Odd1.mkv").writeText("x")

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("Odd[1].mkv"))

            selection.selected.map { it.name } shouldContainExactly listOf("Odd1.mkv")
        }

        test("several masks union") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv", "c.mkv").forEach { File(dir, it).writeText("x") }

            val selection =
                selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("a.mkv", "c.*"))

            selection.selected.map { it.name } shouldContainExactly listOf("a.mkv", "c.mkv")
        }

        test("an exclude mask drops what it matches, and beats an include") {
            val dir = tempdir()
            listOf("a.mkv", "a.sample.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }

            val selection = selectMedia(
                dir, DEFAULT_ALLOWED_EXTENSIONS,
                fileMasks = listOf("*.mkv"), excludeMasks = listOf("*.sample.mkv"),
            )

            selection.selected.map { it.name } shouldContainExactly listOf("a.mkv", "b.mkv")
        }

        test("a backslash separator is folded, so a Windows-typed pattern still compiles") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            // Nothing here can match a pattern with a separator — the matcher sees bare names — but it
            // must compile rather than throw on the Windows escape.
            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("""sub\*.mkv"""))

            selection.selected.shouldBeEmpty()
        }

        test("a mask matching nothing selects nothing") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("*.mp4")).selected.shouldBeEmpty()
        }
    }

    context("selectMedia") {
        test("`all` is pre-mask while `selected` is post-mask") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("a.mkv"))

            // Discovery runs over `all`: narrowing to one episode must not turn the rest of the season's
            // companions into "unmatched".
            selection.all.map { it.name } shouldContainExactly listOf("a.mkv", "b.mkv")
            selection.selected.map { it.name } shouldContainExactly listOf("a.mkv")
        }

        test("both lists are name-sorted") {
            val dir = tempdir()
            listOf("c.mkv", "a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).selected.map { it.name } shouldContainExactly
                listOf("a.mkv", "b.mkv", "c.mkv")
        }

        test("non-media files are never selected, mask or no mask") {
            val dir = tempdir()
            listOf("a.mkv", "notes.txt", "cover.jpg").forEach { File(dir, it).writeText("x") }

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).selected.map { it.name } shouldContainExactly
                listOf("a.mkv")
            // Even named outright: the mask narrows the media files, it does not add to them.
            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("notes.txt"))
                .selected.shouldBeEmpty()
        }

        test("directories are not files, however they are named") {
            val dir = tempdir()
            File(dir, "season.mkv").mkdirs()
            File(dir, "a.mkv").writeText("x")

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).selected.map { it.name } shouldContainExactly
                listOf("a.mkv")
        }

        test("the listing is top level only") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            File(dir, "Rus sound").mkdirs()
            File(dir, "Rus sound/b.mkv").writeText("x")

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).all.map { it.name } shouldContainExactly
                listOf("a.mkv")
        }

        test("the extension test is case-insensitive") {
            val dir = tempdir()
            File(dir, "Show.MKV").writeText("x")

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).selected.map { it.name } shouldContainExactly
                listOf("Show.MKV")
        }

        test("a configured extension set replaces the defaults outright") {
            val dir = tempdir()
            listOf("a.mkv", "b.mp4").forEach { File(dir, it).writeText("x") }

            selectMedia(dir, setOf("mp4")).selected.map { it.name } shouldContainExactly listOf("b.mp4")
        }

        test("an empty directory selects nothing") {
            selectMedia(tempdir(), DEFAULT_ALLOWED_EXTENSIONS).let {
                it.all.shouldBeEmpty()
                it.selected.shouldBeEmpty()
            }
        }
    }
})
