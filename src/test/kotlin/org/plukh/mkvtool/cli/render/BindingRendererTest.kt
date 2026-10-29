package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.core.AmbiguousCompanion
import org.plukh.mkvtool.core.BindingProblem
import org.plukh.mkvtool.core.BindingReport
import org.plukh.mkvtool.core.BindingResult
import org.plukh.mkvtool.core.BindingRule
import org.plukh.mkvtool.core.CompanionFile
import org.plukh.mkvtool.core.DuplicateEpisode
import org.plukh.mkvtool.core.GroupPartition
import org.plukh.mkvtool.core.GroupRef
import org.plukh.mkvtool.core.MainEpisode
import org.plukh.mkvtool.core.RepeatedCompanion
import org.plukh.mkvtool.core.SeasonConflict
import org.plukh.mkvtool.core.SeasonUnknown
import org.plukh.mkvtool.core.SourceBinding
import java.io.File

/**
 * A batch's binding as `mux` and `inspect` both print it: the per-episode table under the render hint, and
 * every problem — an error where the command refuses the batch, a warning where it
 * carries on. The table is stdout; warnings and problems are stderr.
 */
class BindingRendererTest : FunSpec({

    context("the table") {
        test("one row per main, its sources' files beneath it, and READ where the map changed a number") {
            val first = File("[Grp] Show - 13 (BD).mkv")
            val second = File("[Grp] Show - 14 (BD).mkv")
            val binding = binding(
                mains = listOf(
                    MainEpisode(first, "13", 1),
                    MainEpisode(second, "14", 2),
                    MainEpisode(File("NCOP.mkv"), null, null),
                ),
                sources = listOf(
                    SourceBinding(
                        "additionalSources[0]",
                        listOf(companion(first, "Subs/13.srt"), companion(second, "Subs/14.srt")),
                    ),
                    SourceBinding(
                        "additionalSources[1]",
                        listOf(companion(first, "Rus/[Grp] Show - 13 (BD).mka")),
                    ),
                ),
                rule = BindingRule.REGEX,
            )

            val (out, err) = render(binding, table = true)

            out shouldBe "*** Binding: 3 main files, season 01, by episodeRegex\n" +
                "  EP   READ  MAIN\n" +
                "  01   13    [Grp] Show - 13 (BD).mkv\n" +
                "             additionalSources[0]  Subs/13.srt\n" +
                "             additionalSources[1]  Rus/[Grp] Show - 13 (BD).mka\n" +
                "  02   14    [Grp] Show - 14 (BD).mkv\n" +
                "             additionalSources[0]  Subs/14.srt\n" +
                "  -    -     NCOP.mkv\n" +
                "\n"
            err shouldBe ""
        }

        test("with no map at work there is no READ column, and a VobSub pair is one line") {
            val main = File("Show - S01E01.mkv")
            val idx = companion(main, "Subs/Show - S01E01.idx").copy(partner = File("Subs/Show - S01E01.sub"))
            val binding = binding(
                mains = listOf(MainEpisode(main, "01", 1)),
                sources = listOf(SourceBinding("additionalSources[0]", listOf(idx))),
            )

            render(binding, table = true).first shouldBe "*** Binding: 1 main file, season 01, by SxxEyy\n" +
                "  EP   MAIN\n" +
                "  01   Show - S01E01.mkv\n" +
                "       additionalSources[0]  Subs/Show - S01E01.idx + Show - S01E01.sub\n" +
                "\n"
        }

        test("without the hint a clean binding says nothing at all") {
            val binding = binding(mains = listOf(MainEpisode(File("Show - S01E01.mkv"), "01", 1)))

            render(binding) shouldBe ("" to "")
        }
    }

    test("a main with no episode is a row of its own, and the header names the run's rule, not a main's") {
        val binding = binding(mains = listOf(MainEpisode(File("NCOP.mkv"), null, null)), rule = BindingRule.REGEX)

        render(binding, table = true) shouldBe (
            "*** Binding: 1 main file, season 01, by episodeRegex\n" +
                "  EP   MAIN\n" +
                "  -    NCOP.mkv\n" +
                "\n" to ""
        )
        // Without the table nothing is said: an episode only matters where a template asks for one.
        render(binding) shouldBe ("" to "")
    }

    context("each problem is a line under the error, naming the files and the source") {
        withData(
            nameFn = { it.second },
            SeasonUnknown to "no season: no file name and no episode metadata names one",
            SeasonConflict(mapOf("01" to listOf("a.mkv"), "02" to listOf("b.mkv")), "01") to
                "more than one season; the episode metadata says 01:\n" +
                "        season 01:\n          a.mkv\n        season 02:\n          b.mkv",
            DuplicateEpisode(3, listOf(File("a.mkv"), File("b.mkv"))) to
                "episode 3 is claimed by 2 main files: a.mkv, b.mkv",
            AmbiguousCompanion("additionalSources[0]", File("A.mkv"), listOf("Subs/a 1.srt", "Subs/a 2.srt")) to
                "additionalSources[0]: 2 files match for A.mkv: Subs/a 1.srt, Subs/a 2.srt",
            RepeatedCompanion("Subs/x.ass", File("A.mkv"), listOf("additionalSources[0]", "additionalSources[1]")) to
                "Subs/x.ass is picked for A.mkv by more than one source: additionalSources[0], additionalSources[1]",
        ) { (problem, lines) ->
            val (out, err) = render(binding(problems = listOf(problem)))

            out shouldBe ""
            err shouldBe "*** Error: the files cannot be bound to episodes (1 problem):\n      $lines\n"
        }
    }

    test("where the command carries on, the same problems are a warning") {
        val (_, err) = render(binding(problems = listOf(SeasonUnknown)), fatal = false)

        err shouldBe "*** Warning: the files cannot be bound to episodes (1 problem):\n" +
            "      no season: no file name and no episode metadata names one\n"
    }

    context("groups") {
        val groups = listOf(GroupRef(0, "raw"), GroupRef(1, null))

        test("a group's source is named by its group, in the table and in a problem") {
            val main = File("Show - S01E01.mkv")
            val binding = binding(
                mains = listOf(MainEpisode(main, "01", 1)),
                sources = listOf(SourceBinding("groups[1].additionalSources[0]", listOf(companion(main, "a.srt")))),
                problems = listOf(AmbiguousCompanion("groups[0].additionalSources[0]", main, listOf("b.srt", "c.srt"))),
                groups = groups,
            )

            render(binding, table = true) shouldBe (
                "*** Binding: 1 main file, season 01, by SxxEyy\n" +
                    "  EP   MAIN\n" +
                    "  01   Show - S01E01.mkv\n" +
                    "       group 2: additionalSources[0]  a.srt\n" +
                    "\n" to
                    "*** Error: the files cannot be bound to episodes (1 problem):\n" +
                    "      group 1 (raw): additionalSources[0]: 2 files match for Show - S01E01.mkv: b.srt, c.srt\n"
            )
        }

        test("files no group claims are listed as skipped, on stdout, and are no problem") {
            val claimed = File("Show - S01E01.mkv")
            val unclaimed = listOf(File("Show - S01E05.mkv"), File("Show - S01E06.mkv"))
            val binding = binding(groups = groups)
                .copy(partition = GroupPartition(listOf(listOf(claimed), emptyList()), unclaimed))

            render(binding) shouldBe (
                "*** 2 files will be skipped: not claimed by any group\n" +
                    "      Show - S01E05.mkv\n" +
                    "      Show - S01E06.mkv\n" +
                    "\n" to ""
            )
        }

        test("a refused batch skips nothing, so it lists no unclaimed file; a carried-on one still does") {
            val unclaimed = listOf(File("Show - S01E05.mkv"))
            val binding = binding(groups = groups, problems = listOf(SeasonUnknown))
                .copy(partition = GroupPartition(listOf(emptyList(), emptyList()), unclaimed))

            render(binding).first shouldBe ""
            render(binding, fatal = false).first shouldBe
                "*** 1 file will be skipped: not claimed by any group\n      Show - S01E05.mkv\n\n"
        }
    }
})

private fun binding(
    mains: List<MainEpisode> = emptyList(),
    sources: List<SourceBinding> = emptyList(),
    problems: List<BindingProblem> = emptyList(),
    rule: BindingRule = BindingRule.SXXEYY,
    groups: List<GroupRef> = emptyList(),
) = BindingResult("01", rule, mains, sources, problems, groups = groups)

private fun render(binding: BindingResult, table: Boolean = false, fatal: Boolean = true): Pair<String, String> {
    val (out, err) = renderResult(BindingReportRenderer(table), BindingReport(binding, fatal))
    return out.replace("\r\n", "\n") to err.replace("\r\n", "\n")
}

private fun companion(main: File, path: String) = CompanionFile(main, path, File(path))
