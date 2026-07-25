package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.FileRenamed
import org.plukh.mkvtool.core.PlannedRename
import org.plukh.mkvtool.core.RenameOutcome
import org.plukh.mkvtool.core.RenamePlan
import org.plukh.mkvtool.core.RenameProblem
import org.plukh.mkvtool.core.RenameRun
import org.plukh.mkvtool.core.ShowNameResolved
import java.io.File

/**
 * Pins the v1 text of every `rename` line, and its routing: the refusal report goes to stderr in one
 * piece — preview included — because splitting one report across two streams scrambles its line order
 * under buffering when both are redirected to the same place.
 */
class RenameRendererTest : FunSpec({

    val esc = Char(27).toString()

    fun entry(name: String, newName: String, relPath: String? = null, external: Boolean = false) =
        PlannedRename(File(name), newName, relPath, external)

    fun plan(
        entries: List<PlannedRename> = emptyList(),
        problems: List<RenameProblem> = emptyList(),
        skipped: List<String> = emptyList(),
    ) = RenamePlan(entries, problems, skipped)

    test("the show-name line says which file it came from") {
        renderResult(ShowNameResolvedRenderer, ShowNameResolved("My Show", "episodes.yaml")).first shouldContain
            "*** Show name from episodes.yaml: My Show"
    }

    context("the plan") {
        test("an empty plan with nothing wrong says so, in yellow") {
            val (out, err) = renderResult(RenamePlanRenderer, plan(), colorEnabled = true)
            out shouldContain "${esc}[33m*** Nothing to rename${esc}[0m"
            err.shouldBeEmpty()
        }

        test("a plan with work to do and no problems says nothing at all") {
            val (out, err) = renderResult(
                RenamePlanRenderer,
                plan(entries = listOf(entry("a.mkv", "My Show - S01E01 - One.mkv"))),
            )
            out.shouldBeEmpty()
            err.shouldBeEmpty()
        }

        test("skipped externals are a warning naming each one, and why") {
            val (out, err) = renderResult(
                RenamePlanRenderer,
                plan(
                    entries = listOf(entry("a.mkv", "My Show - S01E01 - One.mkv")),
                    skipped = listOf("Grp/AAA S01E01.mka", "Grp/BBB S01E02.mka"),
                ),
            )
            err shouldContain "*** Warning: 2 external files matched by episode number only, and are not renamed:"
            err shouldContain "  - Grp/AAA S01E01.mka"
            err shouldContain "  Their names carry no relation to the main file's, so there is no suffix to preserve."
            out.shouldBeEmpty()
        }

        test("one problem refuses the batch and counts itself in the singular") {
            val (_, err) = renderResult(
                RenamePlanRenderer,
                plan(problems = listOf(RenameProblem.NoEpisodeNumber("bonus.mkv"))),
            )
            err shouldContain "*** Error: Refusing to rename anything, 1 problem found:"
            err shouldContain "  - no season/episode (sXXeYY) in the file name: 'bonus.mkv'"
        }

        test("every problem has its own sentence") {
            val (_, err) = renderResult(
                RenamePlanRenderer,
                plan(
                    problems = listOf(
                        RenameProblem.NoTitle("02", "episodes.txt", "Show.s01e02.mkv"),
                        RenameProblem.TargetExists("Rus sound/[A]/New.mka", "Rus sound/[A]/S01E01.mka"),
                        RenameProblem.DuplicateTarget("New.mkv", listOf("a.mkv", "b.mkv")),
                    ),
                ),
            )
            err shouldContain "*** Error: Refusing to rename anything, 3 problems found:"
            err shouldContain "  - no title for episode 02 in episodes.txt (needed by 'Show.s01e02.mkv')"
            err shouldContain
                "  - target already exists: 'Rus sound/[A]/New.mka' (would overwrite it with 'Rus sound/[A]/S01E01.mka')"
            err shouldContain "  - multiple files would be renamed to 'New.mkv': a.mkv, b.mkv"
        }

        test("the blocked batch still shows what the rest would have done, on the same stream") {
            val (out, err) = renderResult(
                RenamePlanRenderer,
                plan(
                    entries = listOf(
                        entry("a.mkv", "My Show - S01E01 - One.mkv"),
                        entry("x.mka", "My Show - S01E01 - One.mka", relPath = "Rus sound/[A]/x.mka", external = true),
                    ),
                    problems = listOf(RenameProblem.NoEpisodeNumber("bonus.mkv")),
                ),
            )
            err shouldContain "*** The other 2 files would have been renamed:"
            err shouldContain "  'a.mkv' -> 'My Show - S01E01 - One.mkv'"
            // An external entry is previewed by its path, which says which directory's copy is meant.
            err shouldContain "  'Rus sound/[A]/x.mka' -> 'My Show - S01E01 - One.mka'"
            out.shouldBeEmpty()
        }
    }

    context("one file") {
        test("a preview is the arrow form, uncolored") {
            val (out, _) = renderResult(
                FileRenamedRenderer,
                FileRenamed("a.mkv", "My Show - S01E01 - One.mkv", RenameOutcome.Previewed),
                colorEnabled = true,
            )
            out shouldContain "'a.mkv' -> 'My Show - S01E01 - One.mkv'"
            out shouldNotContain esc
        }

        test("a rename is two lines of one logical header, both in the header colour") {
            val (out, err) = renderResult(
                FileRenamedRenderer,
                FileRenamed("a.mkv", "New.mkv", RenameOutcome.Renamed),
                colorEnabled = true,
            )
            out shouldContain "${esc}[36m*** Renaming 'a.mkv'${esc}[0m"
            out shouldContain "${esc}[36m***       to 'New.mkv'${esc}[0m"
            err.shouldBeEmpty()
        }

        test("a failure keeps the header and adds a red error on stderr") {
            val (out, err) = renderResult(
                FileRenamedRenderer,
                FileRenamed("a.mkv", "New.mkv", RenameOutcome.Failed),
                colorEnabled = true,
            )
            out shouldContain "*** Renaming 'a.mkv'"
            err shouldContain "${esc}[31m*** Error: could not rename 'a.mkv'${esc}[0m"
        }
    }

    context("the summary") {
        fun run(
            entries: List<PlannedRename>,
            failed: Int = 0,
            dryRun: Boolean = false,
            problems: List<RenameProblem> = emptyList(),
        ) = RenameRun(plan(entries, problems), emptyList(), failed, dryRun)

        test("a clean run is green and counts the externals separately") {
            val (out, _) = renderResult(
                RenameRunRenderer,
                run(
                    listOf(
                        entry("a.mkv", "New.mkv"),
                        entry("x.mka", "New.mka", relPath = "Rus sound/[A]/x.mka", external = true),
                    ),
                ),
                colorEnabled = true,
            )
            out shouldContain "${esc}[32m*** 2 files (1 external) renamed${esc}[0m"
        }

        test("no externals, no parenthetical") {
            renderResult(RenameRunRenderer, run(listOf(entry("a.mkv", "New.mkv")))).first shouldContain
                "*** 1 file renamed"
        }

        test("a dry run says nothing changed, uncolored") {
            val (out, _) = renderResult(
                RenameRunRenderer,
                run(listOf(entry("a.mkv", "New.mkv")), dryRun = true),
                colorEnabled = true,
            )
            out shouldContain "*** Dry run: 1 file would be renamed, nothing changed"
            out shouldNotContain esc
        }

        test("a failure is red and counts what did get through") {
            val (out, _) = renderResult(
                RenameRunRenderer,
                run(listOf(entry("a.mkv", "New.mkv"), entry("b.mkv", "New2.mkv")), failed = 1),
                colorEnabled = true,
            )
            out shouldContain "${esc}[31m*** 1 renamed, 1 failed${esc}[0m"
        }

        test("a refused batch gets no summary: the plan already had its say") {
            val (out, err) = renderResult(
                RenameRunRenderer,
                run(listOf(entry("a.mkv", "New.mkv")), problems = listOf(RenameProblem.NoEpisodeNumber("x.mkv"))),
            )
            out.shouldBeEmpty()
            err.shouldBeEmpty()
        }

        test("an empty batch gets no summary either") {
            val (out, _) = renderResult(RenameRunRenderer, run(emptyList()))
            out.shouldBeEmpty()
        }
    }
})
