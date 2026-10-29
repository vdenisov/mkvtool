package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import java.io.File
import java.util.Locale

/** Which rule read the main files' episodes: the config's `episodeRegex`, or `SxxEyy`. */
enum class BindingRule { REGEX, SXXEYY }

/**
 * A main file's [episode], mapped from the number the run's rule [read], as the name spells it; both null when it
 * read none. [season] is the one its name gave, if any.
 */
data class MainEpisode(val main: File, val read: String?, val episode: Int?, val season: String? = null)

/**
 * The file a source's template resolved to for [main]: [path] as the command line names it, and the [partner] that
 * travels with it when it is half of a pair.
 */
data class CompanionFile(val main: File, val path: String, val file: File, val partner: File? = null)

/**
 * One source's resolved files, by its config [path]: [companions] for the mains it found exactly one file for, and
 * [ambiguous] the paths it matched for the mains it found several for. A main in neither has no file of this source.
 */
data class SourceBinding(
    val path: String,
    val companions: List<CompanionFile>,
    val ambiguous: Map<File, List<String>> = emptyMap(),
)

/** Why a batch cannot be bound. Any one of them fails the whole batch; the user fixes the files or the config. */
sealed interface BindingProblem

/** Neither the files nor the metadata name a season. */
data object SeasonUnknown : BindingProblem

/** More than one season among the files, which read [bySeason] (season to the names read), and the [metadata]. */
data class SeasonConflict(val bySeason: Map<String, List<String>>, val metadata: String?) : BindingProblem

/** Several [mains] on one [episode]. */
data class DuplicateEpisode(val episode: Int, val mains: List<File>) : BindingProblem

/** The template of source [path] matching several files, [matches], for [main]. */
data class AmbiguousCompanion(val path: String, val main: File, val matches: List<String>) : BindingProblem

/** One file, [path], picked for one [main] by several sources, at [sources]. */
data class RepeatedCompanion(val path: String, val main: File, val sources: List<String>) : BindingProblem

/**
 * The batch's one [season], the [rule] its main files were read by, every main file with the episode it read if
 * any, each source's files, and the mains split by the group claiming each. Nothing may act on it while
 * [problems] is not empty.
 */
data class BindingResult(
    val season: String?,
    val rule: BindingRule,
    val mains: List<MainEpisode>,
    val sources: List<SourceBinding>,
    val problems: List<BindingProblem>,
    /** The implicit group's by default, which claims every main. */
    val partition: GroupPartition = GroupPartition(listOf(mains.map { it.main }), emptyList()),
    /** The declared groups, by index; empty for the implicit group. */
    val groups: List<GroupRef> = emptyList(),
) {
    /** The mains that read an episode. */
    val episodes: List<MainEpisode> get() = mains.filter { it.episode != null }

    private val byPathAndMain: Map<Pair<String, File>, CompanionFile> by lazy {
        sources.flatMap { source -> source.companions.map { (source.path to it.main) to it } }.toMap()
    }

    /** The file the source at config [path] resolved to for [main], or null when it resolved to none. */
    fun companion(path: String, main: File): CompanionFile? = byPathAndMain[path to main]

    /** This binding as far as a run over [files] reaches; the problems stay whole, since they fail the batch. */
    fun narrowedTo(files: Set<File>): BindingResult = copy(
        mains = mains.filter { it.main in files },
        sources = sources.map { source ->
            source.copy(
                companions = source.companions.filter { it.main in files },
                ambiguous = source.ambiguous.filterKeys { it in files },
            )
        },
        partition = GroupPartition(
            members = partition.members.map { members -> members.filter { it in files } },
            unclaimed = partition.unclaimed.filter { it in files },
            contested = partition.contested.filter { it in files },
        ),
    )
}

/**
 * What a command says about its batch's binding. [fatal] is the command's verdict on the problems: `mux`
 * refuses a batch with any, `inspect` reports them and carries on.
 */
data class BindingReport(val binding: BindingResult, val fatal: Boolean) : CommandResult

/**
 * Gives each of [mains] an episode by one rule (the regex, or `SxxEyy`), splits them by group, and resolves each
 * group's source templates for its mains. A main no rule reads is a main with no episode; anything ambiguous is a
 * problem, and nothing is guessed.
 *
 * [mains] is the full list, before any command-line mask, and [run] the mains the masks selected: the season and
 * duplicate-episode checks and the templates are about the run, so a sample it leaves out cannot clash with its
 * episode. [dir] is the media directory a literal template resolves against, [tree] what a wildcard is matched
 * against, leaving out `destinationDir`. [config] has passed [bindingProblems]. [episodes] is a season to agree
 * with and the variables a template may use.
 */
fun bindFiles(
    dir: File,
    config: Config,
    mains: List<File>,
    tree: List<TreeEntry>,
    episodes: EpisodeData? = null,
    run: Collection<File> = mains,
): BindingResult = Binder(dir, config, mains, run.toSet(), episodes).bind(tree)

/** A release number as a rule read it, as the name spells it, with the season when the name carries one. */
private data class Read(val season: String?, val spelled: String) {
    val number: Int get() = spelled.toInt()
}

/** The number rules of `general`: its regexes, compiled once. */
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
        return Read(pad(season.toInt()), number)
    }

    fun sxxeyy(base: String): Read? = readSeasonEpisode(base)?.let { (parsed, spelled) -> Read(parsed.season, spelled) }

    /** Group 1 of [regex]'s first match in [base], or null when it is no number. */
    private fun capturedIn(base: String, regex: Regex): String? =
        regex.find(base)?.groupValues?.get(1)?.takeIf { ALL_DIGITS.matches(it) && it.toIntOrNull() != null }
}

private class Binder(
    private val dir: File,
    private val config: Config,
    private val mains: List<File>,
    private val run: Set<File>,
    private val episodes: EpisodeData?,
) {
    private val problems = ArrayList<BindingProblem>()

    /** Every season a main in the run read, with the name it was read from. */
    private val seasons = ArrayList<Pair<String, String>>()
    private val ranges = config.general.matching.episodeMap?.entries.orEmpty().mapNotNull { entry ->
        entry.fromRange?.let { from -> entry.toRange?.let { from to it } }
    }
    private val reader = NumberReader(config.general.matching)
    private val rule = if (reader.hasRegex) BindingRule.REGEX else BindingRule.SXXEYY

    fun bind(tree: List<TreeEntry>): BindingResult {
        val bound = mains.map { main ->
            val base = baseNameOf(main.name)
            val read = if (rule == BindingRule.REGEX) reader.regex(base) else reader.sxxeyy(base)
            if (main in run) read?.season?.let { seasons += it to main.name }
            MainEpisode(main, read?.spelled, read?.let { mapped(it.number) }, read?.season)
        }
        val episodeOf = bound.associate { it.main to it.episode }
        val partition = partitionByGroup(config.groups, mains) { episodeOf[it] }
        val groups = config.groups.indices.mapNotNull { config.groupRef(it) }

        // Only a main that read an episode can clash over one.
        bound.filter { it.episode != null && it.main in run }.groupBy { it.episode!! }.forEach { (episode, clashing) ->
            if (clashing.size > 1) problems += DuplicateEpisode(episode, clashing.map { it.main })
        }
        val season = seasonOf(bound.any { it.episode != null && it.main in run })

        val engine = SubstitutionEngine(episodes).boundTo(season, bound)
        val sources = resolveSources(engine, partition, tree)
        return BindingResult(season, rule, bound, sources, problems, partition, groups)
    }

    /**
     * Each group's source templates resolved for the group's mains in the run. A template naming a variable nothing
     * knows resolves nothing, validation having reported it; one a main has no value for resolves nothing for that
     * main. A main missing a variable its group's templates use is stage two's to drop, so it raises no problem.
     */
    private fun resolveSources(
        engine: SubstitutionEngine,
        partition: GroupPartition,
        tree: List<TreeEntry>,
    ): List<SourceBinding> {
        // A wildcard can match a main file as easily as a companion; a main is never another main's source.
        val mainPaths = mains.mapTo(HashSet()) { fileKey(it) }
        val candidates = tree.filter { fileKey(it.file) !in mainPaths }
        // mkvmerge reads a pair through its primary half, so the partner beside it is the same track, not a rival.
        val pairs = withPartners(candidates) { it }.filter { it.second != null }
        val partnerOf = pairs.associate { (entry, partner) -> fileKey(entry.file) to partner!!.file }
        val primaryOf = pairs.associate { (entry, partner) -> fileKey(partner!!.file) to entry }
        // Each kept main's files, by file, with the sources that picked each.
        val picks = LinkedHashMap<Pair<File, String>, MutableList<Pair<String, CompanionFile>>>()

        val sources = config.groups.flatMapIndexed { g, group ->
            val used = validateTemplates(collectTemplateFields(config, g)).usedFileVars
            group.additionalSources.mapIndexedNotNull { i, source ->
                val template = source.file?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val path = config.sourcePath(g, i)
                // An unknown name would expand to nothing, and `*${'$'}{typo}*` would then cross folders as `**`.
                val names = variablesIn(template)
                if (names.any { it !in FILE_VARS }) return@mapIndexedNotNull SourceBinding(path, emptyList())
                val wildcard = hasWildcard(template)
                val ambiguous = LinkedHashMap<File, List<String>>()
                val companions = partition.members[g].filter { it in run }.mapNotNull { main ->
                    val vars = engine.fileVarsFor(main)
                    if (names.any { it in vars.missing }) return@mapNotNull null
                    val matches = resolve(template, wildcard, vars.vars, candidates, main)
                        .map { asPrimary(it, primaryOf, partnerOf) }
                        .distinctBy { fileKey(it.file) }
                    if (matches.size > 1) ambiguous[main] = matches.map { it.path }
                    if (vars.missing.none { it in used }) {
                        if (matches.size > 1) problems += AmbiguousCompanion(path, main, matches.map { it.path })
                        matches.singleOrNull()?.let {
                            picks.getOrPut(main to fileKey(it.file)) { ArrayList() } += path to it
                        }
                    }
                    matches.singleOrNull()
                }
                SourceBinding(path, companions, ambiguous)
            }
        }

        // Two sources handing one main one file would mux it twice.
        picks.forEach { (key, picked) ->
            if (picked.size > 1) {
                problems += RepeatedCompanion(picked.first().second.path, key.first, picked.map { it.first })
            }
        }
        return sources
    }

    /** What [template] names for [main]: the one file a literal names, or every file a wildcard matches. */
    private fun resolve(
        template: String,
        wildcard: Boolean,
        vars: Map<String, String?>,
        candidates: List<TreeEntry>,
        main: File,
    ): List<CompanionFile> {
        val path = substitute(template, vars)
        val file = resolveAgainst(dir, path)
        // A file whose own name holds a `*` or `?` (legal off Windows) is named literally, as a mask names it.
        if (!wildcard || file.isFile) return if (file.isFile) listOf(CompanionFile(main, path, file)) else emptyList()
        val glob = templateGlob(template, vars)
        return candidates.filter { glob.matches(it.relPath) }.map { CompanionFile(main, it.relPath, it.file) }
    }

    /**
     * [companion] as the half of a pair mkvmerge reads, with its partner: a template naming either half, or both,
     * names one track.
     */
    private fun asPrimary(
        companion: CompanionFile,
        primaryOf: Map<String, TreeEntry>,
        partnerOf: Map<String, File>,
    ): CompanionFile {
        val primary = primaryOf[fileKey(companion.file)]
            ?.let { companion.copy(path = it.relPath, file = it.file) } ?: companion
        return primary.copy(partner = partnerOf[fileKey(primary.file)])
    }

    /** [number] through the episode map: the first range holding it, else itself. */
    private fun mapped(number: Int): Int {
        val (from, to) = ranges.firstOrNull { number in it.first } ?: return number
        return to.first + number - from.first
    }

    /** The one season the run's mains and the metadata agree on; none, when a main read an episode, is a problem. */
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

/**
 * Whether [template] needs the walked tree: for a wildcard's matches, or for the partner of a paired format's half.
 * Every other template names its one file on its own.
 */
fun readsTree(template: String): Boolean {
    val extension = template.substringAfterLast('.').lowercase(Locale.ROOT)
    return hasWildcard(template) || extension in PAIRED_EXTENSIONS || extension in PAIRED_EXTENSIONS.values
}

/**
 * A file's identity across the ways one directory listing and one template spell it: the normalized absolute path,
 * case folded where the file system ignores case. No file system call, which on a network share is the cost.
 */
private fun fileKey(file: File): String =
    file.absoluteFile.normalize().path.let { if (File.separatorChar == '\\') it.lowercase(Locale.ROOT) else it }
