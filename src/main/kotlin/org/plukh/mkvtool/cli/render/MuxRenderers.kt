package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.CompanionDrops
import org.plukh.mkvtool.core.FileMux
import org.plukh.mkvtool.core.MuxAbort
import org.plukh.mkvtool.core.MuxOutcome
import org.plukh.mkvtool.core.SubstitutionDrops
import org.plukh.mkvtool.core.TrackOrder
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.plural

/**
 * The derived track order, announced once before the batch.
 *
 * It is said out loud because it is a decision the config did not make: everything else mkvmerge is told
 * was written down by hand, and this one line is the tool showing its work.
 */
val TrackOrderDerivedRenderer = ResultTextRenderer<TrackOrder.Derived> { result, s ->
    s.out.println("*** trackOrder not configured; using derived order: ${result.order}")
    s.out.println()
}

/**
 * What did not line up in a configured track order — warnings only, never a refusal.
 *
 * mkvmerge silently ignores an entry matching no muxed track, so a stale order fails *quietly*, which is
 * the whole reason for saying anything at all. Failing instead would break configs that work today, so
 * each finding is a warning with its own continuation explaining what the consequence actually is.
 *
 * Written from the result rather than composed into diagnostics because the three lists are findings: the
 * ids are data the check produced, and folding them into a message string is exactly what the seam forbids.
 */
val TrackOrderConfiguredRenderer = ResultTextRenderer<TrackOrder.Configured> { result, s ->
    if (result.malformed.isNotEmpty()) {
        s.err.println(s.warningText("trackOrder contains malformed entries: ${result.malformed.joinToString(", ")}"))
        s.err.println(
            s.yellow("""***          Expected comma-separated sourceIndex:trackId pairs, e.g. "0:0,0:1,1:0"."""),
        )
    }

    if (result.unknown.isNotEmpty()) {
        s.err.println(
            s.warningText("trackOrder references track IDs not configured: ${result.unknown.joinToString(", ")}"),
        )
        s.err.println(s.yellow("***          mkvmerge silently ignores unknown IDs, so these have no effect."))
        s.err.println(
            s.yellow(
                "***          Check trackOrder against mainSource.audioTracks / subtitleTracks / " +
                    "additionalSources.",
            ),
        )
    }

    if (result.missing.isNotEmpty()) {
        s.err.println(s.warningText("trackOrder omits configured track IDs: ${result.missing.joinToString(", ")}"))
        s.err.println(
            s.yellow(
                "***          These tracks are still muxed, but their position in the output is left to " +
                    "mkvmerge.",
            ),
        )
    }
}

/**
 * The episodes stage two dropped, and which variable left each of them unresolvable.
 *
 * Grouped by variable rather than by file, because that is the shape of the answer: one missing
 * `${'$'}{episodeName}` across eleven episodes is one thing to fix, and eleven lines saying so is not. The
 * missing-metadata note comes first when there is no metadata at all — it explains every line below it at
 * once, and is usually the whole story.
 */
val SubstitutionDropsRenderer = ResultTextRenderer<SubstitutionDrops> { result, s ->
    s.out.println(
        s.yellow(
            "*** ${plural(result.fileNames.size, "file")} will be skipped: " +
                "substitution variables have no value",
        ),
    )
    if (result.episodeSource == null) s.out.println("      no episodes.yaml or episodes.txt in this directory")
    for (variable in result.variables) {
        s.out.println("      \${${variable.name}}  (unresolved for ${plural(variable.fileNames.size, "file")})")
        formatFileList(variable.fileNames, "        ").forEach { s.out.println(it) }
    }
    s.out.println()
}

/**
 * The episodes whose companions are not on disk, grouped by the pattern that failed to resolve.
 *
 * The pattern is printed unresolved — `${'$'}{fileName}[Studio].mka` — because that is the line in the
 * config a reader has to go and look at, and the files it did not find are listed under it.
 */
val CompanionDropsRenderer = ResultTextRenderer<CompanionDrops> { result, s ->
    s.out.println(
        s.yellow("*** ${plural(result.fileNames.size, "file")} will be skipped: companion files are missing"),
    )
    for (source in result.sources) {
        s.out.println("      ${source.pattern}  (missing for ${plural(source.fileNames.size, "file")})")
        formatFileList(source.fileNames, "        ").forEach { s.out.println(it) }
    }
    s.out.println()
}

/** Stage two under `--strict`: the same finding the report above would have made, as a refusal. */
val UnresolvedVariablesAbortRenderer = ResultTextRenderer<MuxAbort.UnresolvedVariables> { result, s ->
    s.err.println(
        s.red(
            "*** Strict mode: aborting (${plural(result.fileCount, "file")} with " +
                "unresolved substitution variables).",
        ),
    )
}

/**
 * The check under `--strict`. Two lines, because the first says what was found and the second says what it
 * cost — and what to do about it, since "aborting" alone leaves a reader to guess whether anything was
 * written.
 */
val BlockingDiscrepanciesAbortRenderer = ResultTextRenderer<MuxAbort.BlockingDiscrepancies> { result, s ->
    s.err.println(
        s.red(
            "*** Strict mode: aborting (${plural(result.count, "discrepancy", "discrepancies")} " +
                "affecting selected tracks).",
        ),
    )
    s.err.println(s.red("*** Nothing was muxed. Fix config.yaml or the inputs, or drop --strict to continue."))
}

/**
 * One file the batch walked over.
 *
 * A muxed file prints nothing here: its `*** Processing` header is a diagnostics event and mkvmerge's own
 * output went straight to the console while it ran. What is left is the three things mkvmerge did not say
 * — that a file was passed over, what would have been run, and that a run failed.
 */
val FileMuxRenderer = ResultTextRenderer<FileMux> { result, s ->
    when (val outcome = result.outcome) {
        MuxOutcome.Skipped -> s.out.println("*** Skipping ${result.fileName}")

        MuxOutcome.Previewed -> {
            s.out.println("*** Dry run, would execute:")
            s.out.println(result.command.orEmpty().joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
            s.out.println()
        }

        MuxOutcome.Muxed -> {
            // mkvmerge inherited the console and has already had its say.
        }

        is MuxOutcome.Failed -> {
            s.out.println()
            s.err.println(s.errorText("mkvmerge exited with code ${outcome.exitCode}"))
        }
    }
}
