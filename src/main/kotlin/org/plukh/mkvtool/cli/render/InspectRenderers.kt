package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.ConfiguredSource
import org.plukh.mkvtool.core.ExternalLeftovers
import org.plukh.mkvtool.core.ExternalLegend
import org.plukh.mkvtool.core.ExternalListing
import org.plukh.mkvtool.core.ExternalTrack
import org.plukh.mkvtool.core.FileIdentification
import org.plukh.mkvtool.core.MatchTier
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.core.SourceListing
import org.plukh.mkvtool.core.StrictVerdict
import org.plukh.mkvtool.core.TrackListing
import org.plukh.mkvtool.core.VariantExternals
import org.plukh.mkvtool.core.label
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import org.plukh.mkvtool.out.plural

/**
 * What `--identify` draws, plus the two batch-level blocks that frame it.
 *
 * Every table on the page shares one column grid — main tracks, configured sources, discovered files — so
 * the columns line up straight down the report instead of each block starting a little table of its own.
 * The `+` header lines are what separates the blocks; a blank line per block as well made six externals
 * fill a screen.
 *
 * This grid is **not** the check report's, which is narrower and indented one level deeper. They are two
 * tables answering two questions and have never matched; unifying them would move both.
 */

/** ID, TYPE, CODEC, LANG, DEF, FOR — the widths every row on an identify page is laid out on. */
private const val ROW_FORMAT = "  %-4s %-10s %-22s %-5s %-4s %-4s %s"

/** Absent language on a main track. A container that says nothing is a question, not a blank. */
private const val UNKNOWN = "?"

/** Absent language on an external or configured source, where having none is ordinary: a raw `.ass` or
 *  `.srt` carries no language at all. */
private const val NONE = "-"

val FileIdentificationRenderer = ResultTextRenderer<FileIdentification> { result, s ->
    s.out.println(s.cyan("*** ${result.fileName}"))

    when (val listing = result.listing) {
        is TrackListing.Unreadable -> {
            s.out.println("  (mkvmerge could not identify this file: ${listing.reason})")
            s.out.println()
            return@ResultTextRenderer
        }

        is TrackListing.Tracks -> {
            if (listing.tracks.isEmpty()) {
                s.out.println("  (no tracks)")
                s.out.println()
                return@ResultTextRenderer
            }
            s.out.println(s.cyan(ROW_FORMAT.format("ID", "TYPE", "CODEC", "LANG", "DEF", "FOR", "NAME")))
            listing.tracks.forEach { s.out.println(trackRow(it, absentLanguage = UNKNOWN)) }
        }
    }

    renderConfiguredSources(result.configuredSources, s)
    renderExternals(result.externals, s)
    s.out.println()
}

/**
 * The legend: which label means which variant, the pattern its files follow, and how many there are.
 *
 * Printed in both modes — the check report labels its external rows the same way, and a label whose key
 * was never printed refers to nothing.
 */
val ExternalLegendRenderer = ResultTextRenderer<ExternalLegend> { result, s ->
    if (result.rows.isEmpty()) return@ResultTextRenderer

    val names = result.rows.map { variantDisplayName(it.variant) }
    val patterns = result.rows.map { sectionPattern(it) }
    val nameWidth = clamp(names.maxOf { it.length }, 12, 40)
    val patternWidth = clamp(patterns.maxOf { it.length }, 20, 60)

    s.out.println(s.cyan("*** External files: ${plural(result.variantCount, "variant")} discovered"))
    s.out.println(
        s.cyan(
            "  ${pad("LBL", 4)} ${pad("TYPE", 10)} ${pad("VARIANT", nameWidth)} " +
                "${pad("PATTERN", patternWidth)} FILES",
        ),
    )
    result.rows.forEachIndexed { index, row ->
        s.out.println(
            "  ${pad(row.variant.label, 4)} ${pad(row.type.label, 10)} " +
                "${pad(names[index], nameWidth)} ${pad(patterns[index], patternWidth)} ${row.fileCount}",
        )
    }
    s.out.println()
}

val ExternalLeftoversRenderer = ResultTextRenderer<ExternalLeftovers> { result, s ->
    if (result.unmatched.isNotEmpty()) {
        s.out.println(s.cyan("*** Unmatched external files (${result.unmatched.size})"))
        formatFileList(result.unmatched, "      ").forEach { s.out.println(it) }
        s.out.println()
    }
    if (result.extras.isNotEmpty()) {
        s.out.println(
            s.cyan(
                "*** Extras: ${plural(result.extras.size, "file")} of a main type in subdirectories, " +
                    "not scanned as sources",
            ),
        )
        formatFileList(result.extras, "      ").forEach { s.out.println(it) }
        s.out.println()
    }
}

/** Both lines can print in one run: they are two independent reasons for the same exit. */
val StrictVerdictRenderer = ResultTextRenderer<StrictVerdict> { result, s ->
    if (result.blockingCount > 0) {
        s.err.println(
            s.red(
                "*** Strict mode: ${plural(result.blockingCount, "discrepancy", "discrepancies")} " +
                    "affecting selected tracks.",
            ),
        )
    }
    if (result.configProblems > 0) {
        s.err.println(
            s.red(
                "*** Strict mode: ${plural(result.configProblems, "config problem")} " +
                    "(the report above was not classified against a config).",
            ),
        )
    }
}

/**
 * Sources the config declares, resolved for this episode.
 *
 * The resolved path is printed as well as its tracks: with templated paths, what a pattern actually
 * expands to per episode is half of what one wants to see here. Never fatal — `--identify` describes what
 * is there, so a source that is missing is a line in the report rather than an error.
 */
private fun renderConfiguredSources(sources: List<ConfiguredSource>, s: TextStyle) {
    if (sources.isEmpty()) return
    s.out.println()

    sources.forEach { source ->
        s.out.println(s.cyan("  + ${source.path}"))
        when (val listing = source.listing) {
            is SourceListing.Missing -> s.out.println("    (not found)")
            is SourceListing.Unreadable ->
                s.out.println("    (mkvmerge could not identify this file: ${listing.reason})")
            // A raw .ass or .srt has no language and no codec_id at all, so that cell is routinely empty.
            is SourceListing.Tracks ->
                listing.tracks.forEach { s.out.println(trackRow(it, absentLanguage = NONE)) }
        }
    }
}

/**
 * Discovered external files, bundled under this episode by variant.
 *
 * The path is not repeated per episode — the legend above carries it — except for a file matched only by
 * episode number, where its name is exactly what is worth seeing.
 */
private fun renderExternals(externals: List<VariantExternals>, s: TextStyle) {
    if (externals.isEmpty()) return
    s.out.println()

    externals.forEach { variant ->
        val extensions = variant.extensions.joinToString(", ") { ".$it" }
        val head = "  + [${variant.variant.label}] ${variantDisplayName(variant.variant)} ($extensions)"
        val byNumber = variant.files.filter { it.tier == MatchTier.EPISODE }

        if (byNumber.isEmpty()) {
            s.out.println(head)
        } else {
            s.out.println(head + s.gray("  (episode match: ${byNumber.joinToString(", ") { it.fileName }})"))
        }

        variant.files.forEach { file ->
            when (val listing = file.listing) {
                is ExternalListing.Unreadable ->
                    s.out.println("    (mkvmerge could not read this file: ${listing.reason})")
                is ExternalListing.Tracks ->
                    if (listing.tracks.isEmpty()) s.out.println("    (no tracks)")
                    else listing.tracks.forEach { s.out.println(externalRow(it, s)) }
            }
        }
    }
}

private fun trackRow(track: ProbedTrack, absentLanguage: String): String = ROW_FORMAT.format(
    track.id,
    track.type.orIfEmpty(UNKNOWN),
    track.codec.orIfEmpty(UNKNOWN),
    track.language.orIfEmpty(absentLanguage),
    if (track.default) "yes" else "no",
    if (track.forced) "yes" else "no",
    track.trackName.orEmpty(),
)

/**
 * One external track's row.
 *
 * Two things set it apart from [trackRow]. The LANG cell says where its value came from — a trailing `?`
 * and gray for a language inferred from a folder rather than read from the file — with the gray applied
 * to the **already-padded** cell, so the escape never lands inside the value and the alignment is
 * unaffected. And the line is stripped of trailing whitespace, because NAME is last and unpadded: an
 * unnamed track would otherwise leave a line with nothing but blanks to show for itself.
 */
private fun externalRow(track: ExternalTrack, s: TextStyle): String {
    // The marker is composed here, not carried: `rus` guessed and `rus` tagged are the same language, and
    // only a reader needs telling them apart.
    val language = pad(track.language?.let { if (track.guessed) "$it?" else it } ?: NONE, 5)
    val row = "  ${pad(track.id, 4)} ${pad(track.type, 10)} ${pad(track.codec, 22)} " +
        "${if (track.guessed) s.gray(language) else language} " +
        "${pad(track.default.yesNo(), 4)} ${pad(track.forced.yesNo(), 4)} ${track.name}"
    return row.trimEnd()
}

/** Null is not "no" but *unknown*: an unprobed file's flags were never read. */
private fun Boolean?.yesNo(): String = when (this) {
    true -> "yes"
    false -> "no"
    null -> NONE
}

private fun clamp(value: Int, min: Int, max: Int): Int = minOf(max, maxOf(min, value))

/** Groovy's elvis falls through on an empty string as well as on null, and every one of these cells
 *  relied on that. */
private fun String?.orIfEmpty(fallback: String): String = if (isNullOrEmpty()) fallback else this
