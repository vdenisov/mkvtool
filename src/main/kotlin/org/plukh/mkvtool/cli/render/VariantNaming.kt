package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.LegendRow
import org.plukh.mkvtool.core.VariantIdentity
import org.plukh.mkvtool.core.trimForDisplay

/**
 * The two strings that name a discovered variant: what to call it, and where its files live.
 *
 * Both are composed here rather than in the discovery engine, which returns the ingredients and no prose,
 * exactly as it returns episode labels without the words that surround them. They sit in their own file
 * because two renderers need them: the legend and the per-episode blocks of `--identify`, and the check
 * report's external rows.
 */

/**
 * What a variant is called.
 *
 * The directory is the better name when there is one, since that is what a reader recognises; a trailing
 * suffix qualifies it (`[GroupA] rus`) and stands alone when there is no directory. A **collision**
 * variant — one whose leaf name appears under two different parents — is named by its full relative path,
 * because the leaf alone would name two things at once.
 *
 * `(same name)` is the last resort: files matching by name with neither a directory nor a suffix to tell
 * them apart. Suffixes are trimmed for display only — identity always uses the raw bytes.
 */
fun variantDisplayName(variant: VariantIdentity): String {
    val trimmed = trimForDisplay(variant.suffix).orEmpty()
    val leaf = variant.leaf.orEmpty()

    return when {
        variant.collision -> variant.dirRel
        leaf.isNotEmpty() -> if (trimmed.isNotEmpty()) "$leaf $trimmed" else leaf
        trimmed.isNotEmpty() -> trimmed
        else -> "(same name)"
    }
}

/**
 * The path pattern one kind of a variant's files follows, as the legend prints it.
 *
 * A section with no suffix anywhere holds only episode-number matches, whose names bear no relation to
 * the main file's — so `<episode number>` is genuinely all that can be said about them. Extensions are
 * joined with `/` because a section may hold more than one (`<name>.rus.ass/srt`).
 */
fun sectionPattern(row: LegendRow): String {
    val prefix = row.dir?.takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
    val stem = if (row.suffix == null) "<episode number>" else "<name>${row.suffix}"
    return "$prefix$stem.${row.extensions.joinToString("/")}"
}
