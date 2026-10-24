package org.plukh.mkvtool.core

import java.io.File
import java.util.Locale

/** Which rule bound a file, in cascade order. */
enum class BindingRule { EXACT, SUFFIX, SXXEYY }

/** Why a file bound to nothing. */
enum class UnboundReason {
    /** No rule it runs matched. */
    NO_MATCH,

    /** A name rule found several mains with one base name. */
    NAME_AMBIGUOUS,

    /** Its episode belongs to several mains, so none of them has it. */
    EPISODE_AMBIGUOUS,
}

/** A main file's episode and the rule that read it. */
data class MainEpisode(val main: File, val episode: SeasonEpisode, val rule: BindingRule)

/**
 * The main files by episode. [contested] holds mains that read an episode another main read too, so none of
 * them has it; [unbound] holds those no rule read one from. Every main stays a main, and receives the files
 * bound to it by name.
 */
data class MainBindings(
    val bound: List<MainEpisode>,
    val contested: List<MainEpisode>,
    val unbound: List<File>,
)

/** A source file bound to [main] by [rule]. [sub] is the `.sub` half of a VobSub pair whose `.idx` is [entry]. */
data class BoundFile(val entry: TreeEntry, val main: File, val rule: BindingRule, val sub: TreeEntry? = null) {
    /** [entry], and [sub] when there is one. */
    val files: List<TreeEntry> get() = listOfNotNull(entry, sub)
}

/** A source file bound to nothing, with the mains behind an ambiguous [reason] as [claimants]. */
data class UnboundFile(
    val entry: TreeEntry,
    val reason: UnboundReason,
    val claimants: List<File> = emptyList(),
    val sub: TreeEntry? = null,
)

/**
 * One `files:` source's binding, by its config [path]. [contested] holds the files that bound to a main another
 * file of this source bound to as well, and those another source bound too: none of them binds, and each keeps
 * the rule that matched it.
 */
data class SourceBinding(
    val path: String,
    val bound: List<BoundFile>,
    val contested: List<BoundFile>,
    val unbound: List<UnboundFile>,
)

/** Every main file's episode, and every `files:` source's files bound to main files. */
data class BindingResult(val mains: MainBindings, val sources: List<SourceBinding>)

/**
 * Binds each of [mains] to an episode, and each file a `files:` source matches in [tree] to a main, by the
 * cascade or the source's pinned rule. Ambiguity binds nothing; nothing is guessed.
 *
 * [mains] is the full list, before any command-line mask: with the longest name winning, a masked-out
 * `X 2.mkv` would let `X 2.srt` bind to `X.mkv` with the suffix " 2". [tree] leaves out `destinationDir`, and
 * [config] has passed [bindingProblems]. Every source binds against every main, whatever its group.
 *
 * The regex tier, `sequence` and the episode map are not run yet: [config]'s regexes and episode maps are not
 * read, and a `regex` or `sequence` pin throws [IllegalStateException].
 */
fun bindFiles(config: Config, mains: List<File>, tree: List<TreeEntry>): BindingResult {
    val mainBindings = bindMains(mains, rulesFor(config.general.matching, MAIN_CASCADE))
    val byEpisode = (mainBindings.bound + mainBindings.contested).groupBy({ it.episode }, { it.main })
    val byName = byLongestBase(mains)
    val mainPaths = mains.mapTo(HashSet()) { it.absolutePath }

    val bySource = config.groups.flatMapIndexed { g, group ->
        group.additionalSources.mapIndexedNotNull { i, source ->
            source.files?.let { masks ->
                bindSource(
                    config.sourcePath(g, i),
                    candidatesOf(masks, tree, mainPaths),
                    rulesFor(source.matching, SOURCE_CASCADE),
                    byName,
                    byEpisode,
                )
            }
        }
    }
    return BindingResult(mainBindings, contestAcross(bySource))
}

private val SOURCE_CASCADE = listOf(BindingRule.EXACT, BindingRule.SUFFIX, BindingRule.SXXEYY)
private val MAIN_CASCADE = listOf(BindingRule.SXXEYY)
private val NAME_RULES = setOf(BindingRule.EXACT, BindingRule.SUFFIX)

/** The cascade, or the one rule [matching] pins. */
private fun rulesFor(matching: MatcherConfig, cascade: List<BindingRule>): List<BindingRule> {
    val pin = matching.pin ?: return cascade
    // bindingProblems refuses any other name; regex and sequence are not run yet.
    val rule = BindingRule.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == pin }
    return listOf(rule ?: error("no binding rule for pin '$pin'"))
}

private fun bindMains(mains: List<File>, rules: List<BindingRule>): MainBindings {
    val read = ArrayList<MainEpisode>()
    val unbound = ArrayList<File>()
    for (main in mains) {
        val base = baseNameOf(main.name)
        val hit = rules.firstNotNullOfOrNull { rule -> episodeOf(base, rule)?.let { MainEpisode(main, it, rule) } }
        if (hit == null) unbound += main else read += hit
    }
    val (bound, contested) = read.groupBy { it.episode }.values.partition { it.size == 1 }
    return MainBindings(bound.flatten(), contested.flatten(), unbound)
}

/** The episode a number rule reads from [base]; a name rule reads none. */
private fun episodeOf(base: String, rule: BindingRule): SeasonEpisode? =
    if (rule == BindingRule.SXXEYY) parseSeasonEpisode(base) else null

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

private fun bindSource(
    path: String,
    candidates: List<Pair<TreeEntry, TreeEntry?>>,
    rules: List<BindingRule>,
    byName: List<MainFile>,
    byEpisode: Map<SeasonEpisode, List<File>>,
): SourceBinding {
    val hits = ArrayList<BoundFile>()
    val unbound = ArrayList<UnboundFile>()
    candidates@ for ((entry, sub) in candidates) {
        for (rule in rules) {
            val claimants = claimantsOf(entry, rule, byName, byEpisode)
            when {
                claimants.isEmpty() -> continue
                claimants.size == 1 -> hits += BoundFile(entry, claimants.single(), rule, sub)
                else -> {
                    val reason =
                        if (rule in NAME_RULES) UnboundReason.NAME_AMBIGUOUS else UnboundReason.EPISODE_AMBIGUOUS
                    unbound += UnboundFile(entry, reason, claimants, sub)
                }
            }
            // A tie stops the cascade as surely as a match: a later rule must not guess past it.
            continue@candidates
        }
        unbound += UnboundFile(entry, UnboundReason.NO_MATCH, sub = sub)
    }
    val (single, shared) = hits.groupBy { it.main }.values.partition { it.size == 1 }
    return SourceBinding(path, single.flatten(), shared.flatten(), unbound)
}

/** [sources] with a file several of them bound moved to each one's contested list: muxed twice, it is a guess. */
private fun contestAcross(sources: List<SourceBinding>): List<SourceBinding> {
    val boundBy = sources.flatMap { source -> source.bound.flatMap { it.files } }.groupingBy { it }.eachCount()
    return sources.map { source ->
        val (shared, own) = source.bound.partition { bound -> bound.files.any { boundBy.getValue(it) > 1 } }
        source.copy(bound = own, contested = source.contested + shared)
    }
}

/** The mains [rule] binds [entry] to: one is a match, several a tie. */
private fun claimantsOf(
    entry: TreeEntry,
    rule: BindingRule,
    byName: List<MainFile>,
    byEpisode: Map<SeasonEpisode, List<File>>,
): List<File> = when (rule) {
    BindingRule.EXACT -> byName.longestPrefix(entry.base).filter { it.lower == entry.base.lowercase() }.map { it.file }
    // After EXACT in the cascade this sees only suffixed names; pinned, it binds exact ones as well.
    BindingRule.SUFFIX -> byName.longestPrefix(entry.base).map { it.file }
    BindingRule.SXXEYY -> episodeOf(entry.base, rule)?.let { byEpisode[it] }.orEmpty()
}
