package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.Slot
import org.plukh.mkvtool.out.TextStyle

/**
 * The column grid and the file lists every report draws with. `--identify` shares one grid with the check
 * report — that is what makes the columns line up straight down a page that mixes the two — so these live
 * beside both rather than inside either.
 */

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
internal fun pad(value: Any?, width: Int): String = (value?.toString() ?: "").padEnd(width)

internal fun cell(value: Any?, width: Int, differing: Boolean, style: TextStyle): String {
    val padded = pad(value, width)
    return if (differing) style.yellow(padded) else padded
}

/**
 * The LANG cell, which marks a guessed value — a language inferred from a folder name rather than read
 * from the file — with a trailing `?` and gray.
 *
 * Both the marker and the colour are composed here, because provenance is something said *about* the
 * language rather than part of it: a guessed `rus` and a tagged `rus` are the same language and must
 * group together, which they cannot do if the marker rides in the value the grouping compares.
 *
 * A differing value still wins the cell: the yellow diff-highlight is this table's whole job, and a guess
 * that also varied would be better shown as varying. In practice a guess never varies within its slot,
 * since every file there shares one extension and one folder guess, but the precedence is the correct
 * one to state.
 */
internal fun langCell(slot: Slot, differing: Boolean, style: TextStyle): String {
    val guessed = slot.guessed
    val padded = pad(if (guessed) "${slot.signature.language}?" else slot.signature.language, 5)
    return when {
        differing -> style.yellow(padded)
        guessed -> style.gray(padded)
        else -> padded
    }
}
