package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.BindingProblem
import org.plukh.mkvtool.core.BindingReport
import org.plukh.mkvtool.core.BindingResult
import org.plukh.mkvtool.core.BindingRule
import org.plukh.mkvtool.core.BoundFile
import org.plukh.mkvtool.core.DuplicateEpisode
import org.plukh.mkvtool.core.NameTie
import org.plukh.mkvtool.core.SeasonConflict
import org.plukh.mkvtool.core.SeasonUnknown
import org.plukh.mkvtool.core.SequenceCount
import org.plukh.mkvtool.core.SharedFile
import org.plukh.mkvtool.core.SharedMain
import org.plukh.mkvtool.core.SkippedMainFile
import org.plukh.mkvtool.core.UnpairedFile
import org.plukh.mkvtool.core.pad as episodeNumber
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import org.plukh.mkvtool.out.plural
import java.io.File
import java.util.Locale

/**
 * A batch's binding, identical in `mux` and `inspect` but for the verdict: the main files no rule read, and
 * every problem — errors where the command refuses the batch, warnings where it carries on. A clean binding
 * says nothing unless [table] asks for the per-episode table, which then lists the skipped mains itself.
 *
 * [table] is the `mux --dry-run` / `inspect --identify` render hint.
 */
class BindingReportRenderer(private val table: Boolean = false) : ResultTextRenderer<BindingReport> {

    override fun render(result: BindingReport, style: TextStyle) {
        val binding = result.binding
        val tabled = table && binding.mains.isNotEmpty()
        if (tabled) renderTable(binding, style)

        if (binding.skipped.isNotEmpty() && !tabled) {
            val count = binding.skipped.size
            style.err.println(
                style.warningText("${plural(count, "main file")} ${have(count)} no episode and will be skipped:"),
            )
            formatFileList(binding.skipped.map { it.name }, "      ").forEach { style.err.println(it) }
        }

        if (binding.problems.isEmpty()) return
        val header = "the files cannot be bound to episodes (${plural(binding.problems.size, "problem")}):"
        style.err.println(if (result.fatal) style.errorText(header) else style.warningText(header))
        for (problem in binding.problems) bindingProblemLines(problem).forEach { style.err.println("      $it") }
    }

    /** One row per main file, its sources' files beneath it; READ only when the map changed a number. */
    private fun renderTable(binding: BindingResult, s: TextStyle) {
        val mains = binding.mains
        val season = binding.season?.let { ", season $it" }.orEmpty()
        s.out.println(
            s.cyan("*** Binding: ${plural(mains.size, "main file")}$season, by ${mainRuleName(mains.first().rule)}"),
        )

        val mapped = mains.any { it.read != it.episode }
        val readWidth = if (mapped) READ_WIDTH else 0
        s.out.println(s.cyan("  ${pad("EP", EP_WIDTH)}${if (mapped) pad("READ", READ_WIDTH) else ""}MAIN"))

        val byMain = binding.sources.flatMap { source -> source.bound.map { it.main to (source.path to it) } }
            .groupBy({ it.first }, { it.second })
        val pathWidth = binding.sources.maxOfOrNull { it.path.length } ?: 0
        val indent = " ".repeat(2 + EP_WIDTH + readWidth)

        for (main in mains) {
            val read = if (mapped) pad(episodeNumber(main.read), READ_WIDTH) else ""
            s.out.println("  ${pad(episodeNumber(main.episode), EP_WIDTH)}$read${main.main.name}")
            byMain[main.main].orEmpty().forEach { (path, bound) ->
                s.out.println("$indent${pad(path, pathWidth)}  ${boundName(bound)}  (${sourceRuleName(bound.rule)})")
            }
        }

        if (binding.skipped.isNotEmpty()) {
            s.out.println(s.cyan("*** Skipped: no episode"))
            formatFileList(binding.skipped.map { it.name }, "      ").forEach { s.out.println(it) }
        }
        s.out.println()
    }

    private companion object {
        /** `EP` plus the gap before the next column; READ the same, a column wider. */
        const val EP_WIDTH = 5
        const val READ_WIDTH = 6
    }
}

/** One problem as the lines a reader acts on; a source is named by its config path. */
private fun bindingProblemLines(problem: BindingProblem): List<String> = when (problem) {
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
            problem.path != "general" -> "but ${plural(expected, "main file")} ${have(expected)} an episode"
            problem.expected == null -> "but there is no episode metadata to count against"
            else -> "but the episode metadata lists ${problem.expected}"
        }
        listOf("${problem.path}.matcher is sequence over $counted, $against")
    }
    is UnpairedFile -> listOf("${problem.path}: ${problem.entry.relPath} pairs with no main file")
    is SkippedMainFile ->
        listOf("${problem.path}: ${problem.entry.relPath} is named after ${problem.main.name}, which has no episode")
    is NameTie -> listOf(
        "${problem.path}: ${problem.entry.relPath} is named after more than one main file: ${names(problem.mains)}",
    )
    is SharedMain -> listOf(
        "${problem.path}: more than one file pairs with ${problem.main.name}: " +
            problem.entries.joinToString(", ") { it.relPath },
    )
    is SharedFile ->
        listOf("${problem.entry.relPath} is matched by more than one source: ${problem.paths.joinToString(", ")}")
}

/** The config key a main rule comes from, which is what a reader goes and looks at. */
private fun mainRuleName(rule: BindingRule): String = when (rule) {
    BindingRule.REGEX -> "episodeRegex"
    BindingRule.SEQUENCE -> "sequence"
    else -> "SxxEyy"
}

private fun sourceRuleName(rule: BindingRule): String = rule.name.lowercase(Locale.ROOT)

/** A paired file, and the `.sub` that travels with an `.idx`. */
private fun boundName(bound: BoundFile): String =
    bound.entry.relPath + bound.sub?.let { " + ${it.file.name}" }.orEmpty()

private fun names(files: List<File>): String = files.joinToString(", ") { it.name }

private fun have(count: Int): String = if (count == 1) "has" else "have"
