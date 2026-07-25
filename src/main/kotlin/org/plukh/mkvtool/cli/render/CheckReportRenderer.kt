package org.plukh.mkvtool.cli.render

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
import org.plukh.mkvtool.core.TrackSlot
import org.plukh.mkvtool.core.TrackValueFinding
import org.plukh.mkvtool.core.formatRanges
import org.plukh.mkvtool.out.ResultTextRenderer
import org.plukh.mkvtool.out.TextStyle
import org.plukh.mkvtool.out.plural
import org.plukh.mkvtool.out.pluralize

/**
 * The consistency check's text form. Bound to [CheckReport] once, so `inspect` and `mux`'s pre-flight
 * render the same report identically by construction — the only thing that differs between them is the
 * header label, which the model carries.
 *
 * Everything here is presentation: the column grid, the differing-cell highlight, the file-evidence
 * lists, and every phrase v1 composed inside the check itself. The model composes no text at all.
 *
 * [verbose] is the `--check-verbose` modifier, reaching here as a render hint. It changes only how much
 * of each file list is shown, never what the report says, which is why it is a renderer setting and not
 * a field on the model.
 */
class CheckReportRenderer(private val verbose: Boolean = false) : ResultTextRenderer<CheckReport> {

    override fun render(result: CheckReport, style: TextStyle) {
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

        // Internal tracks then external files: two loops rather than one over their union, because their
        // ids are different types (a track number, a slot key) and only the tracks' ids index anything.
        renderSlotGroups(layout.trackGroups, isLargest, limit, style) { id, slot, differing ->
            trackRow(id, slot, differing, layout, nameWidth, style)
        }
        renderSlotGroups(layout.externalGroups, isLargest, limit, style) { _, slot, differing ->
            externalRow(slot, differing, nameWidth, style)
        }
    }

    /**
     * Which rows one kind of slot contributes, and which of them name their files. Identical for tracks
     * and for external files — only the row itself differs, which is what [row] supplies.
     */
    private fun <K, S : Slot> renderSlotGroups(
        slotGroups: List<SlotGroup<K, S>>,
        isLargest: Boolean,
        limit: Int,
        style: TextStyle,
        row: (K, S, Set<SignatureField>) -> String,
    ) {
        for (slotGroup in slotGroups) {
            if (slotGroup.consistent) {
                val slot = slotGroup.groups[0].slot ?: continue
                style.out.println(row(slotGroup.id, slot, emptySet()))
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
                style.out.println(row(slotGroup.id, slot, if (isReference) emptySet() else varying))
            }
        }
    }

    /** One internal track's row, identified by its track id — the only id the video titles are keyed by. */
    private fun trackRow(
        id: Int,
        slot: TrackSlot,
        differing: Set<SignatureField>,
        layout: LayoutGroup,
        nameWidth: Int,
        style: TextStyle,
    ): String {
        val name = if (slot.signature.type == "video") videoNameFor(layout, id) else displayName(slot)
        return row(id, slot, differing, fitName(name, nameWidth), style)
    }

    /**
     * One external file's row, identified by its label in the ID column — which variant it belongs to is
     * already said by that label and spelled out in the legend, so composing the two would invent
     * "[Studio] - [Studio]". Its NAME is its own track name exactly like an internal track's; an external
     * file has no group-wide video title to stand in for, and [displayName] answers "-" for a video slot
     * either way.
     */
    private fun externalRow(
        slot: ExternalSlot,
        differing: Set<SignatureField>,
        nameWidth: Int,
        style: TextStyle,
    ): String = row(slot.label, slot, differing, fitName(displayName(slot), nameWidth), style)

    /**
     * One table row, given its already-resolved NAME cell. The NAME column is last and therefore unpadded
     * (no trailing whitespace), and it is the only cell that can be highlighted on its own here.
     */
    private fun row(
        idCell: Any,
        slot: Slot,
        differing: Set<SignatureField>,
        name: String,
        style: TextStyle,
    ): String {
        val signature = slot.signature
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
    private fun videoNameFor(layout: LayoutGroup, id: Int): String {
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
