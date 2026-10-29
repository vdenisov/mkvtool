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
 * The end-to-end mask cases pass their patterns through `ProcessBuilder` precisely so no shell pre-expands
 * them — the same reason the expansion lives in [compileMasks] at all. Getting this wrong passes a Unix-only
 * CI and fails on Windows.
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

        test("brackets are literal, so a bracketed name selects only itself") {
            val dir = tempdir()
            listOf("Odd[1].mkv", "Odd1.mkv").forEach { File(dir, it).writeText("x") }

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("*[1].mkv"))

            selection.selected.map { it.name } shouldContainExactly listOf("Odd[1].mkv")
        }

        test("a pattern naming no file is still no character class") {
            // A JDK glob would read [1] as a character class and select Odd1.mkv here.
            val dir = tempdir()
            File(dir, "Odd1.mkv").writeText("x")

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("Odd[1].mkv"))

            selection.selected.shouldBeEmpty()
        }

        // Windows forbids * and ? in a file name, so there the rule cannot be reached.
        test("a pattern naming an existing file with a wildcard in its name selects only that file")
            .config(enabledIf = { File.separatorChar == '/' }) {
                val dir = tempdir()
                listOf("E0?.mkv", "E01.mkv").forEach { File(dir, it).writeText("x") }

                val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("E0?.mkv"))

                selection.selected.map { it.name } shouldContainExactly listOf("E0?.mkv")
            }

        test("an exact name in another case still selects the file where names ignore case")
            .config(enabledIf = { File.separatorChar == '\\' }) {
                val dir = tempdir()
                File(dir, "Show.S01E02.mkv").writeText("x")

                val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("show.s01e02.mkv"))

                selection.selected.map { it.name } shouldContainExactly listOf("Show.S01E02.mkv")
            }

        test("a file in a subdirectory does not stand in for the same name at the top level") {
            val dir = tempdir()
            File(dir, "E01.mkv").writeText("x")
            File(dir, "extras").mkdir()
            File(dir, "extras/E01.mkv").writeText("x")

            val selection = selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("extras/E01.mkv"))

            selection.selected.shouldBeEmpty()
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

    context("maskRegex: *, ** and ? are the only wildcards") {
        data class Case(val pattern: String, val path: String, val matches: Boolean)

        withData(
            nameFn = { "${it.pattern} ${if (it.matches) "matches" else "does not match"} ${it.path}" },
            Case("*.mkv", "a.mkv", true),
            Case("*.mkv", "dir/a.mkv", false),
            Case("**.mkv", "dir/sub/a.mkv", true),
            Case("**/*.mka", "a.mka", true),
            Case("x**/b", "xb", false),
            Case("**.mkv", "a\nb.mkv", true),
            // Once invalid JDK globs that crashed the run; now plain literals.
            Case("*[OVA*", "S01E01[OVA x", true),
            Case("{{a", "{{a", true),
            Case("**/*.mka", "Rus sound/[MC-Ent]/a.mka", true),
            Case("Rus sound/*/*.mka", "Rus sound/[MC-Ent]/a.mka", true),
            Case("E0?.mkv", "E01.mkv", true),
            Case("E0?.mkv", "E0/.mkv", false),
            Case("*[MC-Ent].mka", "S01E01[MC-Ent].mka", true),
            Case("*[MC-Ent].mka", "S01E01M.mka", false),
            Case("{a,b}.mkv", "{a,b}.mkv", true),
            Case("{a,b}.mkv", "a.mkv", false),
            Case("a.b(1)+.mkv", "a.b(1)+.mkv", true),
            Case("a.mkv", "aXmkv", false),
            Case("Rus sound\\*.mka", "Rus sound/a.mka", true),
        ) { maskRegex(it.pattern).matches(it.path) shouldBe it.matches }

        test("case follows the platform's file names") {
            maskRegex("*.MKV").matches("a.mkv") shouldBe (File.separatorChar == '\\')
            // Beyond ASCII too: a Cyrillic episode name cased otherwise on disk is still the same file there.
            maskRegex("*РУССКИЙ*").matches("русский.mka") shouldBe (File.separatorChar == '\\')
        }

        test("a literal part of a glob matches as written, its * and ? and backslash included") {
            val glob = globRegex(listOf("Subs/" to true, "a*b?\\c" to false, " *.srt" to true))

            glob.matches("Subs/a*b?\\c [x].srt") shouldBe true
            glob.matches("Subs/aZZbYYc [x].srt") shouldBe false
        }

        test("a ** split by a literal part is no ** at all") {
            globRegex(listOf("*" to true, "*" to false, "x" to true)).matches("ab*x") shouldBe true
            globRegex(listOf("*" to true, "*" to false, "x" to true)).matches("a/b*x") shouldBe false
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

        test("`matched` is the masks' answer before the extension rule") {
            val dir = tempdir()
            listOf("a.mkv", "notes.txt", "cover.jpg").forEach { File(dir, it).writeText("x") }

            // `mux` walks this rather than `selected`, because it reports what it passes over. It is also
            // what tells the two empty batches apart: nothing matched at all (a typo'd pattern) against
            // matched something that is not media.
            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS).matched.map { it.name } shouldContainExactly
                listOf("a.mkv", "cover.jpg", "notes.txt")
        }

        test("`matched` narrows with the masks, and can hold nothing at all") {
            val dir = tempdir()
            listOf("a.mkv", "notes.txt").forEach { File(dir, it).writeText("x") }

            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("notes.txt"))
                .matched.map { it.name } shouldContainExactly listOf("notes.txt")
            selectMedia(dir, DEFAULT_ALLOWED_EXTENSIONS, fileMasks = listOf("Nope.*"))
                .matched.shouldBeEmpty()
        }

        test("an empty directory selects nothing") {
            selectMedia(tempdir(), DEFAULT_ALLOWED_EXTENSIONS).let {
                it.all.shouldBeEmpty()
                it.selected.shouldBeEmpty()
            }
        }
    }
})
