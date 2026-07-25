package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.FileRenamed
import org.plukh.mkvtool.core.RenameOutcome
import org.plukh.mkvtool.core.RenamePlan
import org.plukh.mkvtool.core.RenameProblem
import org.plukh.mkvtool.core.RenameRun
import org.plukh.mkvtool.core.ShowNameResolved
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.plural

/** Where the show name came from, said before any rename so the name every file is about to take is
 *  visible first. */
val ShowNameResolvedRenderer = ResultTextRenderer<ShowNameResolved> { result, s ->
    s.out.println("*** Show name from ${result.source}: ${result.showName}")
}

/**
 * The plan's verdict, before anything is touched: which external files are being left alone, and — when
 * the batch is refused — why, plus what the rest of it would have done.
 *
 * The refusal goes to stderr in the `*** Error: ` form, preview included. It is written here rather than
 * emitted as a diagnostic because it *is* the plan's answer: the count and the problems are result data,
 * and composing them into a message would be exactly the move the seam forbids. Keeping the whole report
 * on one stream is deliberate too — splitting it scrambles the line order under buffering when both
 * streams are redirected to the same place.
 */
val RenamePlanRenderer = ResultTextRenderer<RenamePlan> { result, s ->
    if (result.skippedExternals.isNotEmpty()) {
        s.err.println(
            s.warningText(
                "${plural(result.skippedExternals.size, "external file")} matched by episode number only, " +
                    "and are not renamed:"
            )
        )
        result.skippedExternals.forEach { s.err.println("  - $it") }
        s.err.println("  Their names carry no relation to the main file's, so there is no suffix to preserve.")
    }

    if (result.problems.isNotEmpty()) {
        s.err.println(s.errorText("Refusing to rename anything, ${plural(result.problems.size, "problem")} found:"))
        result.problems.forEach { s.err.println("  - ${describe(it)}") }

        // Show what the rest of the batch would have done, so the scope of what is blocked is visible
        // rather than having to be inferred.
        if (result.entries.isNotEmpty()) {
            s.err.println()
            s.err.println("*** The other ${plural(result.entries.size, "file")} would have been renamed:")
            result.entries.forEach { s.err.println("  '${it.relPath ?: it.file.name}' -> '${it.newName}'") }
        }
        return@ResultTextRenderer
    }

    if (result.entries.isEmpty()) s.out.println(s.yellow("*** Nothing to rename"))
}

/** One problem as the sentence the refusal lists it by. */
private fun describe(problem: RenameProblem): String = when (problem) {
    is RenameProblem.NoEpisodeNumber ->
        "no season/episode (sXXeYY) in the file name: '${problem.fileName}'"
    is RenameProblem.NoTitle ->
        "no title for episode ${problem.episode} in ${problem.source} (needed by '${problem.fileName}')"
    is RenameProblem.TargetExists ->
        "target already exists: '${problem.path}' (would overwrite it with '${problem.source}')"
    is RenameProblem.DuplicateTarget ->
        "multiple files would be renamed to '${problem.newName}': ${problem.fileNames.joinToString(", ")}"
}

/**
 * One file, as it happens. A dry run prints the same arrow form the refusal preview uses; a real run
 * prints the rename as two lines that are one logical header, so both carry the header colour.
 *
 * An external file is named by its path — that is what says which directory's copy is being renamed —
 * while the target stays a bare name, because the directory does not change.
 */
val FileRenamedRenderer = ResultTextRenderer<FileRenamed> { result, s ->
    when (result.outcome) {
        RenameOutcome.Previewed -> s.out.println("'${result.from}' -> '${result.to}'")
        RenameOutcome.Renamed -> {
            s.out.println(s.cyan("*** Renaming '${result.from}'"))
            s.out.println(s.cyan("***       to '${result.to}'"))
        }
        RenameOutcome.Failed -> {
            s.out.println(s.cyan("*** Renaming '${result.from}'"))
            s.out.println(s.cyan("***       to '${result.to}'"))
            s.err.println(s.errorText("could not rename '${result.from}'"))
        }
    }
}

/**
 * The batch's result, on stdout in both colours — the per-file errors already went to stderr as they
 * happened.
 *
 * Nothing is printed for a batch that never ran: a refused plan and an empty one have both had their say
 * through [RenamePlanRenderer], and a summary after either would be a second answer to the same question.
 */
val RenameRunRenderer = ResultTextRenderer<RenameRun> { result, s ->
    if (result.plan.problems.isNotEmpty() || result.plan.entries.isEmpty()) return@ResultTextRenderer

    val planned = result.plan.entries.size
    val externalNote = if (result.external > 0) " (${result.external} external)" else ""

    s.out.println()
    when {
        result.dryRun ->
            s.out.println("*** Dry run: ${plural(planned, "file")}$externalNote would be renamed, nothing changed")
        result.failed > 0 ->
            s.out.println(s.red("*** ${planned - result.failed} renamed, ${result.failed} failed"))
        else ->
            s.out.println(s.green("*** ${plural(planned, "file")}$externalNote renamed"))
    }
}
