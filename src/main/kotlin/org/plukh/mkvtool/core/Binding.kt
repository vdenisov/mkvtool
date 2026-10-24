package org.plukh.mkvtool.core

import java.io.File
import java.util.Locale

/** Which rule paired a file: a name rule, or the number rule that read its episode. */
enum class BindingRule { EXACT, SUFFIX, REGEX, SXXEYY, SEQUENCE }

/** A main file's [episode], mapped from the release number [rule] [read]. */
data class MainEpisode(val main: File, val read: Int, val episode: Int, val rule: BindingRule)

/** A source file paired with [main] by [rule]. [sub] is the `.sub` half of a VobSub pair whose `.idx` is [entry]. */
data class BoundFile(val entry: TreeEntry, val main: File, val rule: BindingRule, val sub: TreeEntry? = null) {
    /** [entry], and [sub] when there is one. */
    val files: List<TreeEntry> get() = listOfNotNull(entry, sub)
}

/** One `files:` source's files paired with main files, by its config [path]. */
data class SourceBinding(val path: String, val bound: List<BoundFile>)

/** Why a batch cannot be bound. Any one of them fails the whole batch; the user fixes the files or the config. */
sealed interface BindingProblem

/** Neither the files nor the metadata name a season. */
data object SeasonUnknown : BindingProblem

/** More than one season among the files, which read [bySeason] (season to the names read), and the [metadata]. */
data class SeasonConflict(val bySeason: Map<String, List<String>>, val metadata: String?) : BindingProblem

/** Several [mains] on one [episode]. */
data class DuplicateEpisode(val episode: Int, val mains: List<File>) : BindingProblem

/** A `sequence` over [files] files where [expected] were wanted; null when there was nothing to count against. */
data class SequenceCount(val path: String, val files: Int, val expected: Int?) : BindingProblem

/** A file source [path]'s masks match that pairs with no main file. */
data class UnpairedFile(val path: String, val entry: TreeEntry) : BindingProblem

/** A file named after [main], which was skipped for having no episode. */
data class SkippedMainFile(val path: String, val entry: TreeEntry, val main: File) : BindingProblem

/** A file named after several [mains] at once, which share a base name. */
data class NameTie(val path: String, val entry: TreeEntry, val mains: List<File>) : BindingProblem

/** Several [entries] of one source paired with one [main]. */
data class SharedMain(val path: String, val main: File, val entries: List<TreeEntry>) : BindingProblem

/** One file paired by several sources, at [paths]. */
data class SharedFile(val entry: TreeEntry, val paths: List<String>) : BindingProblem

/**
 * The batch's one [season], each main file's episode, the mains no rule read ([skipped], a warning), and each
 * `files:` source's pairings. Nothing may act on it while [problems] is not empty.
 */
data class BindingResult(
    val season: String?,
    val mains: List<MainEpisode>,
    val skipped: List<File>,
    val sources: List<SourceBinding>,
    val problems: List<BindingProblem>,
)

/**
 * Gives each of [mains] an episode by one rule (`sequence`, the regex, or `SxxEyy`), and pairs each file a
 * `files:` source matches in [tree] with a main file. A main no rule reads is skipped; anything ambiguous or
 * unpaired is a problem, and nothing is guessed.
 *
 * [mains] is the full list, before any command-line mask: with the longest name winning, a masked-out
 * `X 2.mkv` would let `X 2.srt` pair with `X.mkv` with the suffix " 2". [tree] leaves out `destinationDir`, and
 * [config] has passed [bindingProblems]. Every source binds against every main, whatever its group.
 * [episodes] is a season to agree with and what the main files' `sequence` counts against.
 */
fun bindFiles(config: Config, mains: List<File>, tree: List<TreeEntry>, episodes: EpisodeData? = null): BindingResult =
    Binder(config, mains, episodes).bind(tree)

private const val SEQUENCE = "sequence"

/** A release number as a rule read it, with the season when the name carries one. */
private data class Read(val season: String?, val number: Int)

/** The number rules of one config entry: its regexes, compiled once. */
private class NumberReader(matching: MatcherConfig) {
    // A blank regex is no regex, as bindingProblems reads it.
    private val episodeRegex = matching.episodeRegex?.takeUnless { it.isBlank() }?.let(::Regex)
    private val seasonRegex = matching.seasonRegex?.takeUnless { it.isBlank() }?.let(::Regex)

    val hasRegex: Boolean get() = episodeRegex != null

    /** The regex's number from [base], or null when it misses; with a [seasonRegex], both must match. */
    fun regex(base: String): Read? {
        val number = episodeRegex?.let { capturedIn(base, it) } ?: return null
        if (seasonRegex == null) return Read(null, number)
        val season = capturedIn(base, seasonRegex) ?: return null
        return Read(pad(season), number)
    }

    fun sxxeyy(base: String): Read? = parseSeasonEpisode(base)?.let { Read(it.season, it.episode.toInt()) }

    /** Group 1 of [regex]'s first match in [base], or null when it is no number. */
    private fun capturedIn(base: String, regex: Regex): Int? =
        regex.find(base)?.groupValues?.get(1)?.takeIf { ALL_DIGITS.matches(it) }?.toIntOrNull()
}

private class Binder(private val config: Config, private val mains: List<File>, private val episodes: EpisodeData?) {
    private val problems = ArrayList<BindingProblem>()

    /** Every season a paired file read, with the name it was read from. */
    private val seasons = ArrayList<Pair<String, String>>()
    private val ranges = config.general.matching.episodeMap?.entries.orEmpty().mapNotNull { entry ->
        entry.fromRange?.let { from -> entry.toRange?.let { from to it } }
    }
    private val byName = byLongestBase(mains)
    private val skipped = HashSet<File>()

    /** Null when the main files' `sequence` was refused: that one problem says it all, and nothing pairs. */
    private val bound: List<MainEpisode>? = bindMains()
    private val claims = bound.orEmpty().groupBy { it.episode }
    private val byEpisode: Map<Int, File> = claims.filterValues { it.size == 1 }.mapValues { it.value.single().main }
    private val duplicated: Set<Int> = claims.keys - byEpisode.keys

    init {
        claims.filterValues { it.size > 1 }.forEach { (episode, claim) ->
            problems += DuplicateEpisode(episode, claim.map { it.main })
        }
    }

    fun bind(tree: List<TreeEntry>): BindingResult {
        val mainEpisodes = bound ?: return BindingResult(null, emptyList(), emptyList(), emptyList(), problems)
        val mainPaths = mains.mapTo(HashSet()) { it.absolutePath }
        val sources = config.groups.flatMapIndexed { g, group ->
            group.additionalSources.mapIndexedNotNull { i, source ->
                source.files?.let { masks ->
                    val candidates = candidatesOf(masks, tree, mainPaths)
                    bindSource(config.sourcePath(g, i), source.matching, candidates, mainEpisodes.size)
                }
            }
        }
        sharedFiles(sources)
        val season = seasonOf(mainEpisodes.isNotEmpty())
        return BindingResult(season, mainEpisodes, mains.filter { it in skipped }, sources, problems)
    }

    private fun bindMains(): List<MainEpisode>? {
        if (mains.isEmpty()) return emptyList()
        val matching = config.general.matching
        if (matching.pin == SEQUENCE) {
            val expected = episodes?.byEpisode?.size?.takeIf { it > 0 }
            if (expected != mains.size) {
                problems += SequenceCount("general", mains.size, expected)
                return null
            }
            return mains.sortedBy { it.name }.mapIndexed { i, main ->
                MainEpisode(main, i + 1, mapped(i + 1), BindingRule.SEQUENCE)
            }
        }
        val reader = NumberReader(matching)
        val rule = if (reader.hasRegex) BindingRule.REGEX else BindingRule.SXXEYY
        return mains.mapNotNull { main ->
            val base = baseNameOf(main.name)
            val read = if (reader.hasRegex) reader.regex(base) else reader.sxxeyy(base)
            if (read == null) skipped += main
            read?.let {
                it.season?.let { season -> seasons += season to main.name }
                MainEpisode(main, it.number, mapped(it.number), rule)
            }
        }
    }

    private fun bindSource(
        path: String,
        matching: MatcherConfig,
        candidates: List<Pair<TreeEntry, TreeEntry?>>,
        boundMains: Int,
    ): SourceBinding {
        val reader = NumberReader(matching)
        val sequenced = matching.pin == SEQUENCE
        if (sequenced && candidates.size != boundMains) {
            problems += SequenceCount(path, candidates.size, boundMains)
            return SourceBinding(path, emptyList())
        }
        val ordinals = if (sequenced) {
            candidates.map { it.first }.sortedBy { it.relPath }.withIndex().associate { it.value to it.index + 1 }
        } else {
            emptyMap()
        }

        val bound = candidates.mapNotNull { (entry, sub) ->
            when {
                sequenced -> byNumber(path, entry, sub, Read(null, ordinals.getValue(entry)), BindingRule.SEQUENCE)
                reader.hasRegex -> byNumber(path, entry, sub, reader.regex(entry.base), BindingRule.REGEX)
                else -> {
                    // A name match, a tie or a skipped main settles the file; only no name at all reads a number.
                    val named = byName.longestPrefix(entry.base)
                    if (named.isNotEmpty()) byName(path, entry, sub, named)
                    else byNumber(path, entry, sub, reader.sxxeyy(entry.base), BindingRule.SXXEYY)
                }
            }
        }
        bound.groupBy { it.main }.filterValues { it.size > 1 }.forEach { (main, shared) ->
            problems += SharedMain(path, main, shared.map { it.entry })
        }
        return SourceBinding(path, bound)
    }

    /** [entry] paired with the one of [named] its name starts with; a tie or a skipped main is a problem. */
    private fun byName(path: String, entry: TreeEntry, sub: TreeEntry?, named: List<MainFile>): BoundFile? {
        val main = named.singleOrNull()
        when {
            main == null -> problems += NameTie(path, entry, named.map { it.file })
            main.file in skipped -> problems += SkippedMainFile(path, entry, main.file)
            else -> {
                val rule = if (main.lower == entry.base.lowercase()) BindingRule.EXACT else BindingRule.SUFFIX
                return BoundFile(entry, main.file, rule, sub)
            }
        }
        return null
    }

    /** [entry] paired with the main holding [read]'s mapped episode; no number, or no such main, is a problem. */
    private fun byNumber(path: String, entry: TreeEntry, sub: TreeEntry?, read: Read?, rule: BindingRule): BoundFile? {
        val episode = read?.let { mapped(it.number) }
        // A duplicated episode has failed the batch already; its companions add nothing to that.
        if (episode != null && episode in duplicated) return null
        val main = episode?.let { byEpisode[it] }
        if (read == null || main == null) {
            problems += UnpairedFile(path, entry)
            return null
        }
        read.season?.let { seasons += it to entry.relPath }
        return BoundFile(entry, main, rule, sub)
    }

    /** [number] through the episode map: the first range holding it, else itself. */
    private fun mapped(number: Int): Int {
        val (from, to) = ranges.firstOrNull { number in it.first } ?: return number
        return to.first + number - from.first
    }

    private fun sharedFiles(sources: List<SourceBinding>) {
        sources.flatMap { source -> source.bound.flatMap { bound -> bound.files.map { it to source.path } } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (entry, paths) -> problems += SharedFile(entry, paths) }
    }

    /** The one season the paired files and the metadata agree on; none, when a main bound, is a problem. */
    private fun seasonOf(anyBound: Boolean): String? {
        val bySeason = seasons.groupBy({ it.first }, { it.second })
        val metadata = episodes?.season
        val distinct = bySeason.keys + listOfNotNull(metadata)
        when {
            distinct.size == 1 -> return distinct.single()
            distinct.isEmpty() -> if (anyBound) problems += SeasonUnknown
            else -> problems += SeasonConflict(bySeason, metadata)
        }
        return null
    }
}

/** What [masks] match in [tree], mains aside, each with the `.sub` of its VobSub pair when it is an `.idx`. */
private fun candidatesOf(
    masks: List<String>,
    tree: List<TreeEntry>,
    mainPaths: Set<String>,
): List<Pair<TreeEntry, TreeEntry?>> {
    val regexes = masks.map(::maskRegex)
    val matched = tree.filter { entry ->
        entry.file.absolutePath !in mainPaths && regexes.any { it.matches(entry.relPath) }
    }
    // mkvmerge reads a VobSub pair through its .idx, so the .sub beside it is the same track, not a rival.
    val pairKey = { entry: TreeEntry -> entry.dirRel to entry.base.lowercase(Locale.ROOT) }
    val subs = matched.filter { it.ext == "sub" }.associateBy(pairKey)
    val pairOf = matched.filter { it.ext == "idx" }.associateWith { subs[pairKey(it)] }
    val paired = pairOf.values.filterNotNull().toSet()
    return matched.filterNot { it in paired }.map { it to pairOf[it] }
}
