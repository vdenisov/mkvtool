package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import java.io.File

/** Which rule paired a file: a name rule, or the number rule that read its episode. */
enum class BindingRule { EXACT, SUFFIX, REGEX, SXXEYY, SEQUENCE }

/**
 * A main file's [episode], mapped from the release number the run's rule [read]; both null when it read none.
 * [season] is the one its name gave, if any.
 */
data class MainEpisode(val main: File, val read: Int?, val episode: Int?, val season: String? = null)

/** A source file paired with [main] by [rule], and the [partner] that travels with it when it is half of a pair. */
data class BoundFile(val entry: TreeEntry, val main: File, val rule: BindingRule, val partner: TreeEntry? = null) {
    /** [entry], and [partner] when there is one. */
    val files: List<TreeEntry> get() = listOfNotNull(entry, partner)
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

/** A file named after several [mains] at once, which share a base name. */
data class NameTie(val path: String, val entry: TreeEntry, val mains: List<File>) : BindingProblem

/** Several [entries] of one source paired with one [main]. */
data class SharedMain(val path: String, val main: File, val entries: List<TreeEntry>) : BindingProblem

/** One file paired by several sources, at [paths]. */
data class SharedFile(val entry: TreeEntry, val paths: List<String>) : BindingProblem

/**
 * The batch's one [season], the [rule] its main files were read by, every main file with the episode it read if
 * any, and each `files:` source's pairings. Nothing may act on it while [problems] is not empty.
 */
data class BindingResult(
    val season: String?,
    val rule: BindingRule,
    val mains: List<MainEpisode>,
    val sources: List<SourceBinding>,
    val problems: List<BindingProblem>,
) {
    /** The mains that read an episode. */
    val episodes: List<MainEpisode> get() = mains.filter { it.episode != null }

    private val byPathAndMain: Map<Pair<String, File>, BoundFile> by lazy {
        sources.flatMap { source -> source.bound.map { (source.path to it.main) to it } }.toMap()
    }

    /** The file the `files:` source at config [path] paired with [main], or null when it paired none. */
    fun boundFile(path: String, main: File): BoundFile? = byPathAndMain[path to main]

    /** This binding as far as a run over [files] reaches; the problems stay whole, since they fail the batch. */
    fun narrowedTo(files: Set<File>): BindingResult = copy(
        mains = mains.filter { it.main in files },
        sources = sources.map { source -> source.copy(bound = source.bound.filter { it.main in files }) },
    )
}

/**
 * What a command says about its batch's binding. [fatal] is the command's verdict on the problems: `mux`
 * refuses a batch with any, `inspect` reports them and carries on.
 */
data class BindingReport(val binding: BindingResult, val fatal: Boolean) : CommandResult

/**
 * Gives each of [mains] an episode by one rule (`sequence`, the regex, or `SxxEyy`), and pairs each file a
 * `files:` source matches in [tree] with a main file. A main no rule reads is a main with no episode, which
 * only a name can pair with; anything ambiguous or unpaired is a problem, and nothing is guessed.
 *
 * [mains] is the full list, before any command-line mask: with the longest name winning, a masked-out
 * `X 2.mkv` would let `X 2.srt` pair with `X.mkv` with the suffix " 2". [tree] leaves out `destinationDir`, and
 * [config] has passed [bindingProblems]. Every source binds against every main, whatever its group.
 * [episodes] is a season to agree with and what the main files' `sequence` counts against. [run] is the mains
 * the masks selected: the season and duplicate-episode checks, and pairing by number, are about those, so a
 * sample the run leaves out cannot clash with its episode; a `sequence` still numbers every main.
 */
fun bindFiles(
    config: Config,
    mains: List<File>,
    tree: List<TreeEntry>,
    episodes: EpisodeData? = null,
    run: Collection<File> = mains,
): BindingResult = Binder(config, mains, run.toSet(), episodes).bind(tree)

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

private class Binder(
    private val config: Config,
    private val mains: List<File>,
    private val run: Set<File>,
    private val episodes: EpisodeData?,
) {
    private val problems = ArrayList<BindingProblem>()

    /** Every season a paired file read, with the name it was read from. */
    private val seasons = ArrayList<Pair<String, String>>()
    private val ranges = config.general.matching.episodeMap?.entries.orEmpty().mapNotNull { entry ->
        entry.fromRange?.let { from -> entry.toRange?.let { from to it } }
    }
    private val byName = byLongestBase(mains)
    private val reader = NumberReader(config.general.matching)
    private val rule = when {
        config.general.matching.pin == SEQUENCE -> BindingRule.SEQUENCE
        reader.hasRegex -> BindingRule.REGEX
        else -> BindingRule.SXXEYY
    }

    fun bind(tree: List<TreeEntry>): BindingResult {
        // A refused `sequence` is one problem that says it all: every main stays unread, and nothing pairs.
        val bound = bindMains()
            ?: return BindingResult(null, rule, mains.map { MainEpisode(it, null, null) }, emptyList(), problems)
        // Episode to the mains claiming it; only a main that read one can claim, or clash over, an episode.
        val claims = bound.mapNotNull { main -> main.episode?.let { it to main.main } }
            .groupBy({ it.first }, { it.second })
        claims.forEach { (episode, claimed) ->
            val inRun = claimed.filter { it in run }
            if (inRun.size > 1) problems += DuplicateEpisode(episode, inRun)
        }

        val mainPaths = mains.mapTo(HashSet()) { it.absolutePath }
        val sources = config.groups.flatMapIndexed { g, group ->
            group.additionalSources.mapIndexedNotNull { i, source ->
                source.files?.let { masks ->
                    val candidates = candidatesOf(masks, tree, mainPaths)
                    bindSource(config.sourcePath(g, i), source.matching, candidates, claims)
                }
            }
        }
        sharedFiles(sources)
        val season = seasonOf(bound.any { it.episode != null && it.main in run })
        return BindingResult(season, rule, bound, sources, problems)
    }

    /** Every main with the episode the run's rule read, or null when the mains' `sequence` was refused. */
    private fun bindMains(): List<MainEpisode>? {
        if (rule == BindingRule.SEQUENCE) {
            if (mains.isEmpty()) return emptyList()
            val expected = episodes?.byEpisode?.size?.takeIf { it > 0 }
            if (expected != mains.size) {
                problems += SequenceCount("general", mains.size, expected)
                return null
            }
            return mains.sortedBy { it.name }.mapIndexed { i, main -> MainEpisode(main, i + 1, mapped(i + 1)) }
        }
        return mains.map { main ->
            val base = baseNameOf(main.name)
            val read = if (rule == BindingRule.REGEX) reader.regex(base) else reader.sxxeyy(base)
            if (main in run) read?.season?.let { seasons += it to main.name }
            MainEpisode(main, read?.number, read?.let { mapped(it.number) }, read?.season)
        }
    }

    private fun bindSource(
        path: String,
        matching: MatcherConfig,
        candidates: List<Pair<TreeEntry, TreeEntry?>>,
        claims: Map<Int, List<File>>,
    ): SourceBinding {
        val reader = NumberReader(matching)
        val sequenced = matching.pin == SEQUENCE
        // Episodes, not mains: a masked-out sample shares its episode, and needs no file of its own.
        if (sequenced && candidates.size != claims.size) {
            problems += SequenceCount(path, candidates.size, claims.size)
            return SourceBinding(path, emptyList())
        }
        val ordinals = if (sequenced) {
            candidates.map { it.first }.sortedBy { it.relPath }.withIndex().associate { it.value to it.index + 1 }
        } else {
            emptyMap()
        }

        val bound = candidates.mapNotNull { (entry, partner) ->
            when {
                sequenced ->
                    byNumber(path, entry, partner, Read(null, ordinals.getValue(entry)), BindingRule.SEQUENCE, claims)
                reader.hasRegex -> byNumber(path, entry, partner, reader.regex(entry.base), BindingRule.REGEX, claims)
                else -> {
                    // A name match or a tie settles the file; only no name at all reads a number.
                    val named = byName.longestPrefix(entry.base)
                    if (named.isNotEmpty()) byName(path, entry, partner, named)
                    else byNumber(path, entry, partner, reader.sxxeyy(entry.base), BindingRule.SXXEYY, claims)
                }
            }
        }
        bound.groupBy { it.main }.filterValues { it.size > 1 }.forEach { (main, shared) ->
            problems += SharedMain(path, main, shared.map { it.entry })
        }
        return SourceBinding(path, bound)
    }

    /** [entry] paired with the one of [named] its name starts with, episode or not; a tie is a problem. */
    private fun byName(path: String, entry: TreeEntry, partner: TreeEntry?, named: List<MainFile>): BoundFile? {
        val main = named.singleOrNull()
        if (main == null) {
            problems += NameTie(path, entry, named.map { it.file })
            return null
        }
        val rule = if (main.lower == entry.base.lowercase()) BindingRule.EXACT else BindingRule.SUFFIX
        return BoundFile(entry, main.file, rule, partner)
    }

    /** [entry] paired with the main that claims [read]'s mapped episode; no number, or no main, is a problem. */
    private fun byNumber(
        path: String,
        entry: TreeEntry,
        partner: TreeEntry?,
        read: Read?,
        rule: BindingRule,
        claims: Map<Int, List<File>>,
    ): BoundFile? {
        val claimed = read?.let { claims[mapped(it.number)] }.orEmpty()
        val main = claimed.singleOrNull() ?: claimed.filter { it in run }.singleOrNull()
        // A clash among the run's mains has failed the batch already, and one outside the run is not its concern.
        if (main == null && claimed.size > 1) return null
        if (read == null || main == null) {
            problems += UnpairedFile(path, entry)
            return null
        }
        if (main in run) read.season?.let { seasons += it to entry.relPath }
        return BoundFile(entry, main, rule, partner)
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

/** What [masks] match in [tree], mains aside, each with its partner when it is the primary half of a pair. */
private fun candidatesOf(
    masks: List<String>,
    tree: List<TreeEntry>,
    mainPaths: Set<String>,
): List<Pair<TreeEntry, TreeEntry?>> {
    val regexes = masks.map(::maskRegex)
    val matched = tree.filter { entry ->
        entry.file.absolutePath !in mainPaths && regexes.any { it.matches(entry.relPath) }
    }
    // mkvmerge reads a pair through its primary half, so the partner beside it is the same track, not a rival.
    return withPartners(matched) { it }
}
