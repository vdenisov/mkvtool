package org.plukh.mkvtool.core

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * Which files a batch command works on: the extension rule, the file masks, and the two lists every
 * caller needs. A port of the `compileMasks` and file-listing block v1 kept in both `inspect.groovy` and
 * `mux.groovy`.
 *
 * v1 duplicated `compileMasks` deliberately — a shared helper cost an `evaluate()` load there, and ten
 * lines of CLI plumbing were not worth it. An ordinary function costs nothing, and the mask rule decides
 * *what gets inspected and muxed*, which is not plumbing.
 */

/**
 * What counts as a main source when the config names no `allowedExtensions`.
 *
 * `mka`/`mks` are in it because a directory of external tracks is a legitimate thing to inspect on its
 * own, not only as companions to something else.
 */
val DEFAULT_ALLOWED_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "mka", "mks",
)

/**
 * One compiled file mask: a predicate over a candidate file.
 *
 * Unix shells expand `*.mkv` before the process ever sees it, but `cmd.exe` passes the literal string
 * through, so the expansion happens here or not at all.
 */
typealias FileMask = (File) -> Boolean

/**
 * [patterns] as predicates, resolved against [dir].
 *
 * A pattern that names an **existing file** is matched by bare name equality instead of as a glob. That is
 * the only way to select a file whose own name holds glob metacharacters: as a glob, `Odd[1].mkv` also
 * matches `Odd1.mkv` and can never name itself.
 *
 * Everything else becomes a JDK `glob:` matcher over the **bare file name** — backslashes folded to
 * forward slashes first, since a Windows caller types the separator that way. Matching the name alone is
 * what makes a pattern holding a `/` match nothing at all, v1's behaviour and harmless: these commands
 * only ever list one directory.
 */
fun compileMasks(patterns: List<String>, dir: File): List<FileMask> =
    patterns.map { pattern ->
        val named = File(pattern).let { if (it.isAbsolute) it else File(dir, pattern) }
        if (named.isFile) {
            val literal = named.name
            return@map { candidate: File -> candidate.name == literal }
        }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace('\\', '/'))
        return@map { candidate: File -> matcher.matches(Paths.get(candidate.name)) }
    }

/**
 * The media files of one directory, before and after the masks.
 *
 * [all] is every media file there, name-sorted. [selected] is what the masks left — a subset of [all],
 * in the same order.
 *
 * The split is load-bearing for `inspect`: external-file discovery runs against [all], because matching
 * against the masked list would dump the rest of the season's dubs into "unmatched", which is the exact
 * opposite of what narrowing to one episode asks for. What a mask narrows is what gets *reported*.
 */
data class MediaSelection(val all: List<File>, val selected: List<File>)

/**
 * The media files directly in [dir] — top level only, never recursive — split by [fileMasks] and
 * [excludeMasks].
 *
 * A file is included when it matches **any** include mask (or when there are none) and no exclude mask.
 * The extension test is case-insensitive; [allowedExtensions] is therefore expected in lower case, as
 * both the defaults and a hand-written config are.
 */
fun selectMedia(
    dir: File,
    allowedExtensions: Set<String>,
    fileMasks: List<String> = emptyList(),
    excludeMasks: List<String> = emptyList(),
): MediaSelection {
    val includes = compileMasks(fileMasks, dir)
    val excludes = compileMasks(excludeMasks, dir)

    val files = dir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
    val isMedia = { file: File -> extensionOf(file.name) in allowedExtensions }

    var masked = files
    if (includes.isNotEmpty()) masked = masked.filter { file -> includes.any { it(file) } }
    if (excludes.isNotEmpty()) masked = masked.filter { file -> excludes.none { it(file) } }

    return MediaSelection(all = files.filter(isMedia), selected = masked.filter(isMedia))
}

/**
 * [name]'s extension, lower-cased, without the dot; empty when it has none.
 *
 * Matches commons-io's `FilenameUtils.getExtension` on a bare file name, dot-files included: `.mkv` has
 * the extension `mkv` and is therefore a media file, exactly as v1 read it. Kotlin's no-argument
 * `lowercase` is locale-independent, which is the [Locale.ROOT] the discovery engine also folds in.
 */
fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
