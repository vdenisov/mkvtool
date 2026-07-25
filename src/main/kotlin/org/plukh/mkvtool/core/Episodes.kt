package org.plukh.mkvtool.core

import java.io.File
import java.util.Locale

/**
 * Episode-metadata semantics: the join key between a media file and its episode, the canonical-name
 * parser and its file-name sanitizer, the two `episodes.*` readers' shapes, and the batch-relative
 * labels a group header is built from. A port of `src/lib/episodes.groovy`.
 *
 * It owns the *shape*, never the I/O: [normalizeYaml] takes an already-parsed map, exactly as v1 did.
 * That seam predates the no-imports rule that forced it and outlives it — callers own reading, parsing
 * and charset policy ([loadMapping], [readLinesDetected]), and hand the result here.
 *
 * Episode numbers are **two-digit zero-padded strings** (`"01"`), both as map keys and in what the parsers
 * return, because they come from and go back into file names. Looking one up with an `Int` silently misses.
 * Three-digit numbering is out of scope: the SxxEyy pattern matches exactly two digits.
 */

/** The join key between a media file and its episode metadata, and the one pattern every command agrees on.
 *  Unanchored, so it finds the token anywhere in the name; `\.?` tolerates the `s01.e01` spelling. */
private val SEASON_EPISODE = Regex("""s(\d\d)\.?e(\d\d)""")

/** The inverse of `rename`'s output pattern. Non-greedy on the show name, so it takes the shortest prefix —
 *  matching what `rename` itself writes. A show name that itself contains `" - S01E01 - "` cannot be
 *  disambiguated and is not worth the complexity of trying. */
private val CANONICAL_NAME = Regex("""^(.+?) - [Ss](\d\d)[Ee](\d\d) - (.+)$""")

/** A trailing `[Studio]` suffix on a canonical title. Greedy, so `T [A] [B]` strips from the first bracket. */
private val TRAILING_SUFFIX = Regex("""\s*\[.+\]$""")

/** The characters Windows rejects in a file name. Removed, not replaced — `Slash/Colon` becomes
 *  `SlashColon`, with no space invented where the original had none. */
private val ILLEGAL_IN_FILENAME = Regex("""[\\/:*?"<>|]""")

/** Trailing dots and spaces, which Windows also rejects at the end of a name. */
private val TRAILING_DOTS_AND_SPACES = Regex("""[. ]+$""")

/** A label that is a plain number, and therefore a candidate for run-collapsing in [formatRanges]. */
private val ALL_DIGITS = Regex("""\d+""")

/** Trimmed off a batch's common prefix in [batchLabels], so a prefix cannot end mid-number. */
private val TRAILING_DIGITS = Regex("""\d+$""")

/** The digit run a name's post-prefix remainder must start with to be labelled. Capped at four digits. */
private val LEADING_NUMBER = Regex("""^(\d{1,4})""")

/** Season and episode as they were spelled in the file name: two digits each, already zero-padded. */
data class SeasonEpisode(val season: String, val episode: String)

/** A file name in `rename`'s canonical `Show - SxxEyy - Title[suffix]` shape, taken apart. */
data class CanonicalName(
    val showName: String,
    val season: String,
    val episode: String,
    val title: String,
)

/**
 * Episode metadata as every consumer wants it, from either source. `episodes.yaml` fills all of it;
 * `episodes.txt` carries names only, so it yields [byEpisode] alone and leaves the rest null — the same
 * one-shape-two-sources arrangement v1 had as two differently-sized maps.
 *
 * [byEpisode] may be **sparse**: a season with a gap, or an entry carrying no episode number, simply has no
 * key for it. Every consumer must handle a miss rather than assume a contiguous 1..n run.
 */
data class EpisodeData(
    val show: String? = null,
    val year: String? = null,
    val season: String? = null,
    val seasonName: String? = null,
    val language: String? = null,
    val byEpisode: Map<String, String> = emptyMap(),
)

/** Season and episode numbers from [baseName], or null when it carries none. Case-insensitive, and the
 *  leftmost token wins when a name somehow holds two. */
fun parseSeasonEpisode(baseName: String?): SeasonEpisode? {
    val match = SEASON_EPISODE.find(baseName.orEmpty().lowercase()) ?: return null
    return SeasonEpisode(match.groupValues[1], match.groupValues[2])
}

/**
 * [name] with the characters Windows rejects in a file name removed, and its trailing dots and spaces cut.
 * Applied when a raw name is turned into a file name — never when it is used as a track or segment title,
 * where the original spelling is exactly what is wanted.
 *
 * Idempotent, so re-sanitizing an already-clean legacy `episodes.txt` line changes nothing. Null yields the
 * empty string, which is load-bearing: it is how `rename` detects that no show name is available.
 */
fun sanitizeForFilename(name: String?): String =
    name.orEmpty()
        .replace(ILLEGAL_IN_FILENAME, "")
        .replace(TRAILING_DOTS_AND_SPACES, "")

/** [baseName] taken apart into show, season, episode and title, or null when it is not in the canonical
 *  shape. The title has a trailing `[Studio]` suffix stripped; a title that is nothing but a suffix
 *  therefore comes back empty. */
fun parseCanonicalName(baseName: String?): CanonicalName? {
    val match = CANONICAL_NAME.find(baseName.orEmpty()) ?: return null
    val groups = match.groupValues
    return CanonicalName(
        showName = groups[1],
        season = groups[2],
        episode = groups[3],
        title = groups[4].replace(TRAILING_SUFFIX, ""),
    )
}

/**
 * Episode number -> name from `episodes.txt` [lines], which carry names only.
 *
 * [offset] is the episode number **of the first line**, not an amount added to a zero-based index: 1 maps
 * line 0 to `"01"`. It is what makes a partial file readable — titles for episodes 11-20 with offset 11 —
 * and it is meaningful for this format alone, since `episodes.txt` has no numbers in it and line order is
 * the only thing tying a name to an episode. There is deliberately no default: every call site states the
 * numbering it is applying, because that is a decision, not a detail.
 *
 * Lines are taken verbatim — untrimmed, and a blank line becomes an empty title for its number. Padding is
 * a minimum width, so a hundredth episode keys as `"100"` and no SxxEyy name can ever match it.
 */
fun indexFromLines(lines: List<String>, offset: Int): Map<String, String> {
    val index = LinkedHashMap<String, String>(lines.size)
    lines.forEachIndexed { i, line -> index[pad(i + offset)] = line }
    return index
}

/**
 * The parsed `episodes.yaml` mapping, normalized. Episode numbers are TheMovieDB's own, so the join against
 * a file name is exact and needs no positional reasoning — which is why [offset][indexFromLines] has no
 * counterpart here.
 *
 * It **throws** on an episode or season number it cannot read as a number, and on an `episodes:` value that
 * is not a list of mappings. That is deliberate and load-bearing: callers run this as [loadMapping]'s
 * `transform`, inside the guard, so a hand-edited `episode: "one"` becomes a classified problem rather than
 * a stack trace. Anything calling it outside the guard owns the exception.
 */
fun normalizeYaml(yaml: Map<*, *>): EpisodeData {
    val index = LinkedHashMap<String, String>()
    val episodes = yaml["episodes"]
    if (episodes != null) {
        require(episodes is List<*>) { "episodes is not a list (found ${episodes.javaClass.simpleName})" }
        for (episode in episodes) {
            if (episode == null) continue
            require(episode is Map<*, *>) {
                "an episode is not a mapping (found ${episode.javaClass.simpleName})"
            }
            val number = episode["episode"] ?: continue
            index[pad(asInt(number, "episode number"))] = (episode["name"] ?: "").toString()
        }
    }
    return EpisodeData(
        show = yaml["show"]?.toString(),
        year = yaml["year"]?.toString(),
        season = yaml["season"]?.let { pad(asInt(it, "season number")) },
        seasonName = yaml["seasonName"]?.toString(),
        language = yaml["language"]?.toString(),
        byEpisode = index,
    )
}

/**
 * `"01-04, 07, 09-10"` — a set of episode labels as compact runs. Anything that is not a plain number is
 * passed through as itself, so a mixed list degrades to a comma-joined one rather than lying about a range.
 *
 * Sorting is lexicographic on the label strings while runs are detected numerically. For the fixed-width
 * labels [batchLabels] produces the two agree; for hand-assembled mixed widths they do not (`"9"` sorts
 * after `"10"`), and a label of more than ten digits throws. Both are v1 behavior, both unreachable through
 * [batchLabels], which caps a label at four digits and takes them all from one batch.
 */
fun formatRanges(labels: Collection<String?>): String {
    val sorted = labels.map { it.toString() }.distinct().sorted()
    if (sorted.isEmpty()) return ""
    if (!sorted.all { it.matches(ALL_DIGITS) }) return sorted.joinToString(", ")

    val runs = ArrayList<Pair<String, String>>()
    var start = sorted[0]
    var previous = sorted[0]
    for (label in sorted.drop(1)) {
        if (label.toInt() == previous.toInt() + 1) {
            previous = label
        } else {
            runs.add(start to previous)
            start = label
            previous = label
        }
    }
    runs.add(start to previous)
    return runs.joinToString(", ") { (from, to) -> if (from == to) from else "$from-$to" }
}

/**
 * A short label per name for a *batch*, to say which episodes are in a group without printing ten
 * sixty-character file names.
 *
 * **Display only.** This is deliberately not [parseSeasonEpisode]: it is batch-relative — it needs the other
 * names to know where the number starts — whereas identity has to be answerable for one file on its own. A
 * wrong guess here costs a slightly odd line in a report; a wrong guess in identity stamps the wrong title
 * into a file. Nothing that renames or resolves an episode number may use it.
 *
 * Two tiers. If every name carries an SxxEyy, use the episode number. Otherwise anchor on what the whole
 * batch shares: the longest common prefix, with any trailing digits trimmed off it, and take the run of
 * digits that follows. Trimming is what keeps 10-19 from collapsing to 0-9 (their common prefix ends
 * mid-number) and what preserves the padding of 01-09. Anchoring is what makes this safe at all: `1080p` and
 * `x264` sit inside the common prefix, so nothing can mistake them for an episode.
 *
 * All-or-nothing: one name whose remainder does not start with digits yields an empty map for the whole
 * batch, because a partly-labelled group is worse than an unlabelled one.
 */
fun batchLabels(baseNames: Collection<String>): Map<String, String> {
    val names = baseNames.toList()
    if (names.isEmpty()) return emptyMap()

    if (names.all { parseSeasonEpisode(it) != null }) {
        return names.associateWith { parseSeasonEpisode(it)!!.episode }
    }

    var prefix = names[0]
    for (name in names) {
        val limit = minOf(prefix.length, name.length)
        var i = 0
        while (i < limit && prefix[i] == name[i]) i++
        prefix = prefix.substring(0, i)
    }
    prefix = prefix.replace(TRAILING_DIGITS, "")

    val labels = LinkedHashMap<String, String>(names.size)
    for (name in names) {
        val rest = if (name.length > prefix.length) name.substring(prefix.length) else ""
        val match = LEADING_NUMBER.find(rest) ?: return emptyMap()
        labels[name] = match.groupValues[1]
    }
    return labels
}

/**
 * The distinct episode labels of one group of [baseNames], or null when the batch is not numbered and the
 * caller has to fall back to a plain file list.
 *
 * This is the *data* behind a layout group's membership line; composing it into words ("episodes 01-03")
 * belongs to the renderer, which knows how a report reads. The labels are group-local — a group is its own
 * batch, so the numbers it shows are anchored on the names it holds.
 */
fun membershipLabels(baseNames: Collection<String>): List<String>? {
    val labels = batchLabels(baseNames)
    if (labels.isEmpty()) return null
    return baseNames.map { labels[it].toString() }.distinct()
}

/**
 * What a directory's episode metadata turned out to be. Classify-don't-decide, the same division
 * [loadMapping] draws: what to *do* about each outcome is the caller's policy, and the three commands
 * genuinely disagree — `rename` cannot work without metadata, `mux` refuses a file it could not read, and
 * `inspect` warns and carries on because metadata only decorates what it prints.
 */
sealed interface EpisodeMetadata {
    /** [name] is the file it came from, which is what a "no title for episode 12" problem names. */
    data class Loaded(val data: EpisodeData, val name: String) : EpisodeMetadata

    /** The file is there and unusable. [message] is [loadMapping]'s bare classified fragment, which the
     *  caller finishes in its own words; [name] is the file it refers to. */
    data class Unusable(val message: String, val name: String) : EpisodeMetadata

    /** Neither file exists. Not a problem in itself — only in the light of what the caller wanted it for. */
    data object Absent : EpisodeMetadata
}

/**
 * Read `episodes.yaml`, else `episodes.txt`, from [dir].
 *
 * The yaml wins when present: it carries real episode numbers, so a season with a gap stays aligned, and
 * it supplies the show name. [offset] applies to the text file **alone** — that file has no numbers in it,
 * so the offset is part of how it is read, while the yaml needs no shifting and must not get one.
 *
 * Charsets differ by design: the yaml is machine-written and read back as explicit UTF-8, while the text
 * file is auto-detected because it is the format a human types (Notepad on a Cyrillic Windows writes
 * cp1251). Forcing UTF-8 there would mangle exactly that case and gain nothing.
 *
 * The yaml is read through [loadMapping] with [normalizeYaml] as the transform, so a hand-edited
 * `episode: "one"` is a classified [EpisodeMetadata.Unusable] rather than the stack trace v1's `rename`
 * met.
 */
fun loadEpisodeMetadata(dir: File, offset: Int): EpisodeMetadata {
    val yamlFile = File(dir, "episodes.yaml")
    val textFile = File(dir, "episodes.txt")

    if (yamlFile.isFile) {
        return when (val load = loadMapping(yamlFile, Charsets.UTF_8, ::normalizeYaml)) {
            is MappingLoad.Loaded -> EpisodeMetadata.Loaded(load.value, yamlFile.name)
            is MappingLoad.Problem -> EpisodeMetadata.Unusable(load.message, yamlFile.name)
        }
    }

    if (textFile.isFile) {
        return EpisodeMetadata.Loaded(
            EpisodeData(byEpisode = indexFromLines(readLinesDetected(textFile), offset)),
            textFile.name,
        )
    }

    return EpisodeMetadata.Absent
}

/** Two-digit zero padding, in the root locale: an episode number is a key and part of a file name, so it
 *  must be the same digits on a machine whose locale would otherwise number in its own script. */
private fun pad(number: Int): String = String.format(Locale.ROOT, "%02d", number)

/** A yaml scalar as an episode or season number, reproducing Groovy's `as int`: a number truncates toward
 *  zero, a quoted number parses (a hand-edited `episode: "12"` has always worked), and anything else —
 *  `"one"` included — throws for [normalizeYaml]'s caller to classify. */
private fun asInt(value: Any, what: String): Int = when (value) {
    is Number -> value.toInt()
    is String -> value.toInt()
    else -> throw IllegalArgumentException("$what is not a number: $value")
}
