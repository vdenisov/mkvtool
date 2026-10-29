package org.plukh.mkvtool.core

import java.io.File

/**
 * Which files a batch command works on: the extension rule, the file masks, and the two lists every
 * caller needs. v1 kept a copy of the `compileMasks` and file-listing block in each of `mux` and
 * `inspect`; this is the one shared version, because the mask rule decides which files get muxed.
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
 * A pattern that names an **existing file in [dir]** is matched by name equality instead of as a glob, so
 * a file whose own name holds a `*` or `?` (legal outside Windows) selects only itself. The name compared
 * is the one on disk, so a pattern typed in another case still selects the file where the file system
 * ignores case.
 *
 * Everything else goes through [maskRegex] over the **bare file name**, so a pattern naming a directory
 * matches nothing, v1's behaviour and harmless: these commands only ever list one directory. The one
 * exception is a leading `**` and slash, which also matches no directory at all.
 */
fun compileMasks(patterns: List<String>, dir: File): List<FileMask> =
    patterns.map { pattern ->
        val named = File(pattern).let { if (it.isAbsolute) it else File(dir, pattern) }
        // A same-named file elsewhere must not stand in for the one in dir: `extras/E01.mkv` is not `E01.mkv`.
        if (named.isFile && named.canonicalFile.parentFile == dir.canonicalFile) {
            val literal = named.canonicalFile.name
            return@map { candidate: File -> candidate.name == literal }
        }
        val regex = maskRegex(pattern)
        return@map { candidate: File -> regex.matches(candidate.name) }
    }

/**
 * [pattern] as the tool's one glob: `*` is any run within a path segment, `**` any run across segments
 * (at the start of a segment and before a slash, also no directory at all), `?` one character, and
 * everything else literal. Brackets and braces are literal because release names are full of them
 * (`[MC-Ent]`), where a JDK glob would read a character class; it also means no pattern can be invalid.
 * Backslashes fold to forward slashes, since a Windows caller types the separator that way. Case is
 * ignored on Windows and significant elsewhere.
 */
fun maskRegex(pattern: String): Regex = globRegex(listOf(pattern to true))

/**
 * [parts] joined into one [maskRegex] glob, where only a part flagged `true` is glob text and every other part
 * matches literally, its `*` and `?` included: a template's own text against the values substituted into it.
 */
fun globRegex(parts: List<Pair<String, Boolean>>): Regex {
    val chars = parts.flatMap { (text, glob) -> (if (glob) text.replace('\\', '/') else text).map { it to glob } }
    fun globAt(i: Int, token: String) =
        token.indices.all { k -> chars.getOrNull(i + k)?.let { it.second && it.first == token[k] } == true }

    val regex = StringBuilder()
    var i = 0
    while (i < chars.size) {
        val (c, glob) = chars[i]
        val segmentStart = i == 0 || chars[i - 1].first == '/'
        val token = when {
            !glob -> (if (c in REGEX_SPECIALS) "\\$c" else c.toString()) to 1
            segmentStart && globAt(i, "**/") -> "(?:.*/)?" to 3
            globAt(i, "**") -> ".*" to 2
            c == '*' -> "[^/]*" to 1
            c == '?' -> "[^/]" to 1
            c in REGEX_SPECIALS -> "\\$c" to 1
            else -> c.toString() to 1
        }
        regex.append(token.first)
        i += token.second
    }
    // Windows file names ignore case, so a mask there must too.
    val caseRule = if (File.separatorChar == '\\') setOf(RegexOption.IGNORE_CASE) else emptySet()
    // `.*` must cross a line terminator like `[^/]*` does, or `**` would match less than `*`.
    return Regex(regex.toString(), caseRule + RegexOption.DOT_MATCHES_ALL)
}

/**
 * The files of one directory, before and after the masks.
 *
 * [all] is every media file there, name-sorted. [selected] is what the masks left — a subset of [all],
 * in the same order.
 *
 * The split is load-bearing for `inspect`: external-file discovery runs against [all], because matching
 * against the masked list would dump the rest of the season's dubs into "unmatched", which is the exact
 * opposite of what narrowing to one episode asks for. What a mask narrows is what gets *reported*.
 *
 * [matched] is the masks' answer *before* the extension rule — every file they left, media or not, and
 * therefore a superset of [selected]. `mux` walks it rather than [selected] because it reports what it
 * passes over (`*** Skipping notes.txt`), and it tells the two empty batches apart: a mask that matched no
 * file at all is a typo, while a mask that matched only a `.txt` found something that is simply not media.
 * `inspect` has no use for it.
 */
data class MediaSelection(val all: List<File>, val selected: List<File>, val matched: List<File>)

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

    return MediaSelection(
        all = files.filter(isMedia),
        selected = masked.filter(isMedia),
        matched = masked,
    )
}

/**
 * What a command says when the masks left it no media to work on — the same sentence in `inspect` and in
 * `mux`, which is why it is composed once here.
 *
 * With no masks the batch is simply empty and the extensions it looked for are the useful thing to name;
 * with masks the patterns are, since a typo is the likely cause. Sorted so the list reads the same way
 * twice.
 */
fun noMediaMessage(
    allowedExtensions: Set<String>,
    fileMasks: List<String> = emptyList(),
    excludeMasks: List<String> = emptyList(),
): String =
    if (fileMasks.isEmpty() && excludeMasks.isEmpty()) {
        "*** No media files (${allowedExtensions.sorted().joinToString(", ")}) in the current directory"
    } else {
        "*** No media files match: ${maskDescription(fileMasks, excludeMasks)}"
    }

/** The masks as the reports name them: the positional patterns, then each exclusion with its own flag. */
fun maskDescription(fileMasks: List<String>, excludeMasks: List<String>): String =
    (fileMasks + excludeMasks.map { "--exclude $it" }).joinToString(", ")

/**
 * A path as the config wrote it, anchored on the working directory unless it is already absolute.
 *
 * Every path a config carries — `destinationDir`, a resolved `additionalSources` entry — is relative to
 * the media directory, which is the directory these commands are pointed at rather than the one the JVM
 * happens to have started in.
 */
internal fun resolveAgainst(dir: File, path: String): File =
    File(path).let { if (it.isAbsolute) it else File(dir, path) }

/**
 * [name]'s extension, lower-cased, without the dot; empty when it has none.
 *
 * Matches commons-io's `FilenameUtils.getExtension` on a bare file name, dot-files included: `.mkv` has
 * the extension `mkv` and is therefore a media file, exactly as v1 read it. Kotlin's no-argument
 * `lowercase` is locale-independent, which is the [Locale.ROOT] the discovery engine also folds in.
 */
fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

// What a regex reads as syntax outside a character class; every other character stands for itself.
private const val REGEX_SPECIALS = """\.^$|?*+()[]{}"""
