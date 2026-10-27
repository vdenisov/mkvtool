package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.BindingProblem
import org.plukh.mkvtool.core.BindingReport
import org.plukh.mkvtool.core.BindingResult
import org.plukh.mkvtool.core.BindingRule
import org.plukh.mkvtool.core.BoundFile
import org.plukh.mkvtool.core.DoubleClaim
import org.plukh.mkvtool.core.DuplicateEpisode
import org.plukh.mkvtool.core.GroupRef
import org.plukh.mkvtool.core.NameTie
import org.plukh.mkvtool.core.SeasonConflict
import org.plukh.mkvtool.core.SeasonUnknown
import org.plukh.mkvtool.core.SequenceCount
import org.plukh.mkvtool.core.SharedFile
import org.plukh.mkvtool.core.SharedMain
import org.plukh.mkvtool.core.UnpairedFile
import org.plukh.mkvtool.core.groupLabel
import org.plukh.mkvtool.core.pad as episodeNumber
import org.plukh.mkvtool.core.sourceLabel
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import org.plukh.mkvtool.out.plural
import java.io.File
import java.util.Locale

/**
 * A batch's binding, identical in `mux` and `inspect` but for the verdict on its problems: errors where the
 * command refuses the batch, warnings where it carries on. A clean binding says nothing unless [table] asks
 * for the per-episode table, or a file no group claims is skipped.
 *
 * [table] is the `mux --dry-run` / `inspect --identify` render hint.
 */
class BindingReportRenderer(private val table: Boolean = false) : ResultTextRenderer<BindingReport> {

    override fun render(result: BindingReport, style: TextStyle) {
        val binding = result.binding
        if (table && binding.mains.isNotEmpty()) renderTable(binding, style)

        // Skipped, never refused: claiming a file is the config's decision, and leaving one out is a decision too.
        // Unsaid when the batch is refused, which skips nothing; a refused `sequence` would list every main.
        val unclaimed = binding.partition.unclaimed
        val refused = result.fatal && binding.problems.isNotEmpty()
        if (unclaimed.isNotEmpty() && !refused) {
            style.out.println(
                style.yellow("*** ${plural(unclaimed.size, "file")} will be skipped: not claimed by any group"),
            )
            formatFileList(unclaimed.map { it.name }, "      ").forEach { style.out.println(it) }
            style.out.println()
        }

        if (binding.problems.isEmpty()) return
        val header = "the files cannot be bound to episodes (${plural(binding.problems.size, "problem")}):"
        style.err.println(if (result.fatal) style.errorText(header) else style.warningText(header))
        for (problem in binding.problems) {
            bindingProblemLines(problem, binding.groups).forEach { style.err.println("      $it") }
        }
    }

    /** One row per main file, `-` for one with no episode, its sources' files beneath it; READ only when the map
     *  changed a number. */
    private fun renderTable(binding: BindingResult, s: TextStyle) {
        val mains = binding.mains
        val season = binding.season?.let { ", season $it" }.orEmpty()
        val rule = mainRuleName(binding.rule)
        s.out.println(s.cyan("*** Binding: ${plural(mains.size, "main file")}$season, by $rule"))

        val mapped = mains.any { it.read != it.episode }
        val readWidth = if (mapped) READ_WIDTH else 0
        s.out.println(s.cyan("  ${pad("EP", EP_WIDTH)}${if (mapped) pad("READ", READ_WIDTH) else ""}MAIN"))

        val byMain = binding.sources.flatMap { source ->
            source.bound.map { it.main to (sourceLabel(source.path, binding.groups) to it) }
        }.groupBy({ it.first }, { it.second })
        val pathWidth = binding.sources.maxOfOrNull { sourceLabel(it.path, binding.groups).length } ?: 0
        val indent = " ".repeat(2 + EP_WIDTH + readWidth)

        for (main in mains) {
            val read = if (mapped) pad(numberOrDash(main.read), READ_WIDTH) else ""
            s.out.println("  ${pad(numberOrDash(main.episode), EP_WIDTH)}$read${main.main.name}")
            byMain[main.main].orEmpty().forEach { (path, bound) ->
                s.out.println("$indent${pad(path, pathWidth)}  ${boundName(bound)}  (${sourceRuleName(bound.rule)})")
            }
        }
        s.out.println()
    }

    private companion object {
        /** `EP` plus the gap before the next column; READ the same, a column wider. */
        const val EP_WIDTH = 5
        const val READ_WIDTH = 6
    }
}

/** One problem as the lines a reader acts on; a source is named by its config path, its group by [groups]. */
private fun bindingProblemLines(problem: BindingProblem, groups: List<GroupRef>): List<String> {
    fun label(path: String) = sourceLabel(path, groups)
    return when (problem) {
        SeasonUnknown -> listOf("no season: no file name and no episode metadata names one")
        is SeasonConflict -> buildList {
            add("more than one season" + problem.metadata?.let { "; the episode metadata says $it" }.orEmpty() + ":")
            for ((season, names) in problem.bySeason) {
                add("  season $season:")
                addAll(formatFileList(names, "    "))
            }
        }
        is DuplicateEpisode ->
            listOf("episode ${problem.episode} is claimed by ${problem.mains.size} main files: ${names(problem.mains)}")
        is SequenceCount -> {
            val counted = plural(problem.files, if (problem.path == "general") "main file" else "file")
            val expected = problem.expected ?: 0
            val against = when {
                problem.path != "general" -> "but the main files read ${plural(expected, "episode")}"
                problem.expected == null -> "but there is no episode metadata to count against"
                else -> "but the episode metadata lists ${problem.expected}"
            }
            listOf("${label(problem.path)}.matcher is sequence over $counted, $against")
        }
        is UnpairedFile -> listOf("${label(problem.path)}: ${problem.entry.relPath} pairs with no main file")
        is NameTie -> listOf(
            "${label(problem.path)}: ${problem.entry.relPath} is named after more than one main file: " +
                names(problem.mains),
        )
        is SharedMain -> listOf(
            "${label(problem.path)}: more than one file pairs with ${problem.main.name}: " +
                problem.entries.joinToString(", ") { it.relPath },
        )
        is SharedFile -> listOf(
            "${problem.entry.relPath} is matched by more than one source: " +
                problem.paths.joinToString(", ") { label(it) },
        )
        is DoubleClaim -> listOf(
            "${problem.main.name} is claimed by more than one group: " +
                problem.groups.joinToString(", ") { groupLabel(it) },
        )
    }
}

/** The config key a main rule comes from, which is what a reader goes and looks at. */
private fun mainRuleName(rule: BindingRule): String = when (rule) {
    BindingRule.REGEX -> "episodeRegex"
    BindingRule.SEQUENCE -> "sequence"
    else -> "SxxEyy"
}

private fun sourceRuleName(rule: BindingRule): String = rule.name.lowercase(Locale.ROOT)

/** A paired file, and the partner that travels with it. */
private fun boundName(bound: BoundFile): String =
    bound.entry.relPath + bound.partner?.let { " + ${it.file.name}" }.orEmpty()

private fun names(files: List<File>): String = files.joinToString(", ") { it.name }

/** A table cell's number, `-` (the report's one glyph for nothing here) when there is none. */
private fun numberOrDash(number: Int?): String = number?.let(::episodeNumber) ?: "-"
