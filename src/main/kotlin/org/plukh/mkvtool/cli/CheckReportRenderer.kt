package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.AmbiguousTracksFinding
import org.plukh.mkvtool.core.ChapterFinding
import org.plukh.mkvtool.core.CheckReport
import org.plukh.mkvtool.core.ExternalSlot
import org.plukh.mkvtool.core.ExternalValueFinding
import org.plukh.mkvtool.core.Finding
import org.plukh.mkvtool.core.LayoutFinding
import org.plukh.mkvtool.core.LayoutGroup
import org.plukh.mkvtool.core.SignatureField
import org.plukh.mkvtool.core.Slot
import org.plukh.mkvtool.core.SlotGroup
import org.plukh.mkvtool.core.TrackValueFinding
import org.plukh.mkvtool.core.formatRanges
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import org.plukh.mkvtool.out.plural
import org.plukh.mkvtool.out.pluralize

/**
 * The consistency check's text form. Shared verbatim by `inspect` and by `mux`'s pre-flight — the same
 * report must render identically in both, so this is one renderer rather than one per command, and the
 * only thing that differs is the header label carried on the model.
 *
 * Everything here is presentation: the column grid, the differing-cell highlight, the file-evidence
 * lists, and every phrase v1 composed inside the check itself. The model composes no text at all.
 *
 * [verbose] is the `--check-verbose` modifier. It changes only how much of each file list is shown, never
 * what the report says, which is why it is a renderer setting and not a field on the model.
 */
class CheckReportRenderer(private val verbose: Boolean = false) : ResultTextRenderer {

    override fun render(result: CommandResult, style: TextStyle) {
        if (result !is CheckReport) return
        val out = style.out
        val limit = if (verbose) Int.MAX_VALUE else DEFAULT_FILE_LIST_LIMIT

        var header = "*** ${result.headerLabel}: ${plural(result.readable.size, "file")}"
        if (result.unreadable.isNotEmpty()) {
            header += " (${result.unreadable.size} could not be identified by mkvmerge and are excluded)"
        }
        out.println(style.cyan(header))
        if (result.unreadable.isNotEmpty()) {
            // Always the default limit, even under --check-verbose: this list is about setup, not about
            // the comparison the flag widens.
            formatFileList(result.unreadable.map { "${it.fileName} (${it.reason})" }, "      ")
                .forEach { out.println(it) }
        }
        if (result.readable.isEmpty()) {
            out.println()
            return
        }
        out.println()

        val nameWidth = nameWidth(result)
        val multipleLayouts = result.layouts.size > 1
        result.layouts.forEachIndexed { index, layout ->
            if (multipleLayouts) renderLayoutHeader(layout, index, limit, style)
            renderTable(layout, index == 0, nameWidth, limit, style)
            out.println()
        }

        renderDuplicates(result, style)
        renderChapters(result, limit, style)
        renderSummary(result, style)
        out.println()
    }

    /** `*** Layout 2 (3 files - episodes 04-06): video, audio, subs + A B` */
    private fun renderLayoutHeader(layout: LayoutGroup, index: Int, limit: Int, style: TextStyle) {
        var shape = layout.trackTypes.joinToString(", ") { shortType(it) }
        // Labels only, not the variant names: the table below lists every external file as a row, so
        // spelling them out here says the same thing twice and wraps the line. The legend decodes them.
        if (layout.externalLabels.isNotEmpty()) shape += " + ${layout.externalLabels.joinToString(" ")}"

        // Every group says which files are in it, the largest included: the question is "here are your
        // muxing passes and what goes in each", and an unnamed group is a missing answer. When the batch
        // is numbered this rides in the header so the whole plan reads off the "***" lines; otherwise the
        // names go below, since they cannot fit on one line. No "<-" marker either way — that means
        // "these rows deviate", a different question from "these files are here".
        val members = layout.episodeLabels?.let {
            "${pluralize(it.size, "episode")} ${formatRanges(it)}"
        }
        val count = plural(layout.fileNames.size, "file") + (members?.let { " - $it" } ?: "")
        style.out.println(style.cyan("*** Layout ${index + 1} ($count): $shape"))
        if (members == null) {
            formatFileList(layout.fileNames, "      ", limit).forEach { style.out.println(style.gray(it)) }
        }
    }

    /**
     * One layout group's table. Row count is bounded by track count, not file count, so a 200-episode
     * batch prints as compactly as a 3-episode one. All tracks are listed, not only the varying ones:
     * this doubles as the batch's authoritative track map, which is what you read to check `config.yaml`'s
     * numeric ids against reality.
     */
    private fun renderTable(
        layout: LayoutGroup,
        isLargest: Boolean,
        nameWidth: Int,
        limit: Int,
        style: TextStyle,
    ) {
        val out = style.out
        out.println(
            style.cyan(
                "    ${pad("ID", 4)} ${pad("TYPE", 6)} ${pad("CODEC", 20)} " +
                    "${pad("LANG", 5)} ${pad("DEF", 4)} ${pad("FOR", 4)} NAME"
            )
        )

        val slotGroups: List<SlotGroup<*, *>> = layout.trackGroups + layout.externalGroups
        for (slotGroup in slotGroups) {
            if (slotGroup.consistent) {
                val slot = slotGroup.groups[0].slot ?: continue
                out.println(row(slotGroup.id, slot, emptySet(), layout, nameWidth, style))
                continue
            }
            val varying = slotGroup.varying.toSet()
            val maxSize = slotGroup.groups[0].fileNames.size
            // In the common (largest) group the strict-majority row is the reference: unhighlighted and
            // unnamed, since listing the norm would be dozens of files. An outlier group has no
            // reference — every value there is a deviation, so every row names its files.
            val strictMajority = isLargest && slotGroup.groups.size > 1 &&
                slotGroup.groups[1].fileNames.size < maxSize
            slotGroup.groups.forEachIndexed { i, group ->
                val isReference = i == 0 && strictMajority
                if (!isReference) printMinority(group.fileNames, limit, style)
                // A slot absent from some files cannot occur inside a layout group, which is defined by
                // having the same slots; the guard keeps a malformed model from throwing.
                val slot = group.slot ?: return@forEachIndexed
                out.println(
                    row(slotGroup.id, slot, if (isReference) emptySet() else varying, layout, nameWidth, style)
                )
            }
        }
    }

    /**
     * One table row. The NAME column is last and therefore unpadded (no trailing whitespace), and it is
     * the only cell that can be highlighted on its own here.
     *
     * An external row is identified by its label in the ID column, and its NAME is its own track name
     * exactly like an internal track's — which variant it belongs to is already said by that label and
     * spelled out in the legend, so composing the two would invent "[Studio] - [Studio]".
     */
    private fun row(
        id: Any?,
        slot: Slot,
        differing: Set<SignatureField>,
        layout: LayoutGroup,
        nameWidth: Int,
        style: TextStyle,
    ): String {
        val signature = slot.signature
        val idCell = if (slot is ExternalSlot) slot.label else id
        // displayName, not `name ?: "-"`: an *empty* track name reads as "-" too, which is what makes an
        // empty-vs-set split highlight visibly instead of flashing a blank cell.
        val name = if (signature.type == "video") {
            fitName(videoNameFor(layout, id), nameWidth)
        } else {
            fitName(displayName(slot), nameWidth)
        }
        return "    ${pad(idCell, 4)} " +
            "${cell(shortType(signature.type), 6, SignatureField.TYPE in differing, style)} " +
            "${cell(signature.codec, 20, SignatureField.CODEC in differing, style)} " +
            "${langCell(slot, SignatureField.LANGUAGE in differing, style)} " +
            "${cell(if (signature.default) "yes" else "no", 4, SignatureField.DEFAULT in differing, style)} " +
            "${cell(if (signature.forced) "yes" else "no", 4, SignatureField.FORCED in differing, style)} " +
            if (SignatureField.NAME in differing) style.yellow(name) else name
    }

    /**
     * A video row stands for every file in the group, and their titles routinely differ — that is why the
     * name is not compared. So show the title when they all agree, and say that it varies when they do
     * not, rather than picking one file's arbitrarily or hiding it entirely.
     */
    private fun videoNameFor(layout: LayoutGroup, id: Any?): String {
        val names = layout.videoNamesById[id] ?: emptyList()
        if (names.size != 1) return if (names.isNotEmpty()) "(per file)" else "-"
        return names[0].ifEmpty { "-" }
    }

    private fun renderDuplicates(result: CheckReport, style: TextStyle) {
        if (result.duplicates.isEmpty()) return
        val out = style.out
        out.println(style.cyan("*** Ambiguous track IDs"))
        for (duplicate in result.duplicates) {
            val name = if (!duplicate.name.isNullOrEmpty()) "\"${duplicate.name}\"" else "no name"
            out.println(
                "    Tracks ${duplicate.ids.joinToString(" and ")} are both ${duplicate.type} / " +
                    "${duplicate.language} / ${duplicate.codec} with $name, " +
                    "in ${plural(duplicate.fileNames.size, "file")}."
            )
            out.println("    ID-based selection cannot distinguish them; check which one config.yaml means.")
        }
        out.println()
    }

    private fun renderChapters(result: CheckReport, limit: Int, style: TextStyle) {
        val chapters = result.chapters ?: return
        style.out.println(
            style.cyan(
                "*** Chapters: present in ${plural(chapters.withChapters.size, "file")}, " +
                    "absent in ${chapters.withoutChapters.size}"
            )
        )
        val minority = if (chapters.withChapters.size < chapters.withoutChapters.size) {
            chapters.withChapters
        } else {
            chapters.withoutChapters
        }
        printMinority(minority, limit, style)
        style.out.println()
    }

    /**
     * Without a config there is nothing to classify against, so the count of differences is reported and
     * the reader is pointed at how to classify them: the per-item labels all assume selected tracks.
     */
    private fun renderSummary(result: CheckReport, style: TextStyle) {
        val out = style.out
        val (blocking, informational) = result.findings.partition { it.blocking }

        if (!result.hasConfig) {
            if (result.findings.isNotEmpty()) {
                out.println(
                    style.yellow(
                        "*** ${plural(result.findings.size, "difference")} across the batch " +
                            "(see the tables above)."
                    )
                )
                out.println(
                    "***   Add a config.yaml, or --config <path>, to classify which affect selected tracks."
                )
            } else {
                out.println(style.green(consistentLine(result)))
            }
            return
        }

        if (blocking.isNotEmpty()) {
            out.println(
                style.yellow(
                    "*** ${plural(blocking.size, "discrepancy", "discrepancies")} " +
                        (if (blocking.size == 1) "affects a track" else "affect tracks") +
                        " that config.yaml selects:"
                )
            )
            blocking.forEach { out.println("      ${describe(it)}") }
        }
        if (informational.isNotEmpty()) {
            out.println("*** ${informational.size} informational (does not affect what gets muxed):")
            informational.forEach { out.println("      ${describe(it)}") }
        }
        if (blocking.isEmpty() && informational.isEmpty()) out.println(style.green(consistentLine(result)))
    }

    private fun consistentLine(result: CheckReport): String {
        val what = if (result.hasExternals) "Track structure and external files are" else "Track structure is"
        return "*** $what consistent across ${plural(result.readable.size, "file")}."
    }

    /** One finding as the sentence the summary lists it by. */
    private fun describe(finding: Finding): String = when (finding) {
        is LayoutFinding -> describeLayout(finding)
        is TrackValueFinding -> {
            // An empty configured title is treated as no title, as v1's Groovy truthiness did.
            val title = finding.configTitle?.takeIf { it.isNotEmpty() }?.let { ", config title \"$it\"" } ?: ""
            "track ${finding.id} (${finding.type}$title) - ${differs(finding.varying)} " +
                "across ${finding.groupCount} groups"
        }
        is ExternalValueFinding ->
            "external ${finding.label} ${finding.variantName} (${finding.type}) - " +
                "${differs(finding.varying)} across ${finding.groupCount} groups"
        is AmbiguousTracksFinding -> {
            val selects = if (finding.selectedIds.isNotEmpty()) {
                " and config.yaml selects ${pluralize(finding.selectedIds.size, "track")} " +
                    finding.selectedIds.joinToString(", ")
            } else {
                ""
            }
            "tracks ${finding.ids.joinToString(", ")} are ambiguous$selects"
        }
        ChapterFinding -> "chapters are present in some files and not others"
    }

    private fun describeLayout(finding: LayoutFinding): String {
        val verb = if (finding.fileCount == 1) "uses" else "use"
        val what = when {
            finding.internalDiffers && finding.externalsDiffer ->
                "a different track layout and a different set of external files"
            finding.externalsDiffer -> "a different set of external files"
            else -> "a different track layout"
        }
        val files = "${plural(finding.fileCount, "file")} $verb $what"
        return when {
            finding.affectedIds.isNotEmpty() ->
                "$files, at selected ${pluralize(finding.affectedIds.size, "track")} " +
                    finding.affectedIds.joinToString(", ")
            finding.internalDiffers -> "$files (selected tracks unaffected)"
            // Never blocking: nothing selects an external file by id, so this cannot mux the wrong
            // track. It is a separate muxing pass, which is the whole point of saying it.
            else -> "$files, so they need their own pass"
        }
    }

    private fun differs(varying: List<SignatureField>): String =
        varying.joinToString(", ") { it.name.lowercase() } + if (varying.size == 1) " differs" else " differ"

    /**
     * Size the NAME column to the longest name actually present, so it is not clipped on wide screens nor
     * padded to a fixed width when everything is short. Clamped so one pathological title cannot blow the
     * line width.
     */
    private fun nameWidth(result: CheckReport): Int {
        val lengths = result.layouts.flatMap { layout ->
            val slots = (layout.trackGroups + layout.externalGroups)
                .flatMap { group -> group.groups.mapNotNull { it.slot } }
            slots.map { displayName(it).length } + layout.videoNamesById.values.flatten().map { it.length }
        }
        return minOf(60, maxOf(12, lengths.maxOrNull() ?: 0))
    }

    /** "-" is the one glyph for "nothing here". It replaced "(no name)" because it reads better in a
     *  column that competes for width, and it still highlights visibly when a name splits between empty
     *  and set — which an empty cell never did. */
    private fun displayName(slot: Slot): String =
        if (slot.signature.type == "video") "-" else slot.signature.name.orEmpty().ifEmpty { "-" }

    /**
     * Print a file list with the `<-` marker on the *last* named file, since the list sits above the row
     * it describes: the marker adjacent to that row reads more clearly than one at the top, next to the
     * unrelated row above. The rest is a plain hanging indent, so a multi-line list is not mistaken for
     * several groups, and the "... and N more" summary stays below the marker.
     *
     * The list is evidence, not primary data: gray, so the table rows stand out against it. The marker
     * keeps the default foreground — its job is to stay findable inside the gray block — so this is the
     * one sanctioned place where a line holds two color segments, still whole segments and never
     * mid-word.
     */
    private fun printMinority(names: List<String>, limit: Int, style: TextStyle) {
        val lines = formatFileList(names, HANGING_INDENT, limit)
        if (lines.isEmpty()) return
        val markIndex = lines.indexOfLast { !it.contains("... and ") }.takeIf { it >= 0 } ?: 0
        lines.forEachIndexed { i, line ->
            if (i == markIndex) {
                style.out.println("           <- " + style.gray(line.substring(HANGING_INDENT.length)))
            } else {
                style.out.println(style.gray(line))
            }
        }
    }

    private companion object {
        const val DEFAULT_FILE_LIST_LIMIT = 8
        const val HANGING_INDENT = "              "
    }
}

/**
 * One indented file name per line, truncating past [limit]. File names are long and comma-joining several
 * per line runs them together; a plain column is far easier to skim and to count. ASCII only ("..." not
 * "…"), since this output lands on Windows consoles running a legacy codepage.
 *
 * [limit] may be `Int.MAX_VALUE` under `--check-verbose`, so it is clamped against the list size before
 * anything is taken from it.
 */
internal fun formatFileList(names: List<String>, indent: String, limit: Int = 8): List<String> {
    val show = minOf(limit, names.size)
    val lines = names.take(show).map { indent + it }.toMutableList()
    if (names.size > show) lines += "$indent... and ${names.size - show} more"
    return lines
}

/** A short type name for the table and the layout descriptions. */
internal fun shortType(type: String): String = if (type == "subtitles") "subs" else type

/** Truncate an over-long track name so it cannot break the table's alignment. ASCII "..." rather than an
 *  ellipsis, for the same Windows-console reason as [formatFileList]. */
internal fun fitName(name: String, width: Int): String =
    if (name.length > width) name.substring(0, width - 3) + "..." else name

/** A fixed-width cell, padded *before* any color is applied so escapes never count toward the width. */
private fun pad(value: Any?, width: Int): String = (value?.toString() ?: "").padEnd(width)

private fun cell(value: Any?, width: Int, differing: Boolean, style: TextStyle): String {
    val padded = pad(value, width)
    return if (differing) style.yellow(padded) else padded
}

/**
 * The LANG cell, which grays a guessed value (`rus?`) — a language inferred from a folder name rather
 * than read from the file. A differing value still wins the cell: the yellow diff-highlight is this
 * table's whole job, and a guess that also varied would be better shown as varying. In practice a guess
 * never varies within its slot, since every file there shares one extension and one folder guess, but
 * the precedence is the correct one to state.
 */
private fun langCell(slot: Slot, differing: Boolean, style: TextStyle): String {
    val padded = pad(slot.signature.language, 5)
    return when {
        differing -> style.yellow(padded)
        slot is ExternalSlot && slot.guessed -> style.gray(padded)
        else -> padded
    }
}
