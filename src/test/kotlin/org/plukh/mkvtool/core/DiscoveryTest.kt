package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The tree walk and the companion matcher, in-process over temp directories.
 *
 * Every expectation here was taken from the v1 engine itself rather than from a reading of it: the same
 * fixtures were run through `src/lib/discovery.groovy` and its answer — variant order, labels, adopted
 * suffixes, section aggregates, leftovers — is what the assertions pin. Discovery probes nothing, so the
 * fixture files are one byte of text each and no mkvmerge is involved.
 *
 * The matching cases mirror harness case `110_discovery_engine_matching`; the tree fixture mirrors the
 * harness's shared `stageTree`, whose rendered form cases 111-115 assert.
 */
class DiscoveryTest : FunSpec({

    context("trimForDisplay") {
        data class Case(val label: String, val suffix: String?, val expected: String?)

        withData(
            nameFn = { it.label },
            Case("leading punctuation goes", ".rus", "rus"),
            Case("brackets go from both ends", "[Studio]", "Studio"),
            Case("a leading space goes", " 2", "2"),
            // Only the *trailing* run is trimmed, so an unbalanced brace inside the suffix survives.
            Case("punctuation inside the suffix stays", "!odd{sep}", "odd{sep"),
            Case("an empty suffix stays empty", "", ""),
            Case("null passes through", null, null),
        ) { trimForDisplay(it.suffix) shouldBe it.expected }
    }

    context("typeClassOf") {
        data class Case(val ext: String, val expected: CompanionType)

        withData(
            nameFn = { "${it.ext} is ${it.expected}" },
            Case("mka", CompanionType.AUDIO),
            Case("flac", CompanionType.AUDIO),
            Case("ac3", CompanionType.AUDIO),
            Case("ass", CompanionType.SUBTITLES),
            Case("srt", CompanionType.SUBTITLES),
            Case("idx", CompanionType.SUBTITLES),
            // Not a companion extension at all, but the classifier has no opinion about that.
            Case("mkv", CompanionType.SUBTITLES),
        ) { typeClassOf(it.ext) shouldBe it.expected }
    }

    context("walkTree") {
        test("orders children by name, recursing into a subdirectory in its sorted position") {
            // 'a' sorts before 'a.srt', so the subdirectory's contents come first — the walk interleaves
            // directories and files rather than listing all files then descending. That order decides
            // which entry names a variant, so it is behavior, not incidental.
            val dir = tempdir()
            stageAll(dir, "b.srt", "a/inner.srt", "a.srt", "UPPER.SRT", "noext")

            walkTree(dir).map { it.relPath } shouldContainExactly
                listOf("UPPER.SRT", "a/inner.srt", "a.srt", "b.srt", "noext")
        }

        test("derives the relative path, directory, leaf, base name and lower-cased extension") {
            val dir = tempdir()
            stageAll(dir, "UPPER.SRT", "Rus sound/[GroupA]/dub.mka", "noext")
            val byPath = walkTree(dir).associateBy { it.relPath }

            val top = byPath.getValue("UPPER.SRT")
            top.dirRel shouldBe ""
            top.leaf shouldBe null
            top.base shouldBe "UPPER"
            top.ext shouldBe "srt"

            val nested = byPath.getValue("Rus sound/[GroupA]/dub.mka")
            nested.dirRel shouldBe "Rus sound/[GroupA]"
            nested.leaf shouldBe "[GroupA]"
            nested.base shouldBe "dub"
            nested.ext shouldBe "mka"

            val bare = byPath.getValue("noext")
            bare.base shouldBe "noext"
            bare.ext shouldBe ""
        }

        test("skips dot-directories") {
            val dir = tempdir()
            stageAll(dir, "keep.srt", ".stash/hidden.srt")

            walkTree(dir).map { it.relPath } shouldContainExactly listOf("keep.srt")
        }

        test("skips excluded directories by canonical path") {
            // What destinationDir exclusion rests on: muxed output carries its sources' base names and
            // would otherwise come back as an external file of the episode it was made from.
            val dir = tempdir()
            stageAll(dir, "keep.srt", "mkv/output.srt")

            walkTree(dir, setOf(File(dir, "mkv").canonicalPath)).map { it.relPath } shouldContainExactly
                listOf("keep.srt")
        }

        test("never tests the root against the exclusions, so excluding it does nothing") {
            // v1 behavior, reproduced: the guard runs on child directories only. No caller does this.
            val dir = tempdir()
            stageAll(dir, "keep.srt", "sub/other.srt")

            walkTree(dir, setOf(dir.canonicalPath)).map { it.relPath } shouldContainExactly
                listOf("keep.srt", "sub/other.srt")
        }

        test("a directory loop terminates instead of spinning forever") {
            val dir = tempdir()
            val loop = File(dir, "loop").apply { mkdir() }
            stage(dir, "loop/real.srt")
            val linked = try {
                Files.createSymbolicLink(File(loop, "self").toPath(), loop.toPath())
                true
            } catch (_: IOException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
            if (!linked) {
                // Windows needs Developer Mode or elevation to create one; skip rather than fail, the
                // same shape the Groovy harness uses for a missing mkvpropedit.
                println("  (skipped: this JVM cannot create a symbolic link here)")
                return@test
            }

            walkTree(dir).map { it.relPath } shouldContainExactly listOf("loop/real.srt")
        }
    }

    context("discoverCompanions matching rules") {
        data class Case(
            val label: String,
            val fileName: String,
            val main: String,
            val tier: MatchTier,
            val suffix: String?,
        )

        withData(
            nameFn = { it.label },
            Case(
                "the longest main name wins over a shorter one plus a suffix",
                "Show - S01E01 - Title 2.srt", "Show - S01E01 - Title 2.mkv", MatchTier.NAME, "",
            ),
            Case(
                "any separator starts a suffix",
                "Show - S01E01 - Title!odd{sep}.srt", "Show - S01E01 - Title.mkv", MatchTier.NAME, "!odd{sep}",
            ),
            Case(
                "a name that relates to no main is placed by its episode number alone",
                "Other Release S01E02.mka", "Show - S01E02 - Second.mkv", MatchTier.EPISODE, null,
            ),
        ) { case ->
            val dir = stageMatchingFixture(tempdir())
            val result = discover(dir, "Show - S01E01 - Title.mkv", "Show - S01E01 - Title 2.mkv", "Show - S01E02 - Second.mkv")

            val entry = result.variants.flatMap { it.entries }.single { it.entry.file.name == case.fileName }
            entry.main.name shouldBe case.main
            entry.tier shouldBe case.tier
            entry.suffix shouldBe case.suffix
        }

        test("nothing in the matching fixture is left over") {
            val dir = stageMatchingFixture(tempdir())

            val result = discover(dir, "Show - S01E01 - Title.mkv", "Show - S01E01 - Title 2.mkv", "Show - S01E02 - Second.mkv")

            result.unmatched.shouldBeEmpty()
            result.extras.shouldBeEmpty()
        }

        test("an episode two main files claim is ambiguous, so its companion stays unmatched") {
            // Ambiguous means unmatched, never guessed: E01 has two claimants, E02 has one.
            val dir = tempdir()
            stageAll(dir, "A S01E01.mkv", "B S01E01.mkv", "C S01E02.mkv", "Other S01E01.srt", "Late S01E02.srt")

            val result = discover(dir, "A S01E01.mkv", "B S01E01.mkv", "C S01E02.mkv")

            result.unmatched.map { it.relPath } shouldContainExactly listOf("Other S01E01.srt")
            val matched = result.variants.flatMap { it.entries }.single()
            matched.entry.relPath shouldBe "Late S01E02.srt"
            matched.tier shouldBe MatchTier.EPISODE
        }

        test("a main file with an empty base name adopts nothing") {
            // Not hypothetical pedantry: a file called ".mkv" has an empty base name, and every string
            // starts with the empty string, so without the guard it would claim the whole tree.
            val dir = tempdir()
            stageAll(dir, ".mkv", "Show - S01E01 - Title.mkv", "Orphan.srt", "Sub/Loose.ass")

            val result = discover(dir, ".mkv", "Show - S01E01 - Title.mkv")

            result.variants.shouldBeEmpty()
            result.unmatched.map { it.relPath } shouldContainExactly listOf("Orphan.srt", "Sub/Loose.ass")
        }
    }

    context("discoverCompanions variants") {
        test("groups the shared tree fixture into three variants, merging one across two directories") {
            val dir = stageTree(tempdir())

            val result = discover(dir, "Show - S01E01 - Title.mkv", "Show - S01E02 - Title.mkv", "Show - S01E03 - Title.mkv")

            result.variants.map { it.label } shouldContainExactly listOf("A", "B", "C")

            // A — the suffixed sibling in the media directory itself. Its files sit at the top level, so
            // its empty dirRel sorts it before both directory variants.
            val sibling = result.variants[0]
            sibling.leaf shouldBe null
            sibling.suffix shouldBe ".rus"
            sibling.firstSuffix shouldBe ".rus"
            sibling.firstDir shouldBe ""
            sibling.collision shouldBe false
            sibling.type shouldBe CompanionType.SUBTITLES
            sibling.dirs.shouldBeEmpty()
            sibling.extensions shouldContainExactly listOf("srt")
            sibling.entries.map { it.entry.relPath } shouldContainExactly listOf("Show - S01E01 - Title.rus.srt")

            val siblingSection = sibling.sections.single()
            siblingSection.type shouldBe CompanionType.SUBTITLES
            siblingSection.dir shouldBe null
            siblingSection.suffix shouldBe ".rus"
            siblingSection.extensions shouldContainExactly listOf("srt")

            // B — the merge case: one leaf name under two category directories is one dub group holding
            // both kinds of file, which is what makes the report read the way the directory was meant to.
            val merged = result.variants[1]
            merged.leaf shouldBe "[GroupA]"
            merged.suffix shouldBe ""
            merged.collision shouldBe false
            merged.type shouldBe CompanionType.MIXED
            merged.dirs shouldContainExactly listOf("Rus sound/[GroupA]", "Rus subs/[GroupA]")
            merged.extensions shouldContainExactly listOf("ass", "mka")
            merged.entries shouldHaveSize 5

            merged.sections.map { it.type } shouldContainExactly
                listOf(CompanionType.AUDIO, CompanionType.SUBTITLES)

            val audio = merged.sections[0]
            audio.dir shouldBe "Rus sound/[GroupA]"
            audio.suffix shouldBe ""
            audio.extensions shouldContainExactly listOf("mka")
            audio.entries.map { it.entry.relPath } shouldContainExactly listOf(
                "Rus sound/[GroupA]/Show - S01E01 - Title.mka",
                "Rus sound/[GroupA]/Show - S01E02 - Title.mka",
                "Rus sound/[GroupA]/Show - S01E03 - Title.mka",
            )

            val subtitles = merged.sections[1]
            subtitles.dir shouldBe "Rus subs/[GroupA]"
            subtitles.suffix shouldBe ""
            subtitles.extensions shouldContainExactly listOf("ass")
            subtitles.entries.map { it.entry.relPath } shouldContainExactly listOf(
                "Rus subs/[GroupA]/Show - S01E01 - Title.ass",
                "Rus subs/[GroupA]/Show - S01E02 - Title.ass",
            )

            // C — the second dub group, covering one episode only.
            val second = result.variants[2]
            second.leaf shouldBe "[GroupB]"
            second.type shouldBe CompanionType.AUDIO
            second.entries.map { it.entry.relPath } shouldContainExactly
                listOf("Rus sound/[GroupB]/Show - S01E01 - Title.mka")

            // A companion-extension file that belongs to nothing, and a main-type file in a subdirectory.
            result.unmatched.map { it.relPath } shouldContainExactly listOf("Rus subs/[GroupA]/Bonus.ass")
            result.extras.map { it.relPath } shouldContainExactly listOf("extras/Sample.mkv")
        }

        test("a merge that would give one episode two files of the same kind is undone") {
            // Same leaf under two category directories, both supplying audio for E01: those directories
            // are not the same variant after all, so they split back apart and are named by their paths.
            val dir = tempdir()
            stageAll(
                dir,
                "Show - S01E01 - Title.mkv",
                "Rus sound/[G]/Show - S01E01 - Title.mka",
                "Jpn sound/[G]/Show - S01E01 - Title.mka",
            )

            val result = discover(dir, "Show - S01E01 - Title.mkv")

            result.variants shouldHaveSize 2
            result.variants.all { it.collision } shouldBe true
            result.variants.all { it.leaf == "[G]" } shouldBe true
            result.variants.map { it.firstDir } shouldContainExactly listOf("Jpn sound/[G]", "Rus sound/[G]")
        }

        test("an episode-number match adopts the leaf's only suffix, without renaming the variant") {
            // The looser tier exists for exactly this: one file in a set named differently from its
            // siblings. It joins their variant rather than forming a twin of it — but v1 builds the
            // display name from the first entry in *discovery* order, which here is the suffix-less one,
            // so the adopted suffix is not reflected in firstSuffix. Reproduced, not repaired.
            val dir = tempdir()
            stageAll(
                dir,
                "Show - S01E01 - Title.mkv",
                "Show - S01E02 - Title.mkv",
                "Grp/AAA S01E01.mka",
                "Grp/Show - S01E02 - Title.sfx.mka",
            )

            val result = discover(dir, "Show - S01E01 - Title.mkv", "Show - S01E02 - Title.mkv")

            val variant = result.variants.single()
            variant.leaf shouldBe "Grp"
            variant.suffix shouldBe ".sfx"
            variant.firstSuffix shouldBe null
            variant.firstDir shouldBe "Grp"
            variant.entries.map { it.tier } shouldContainExactly listOf(MatchTier.EPISODE, MatchTier.NAME)
            // The section reads the first *non-null* suffix, so the pattern it feeds keeps the real one.
            variant.sections.single().suffix shouldBe ".sfx"
        }

        test("an episode-number match in a leaf with no name matches keeps no suffix") {
            val dir = tempdir()
            stageAll(dir, "Show - S01E02 - Second.mkv", "Rus sound/[X]/Other Release S01E02.mka")

            val result = discover(dir, "Show - S01E02 - Second.mkv")

            val variant = result.variants.single()
            variant.leaf shouldBe "[X]"
            variant.suffix shouldBe null
            variant.sections.single().suffix shouldBe null
        }

        test("a variant guesses its language from its suffix first, then its directories leaf-upwards") {
            // The precedence is what makes a merged group readable: "[Омикрон]" says nothing, so the
            // category directory above it answers. A suffix, when there is one, outranks both.
            val dir = tempdir()
            stageAll(
                dir,
                "Show - S01E01 - Title.mkv",
                "Rus subs/[Омикрон]/Show - S01E01 - Title.ass",
                "Show - S01E01 - Title.jpn.srt",
                "Extras (to be done)/Show - S01E01 - Title.srt",
            )

            val result = discover(dir, "Show - S01E01 - Title.mkv")
            val byLeafOrSuffix = result.variants.associateBy { it.leaf ?: it.firstSuffix }

            byLeafOrSuffix.getValue("[Омикрон]").languageGuess shouldBe "rus"
            byLeafOrSuffix.getValue(".jpn").languageGuess shouldBe "jpn"
            byLeafOrSuffix.getValue("Extras (to be done)").languageGuess shouldBe null
        }

        test("labels run past Z into AA") {
            val dir = tempdir()
            stage(dir, "Main.mkv")
            (0..26).forEach { stage(dir, "Main-%02d.srt".format(it)) }

            val result = discover(dir, "Main.mkv")

            result.variants shouldHaveSize 27
            result.variants.map { it.label }.takeLast(3) shouldContainExactly listOf("Y", "Z", "AA")
        }
    }

    context("discoverCompanions leftovers") {
        test("a main-type file counts as an extra only in a subdirectory") {
            // A stray at the top level is neither an extra (that rule needs a subdirectory) nor unmatched
            // (its extension is not a companion one), so it is dropped silently. Latent v1 quirk.
            val dir = tempdir()
            stageAll(dir, "Show - S01E01 - Title.mkv", "Stray.mkv", "extras/Sample.mkv", "notes.txt", "sub/notes.txt")

            val result = discover(dir, "Show - S01E01 - Title.mkv")

            result.extras.map { it.relPath } shouldContainExactly listOf("extras/Sample.mkv")
            result.unmatched.shouldBeEmpty()
        }

        test("no main extensions means no extras at all") {
            // How rename calls it: it wants the matches, not a report of what else is lying around.
            val dir = tempdir()
            stageAll(dir, "Show - S01E01 - Title.mkv", "extras/Sample.mkv", "Show - S01E01 - Title.srt")

            val result = discoverCompanions(
                listOf(File(dir, "Show - S01E01 - Title.mkv")),
                walkTree(dir),
            )

            result.extras.shouldBeEmpty()
            result.variants.single().entries.map { it.entry.relPath } shouldContainExactly
                listOf("Show - S01E01 - Title.srt")
        }
    }
})

/** Walk [dir] and match [mainNames] against it, with `mkv` as the only main-type extension. */
private fun discover(dir: File, vararg mainNames: String): DiscoveryResult =
    discoverCompanions(mainNames.map { File(dir, it) }, walkTree(dir), setOf("mkv"))

/** One file at a path relative to [dir], directories created as needed. The content is deliberately
 *  unimportant: discovery probes nothing, which is half of what these tests assert. */
private fun stage(dir: File, relPath: String): File =
    File(dir, relPath).apply {
        parentFile?.mkdirs()
        writeText("x")
    }

private fun stageAll(dir: File, vararg relPaths: String) = relPaths.forEach { stage(dir, it) }

/**
 * The harness's shared `stageTree` fixture, re-expressed: three episodes, two dub groups with different
 * coverage, one of them also supplying subtitles from a second category directory (the merge case), a
 * suffixed sibling in the media directory itself, one file belonging to nothing, and an extras folder
 * holding a stray main-type file.
 */
private fun stageTree(dir: File): File {
    for (episode in listOf("01", "02", "03")) {
        stage(dir, "Show - S01E$episode - Title.mkv")
        stage(dir, "Rus sound/[GroupA]/Show - S01E$episode - Title.mka")
    }
    for (episode in listOf("01", "02")) {
        stage(dir, "Rus subs/[GroupA]/Show - S01E$episode - Title.ass")
    }
    stageAll(
        dir,
        "Rus sound/[GroupB]/Show - S01E01 - Title.mka",
        "Show - S01E01 - Title.rus.srt",
        "Rus subs/[GroupA]/Bonus.ass",
        "extras/Sample.mkv",
    )
    return dir
}

/**
 * Harness case 110's matching fixture: two main files for E01, one of which is a name-extension of the
 * other, plus a companion whose own name relates to no main file at all.
 */
private fun stageMatchingFixture(dir: File): File {
    stageAll(
        dir,
        "Show - S01E01 - Title.mkv",
        "Show - S01E01 - Title 2.mkv",
        "Show - S01E02 - Second.mkv",
        "Show - S01E01 - Title 2.srt",
        "Show - S01E01 - Title!odd{sep}.srt",
        "Rus sound/[X]/Other Release S01E02.mka",
    )
    return dir
}
