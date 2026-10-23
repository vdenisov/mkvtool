package org.plukh.mkvtool.core

import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * `config.yaml`: the muxing decisions, typed. v1 spread the reading of it across three files, `mux`,
 * `inspect` and the check's `makeSelection`; one typed model replaces all three.
 *
 * One reader, deliberately. v1 had three sets of `config?.general?.…` chains against the raw mapping, and
 * every consumer here — the track selection, the template fields, the command line at 5.1 — reads this
 * model instead. Two independent readers of one YAML shape is exactly the drift the v1 `lib/`
 * consolidation existed to prevent.
 *
 * Reading is **lenient**: a field of the wrong shape becomes absent rather than an exception, because a
 * config is optional throughout `inspect` and a stale one must never stand between the user and the track
 * table. Whether an unusable config is fatal is the caller's policy — `mux` exits 2, `inspect` warns and
 * carries on — and the classification it decides on comes from [loadMapping], not from here.
 */

/**
 * A configured template, and the distinction the whole substitution stage rests on: a **missing** key is
 * a null [Template] reference (the field falls back to its default — the file name), while a key written
 * with no value is a `Template(null)`, a declared template that happens to be empty.
 *
 * v1 drew the same line with `containsKey` rather than truthiness, and it is load-bearing twice over: an
 * empty `title:` is validated like any other template, and it overrides the default rather than restoring
 * it.
 */
data class Template(val text: String?)

/** Batch-wide settings. Every one of them is optional. */
data class GeneralConfig(
    val destinationDir: String? = null,
    /** Null when the config names none, *including* an empty list, which v1's truthiness also ignored. */
    val allowedExtensions: Set<String>? = null,
    /** Null when absent or blank, which is the caller's cue to auto-detect from `PATH`. */
    val mkvmergeExe: String? = null,
    /** The segment (container) title — a different field from the video track's name. */
    val title: Template? = null,
    /** How the main files get their episode numbers. Here rather than per group: groups claim by number. */
    val matching: MatcherConfig = MatcherConfig(),
    /** The output's base name, templated; null keeps the main file's own. `.mkv` is appended. */
    val outputName: Template? = null,
)

/**
 * How a source's files bind to episodes, as written: [matcher] pins one rule and bypasses the automatic
 * cascade, and nothing set is the cascade itself. Values stay raw so [bindingProblems] can quote them.
 */
data class MatcherConfig(
    val matcher: String? = null,
    val episodeRegex: String? = null,
    val seasonRegex: String? = null,
    /** Null when absent, which is the identity map. */
    val episodeMap: EpisodeMap? = null,
) {
    /** [matcher] as the rule it names: pin names are trimmed and case-insensitive, so `SxxEyy` pins `sxxeyy`. */
    val pin: String? get() = matcher?.trim()?.lowercase(Locale.ROOT)
}

/** An `episodeMap`, release numbers to real ones; [notAMapping] when the key holds something else. */
data class EpisodeMap(val entries: List<EpisodeMapEntry> = emptyList(), val notAMapping: Boolean = false)

/** One `from: to` line as written. A range is null when its side is not one. */
data class EpisodeMapEntry(val from: String, val to: String) {
    // A release may number from 0 (a `00` prologue); real episodes start at 1, as group ranges do.
    val fromRange: IntRange? = episodeRangeOf(from, min = 0)
    val toRange: IntRange? = episodeRangeOf(to)
}

/** The video track, which `mux` always copies and always addresses as track 0. */
data class VideoTrackConfig(
    val language: String? = null,
    val title: Template? = null,
    /** As [TrackConfig.ref]. */
    val ref: String? = null,
)

/**
 * One configured main-source track. [id] is the number it carries in the main file.
 *
 * [id] is nullable because a config can omit it: the model reports what the file says and leaves refusing
 * it to whoever is about to act on it.
 */
data class TrackConfig(
    val id: Int? = null,
    val language: String? = null,
    val title: Template? = null,
    val default: Boolean = false,
    val charset: String? = null,
    /** The `sharedTracks` entry this one is built on, or null; set even when no definition has the name, and
     *  empty for a `use:` with no value. */
    val ref: String? = null,
)

/** The main file's tracks. An absent list and an empty one mean the same thing — copy none of that type. */
data class MainSourceConfig(
    val videoTrack: VideoTrackConfig? = null,
    val audioTracks: List<TrackConfig> = emptyList(),
    val subtitleTracks: List<TrackConfig> = emptyList(),
    val additionalOptions: List<String> = emptyList(),
)

/**
 * The track inside an additional source. It has no id: mkvmerge sees a single-track file there, always
 * track 0. [idDeclared] records that the config wrote one anyway, for [additionalSourceIdProblems].
 */
data class SourceTrackConfig(
    val language: String? = null,
    val title: Template? = null,
    val default: Boolean = false,
    val charset: String? = null,
    val idDeclared: Boolean = false,
    /** As [TrackConfig.ref]. */
    val ref: String? = null,
)

/**
 * An extra file muxed in alongside the main source. [file] is templated — `${'$'}{fileName}` and friends —
 * and names the file per episode; [files] is the other way, masks whose matches [matching] binds to
 * episodes. [files] is null when absent and empty when every mask was blank.
 */
data class AdditionalSource(
    val file: String? = null,
    val tracks: List<SourceTrackConfig> = emptyList(),
    val additionalOptions: List<String> = emptyList(),
    val files: List<String>? = null,
    val matching: MatcherConfig = MatcherConfig(),
)

/**
 * A `sharedTracks` entry as far as validation needs it. The definition itself is laid under every track
 * entry that names it at parse time, so nothing downstream ever resolves a reference.
 */
data class SharedTrack(val name: String?, val usesAnother: Boolean = false)

/**
 * Which files a group claims: a file whose episode falls in [episodes], or whose name matches a mask in
 * [files]. [malformedEpisodes] keeps the tokens that are not `N` or `N-M`, as written, for validation.
 */
data class Membership(
    val episodes: List<IntRange> = emptyList(),
    val malformedEpisodes: List<String> = emptyList(),
    val files: List<String> = emptyList(),
)

/**
 * One muxing pass: the files it claims and the full selection applied to them. A null [membership] is the
 * implicit group of a config without `groups`, which claims every file.
 */
data class GroupConfig(
    val name: String? = null,
    val membership: Membership? = null,
    val mainSource: MainSourceConfig = MainSourceConfig(),
    val additionalSources: List<AdditionalSource> = emptyList(),
    val trackOrder: String? = null,
)

/**
 * The whole config.
 *
 * [trackOrder] is left as the raw `sourceIndex:trackId` CSV: it is validated against the configured
 * tracks and warned about rather than parsed into a structure, and mkvmerge itself silently ignores ids
 * that are not there.
 *
 * [declaredGroups] is null when the config has no `groups` key. [groups] is what a group-aware reader
 * iterates: the declared groups, or one implicit group made of the top-level fields.
 */
data class Config(
    val general: GeneralConfig = GeneralConfig(),
    val mainSource: MainSourceConfig = MainSourceConfig(),
    val additionalSources: List<AdditionalSource> = emptyList(),
    val trackOrder: String? = null,
    val sharedTracks: List<SharedTrack> = emptyList(),
    val declaredGroups: List<GroupConfig>? = null,
) {
    val groups: List<GroupConfig> =
        declaredGroups ?: listOf(GroupConfig(null, null, mainSource, additionalSources, trackOrder))
}

/** Load [file] as a config, or classify why it cannot be one. Auto-detected charset: `config.yaml` is
 *  hand-written, which is the contract [loadMapping] keeps for it. */
fun loadConfig(file: File): MappingLoad<Config> = loadMapping(file, transform = ::parseConfig)

/** Turn an already-parsed mapping into the model. Never throws: anything unreadable is simply absent. */
fun parseConfig(mapping: Map<*, *>): Config {
    val general = mapping.mapAt("general")
    val sharedEntries = mapping.mapsAt("sharedTracks")
    // A duplicated name is a validation problem; the first wins, as a reader meets it.
    val shared = sharedEntries.mapNotNull { entry -> entry.nameAt()?.let { it to entry } }
        .distinctBy { it.first }
        .toMap()

    return Config(
        general = GeneralConfig(
            destinationDir = general.stringAt("destinationDir"),
            allowedExtensions = (general?.get("allowedExtensions") as? List<*>)
                ?.mapNotNull { it?.toString() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() },
            // Truthiness, as v1: a blank path is no path, and the caller auto-detects.
            mkvmergeExe = general.stringAt("mkvmergeExe")?.takeIf { it.isNotEmpty() },
            title = general.templateAt("title"),
            matching = matcherConfigOf(general),
            outputName = general.templateAt("outputName"),
        ),
        mainSource = mainSourceOf(mapping.mapAt("mainSource"), shared),
        additionalSources = additionalSourcesOf(mapping, shared),
        trackOrder = mapping.trackOrderAt(),
        sharedTracks = sharedEntries.map { SharedTrack(it.nameAt(), usesAnother = it.containsKey("use")) },
        // A `groups:` key holding nothing usable is an empty list, not an absent one: it still declares groups.
        declaredGroups = if (mapping.containsKey("groups")) {
            mapping.mapsAt("groups").map { group ->
                GroupConfig(
                    name = group.nameAt(),
                    membership = membershipOf(group),
                    mainSource = mainSourceOf(group.mapAt("mainSource"), shared),
                    additionalSources = additionalSourcesOf(group, shared),
                    trackOrder = group.trackOrderAt(),
                )
            }
        } else {
            null
        },
    )
}

/**
 * One line per additional-source track that sets an `id`, naming it by config path and starting with
 * [configName], the config file as the user named it. Any value is refused, `0` included: the key cannot
 * change what mkvmerge addresses, so it only ever looks like a decision. Each caller applies its own
 * config-problem policy.
 */
fun additionalSourceIdProblems(config: Config, configName: String): List<String> =
    config.groups.flatMapIndexed { g, group ->
        group.additionalSources.flatMapIndexed { i, source ->
            source.tracks.mapIndexedNotNull { j, track ->
                if (track.idDeclared) {
                    val path = withRef("${config.groupPrefix(g)}additionalSources[$i].tracks[$j]", track.ref)
                    "$configName sets an id on $path, which should be removed: " +
                        "an additional-source track is always track 0"
                } else {
                    null
                }
            }
        }
    }

/**
 * One line per problem with `groups` or `sharedTracks` that the config alone can show, each starting with
 * [configName]. Each caller applies its own config-problem policy. A file claimed by two groups through
 * masks needs the file list, so that one is [partitionByGroup]'s to find.
 */
fun groupProblems(config: Config, configName: String): List<String> {
    val problems = ArrayList<String>()
    val sharedNames = config.sharedTracks.map { it.name }

    config.sharedTracks.forEachIndexed { i, shared ->
        when {
            shared.name == null -> problems += "$configName has no name for sharedTracks[$i]"
            sharedNames.indexOf(shared.name) < i ->
                problems += "$configName has two sharedTracks named '${shared.name}'"
        }
        // Silently ignoring the key would read as a decision that is not one.
        if (shared.usesAnother) {
            problems += "$configName has a use on sharedTracks[$i], but a shared track cannot build on another"
        }
    }

    config.groups.forEachIndexed { g, group ->
        for ((path, ref) in group.trackRefs()) {
            when {
                ref == "" -> problems += "$configName has an empty use at ${config.groupPrefix(g)}$path"
                ref != null && ref !in sharedNames ->
                    problems += "$configName uses an unknown shared track '$ref' at ${config.groupPrefix(g)}$path"
            }
        }
    }

    val groups = config.declaredGroups ?: return problems
    if (groups.isEmpty()) problems += "$configName declares groups but lists none"
    val topLevel = listOfNotNull(
        "mainSource".takeIf { config.mainSource != MainSourceConfig() },
        "additionalSources".takeIf { config.additionalSources.isNotEmpty() },
        "trackOrder".takeIf { config.trackOrder != null },
    )
    if (topLevel.isNotEmpty()) {
        problems += "$configName sets ${topLevel.joinToString(", ")} at the top level beside groups, " +
            "where nothing reads it; each group carries its own"
    }

    val labels = groups.mapIndexed { i, group -> "groups[$i]" + group.name?.let { " ($it)" }.orEmpty() }
    groups.forEachIndexed { i, group ->
        val membership = group.membership ?: Membership()
        membership.malformedEpisodes.forEach {
            problems += "$configName has an episode range in ${labels[i]} that is not one: '$it'"
        }
        if (membership == Membership()) problems += "$configName has no episodes or files for ${labels[i]}"
        if (group.name != null && groups.indexOfFirst { it.name == group.name } < i) {
            problems += "$configName has two groups named '${group.name}'"
        }
    }

    for (i in groups.indices) {
        for (j in i + 1 until groups.size) {
            val overlaps = groups[i].membership?.episodes.orEmpty()
                .flatMap { a -> groups[j].membership?.episodes.orEmpty().mapNotNull { b -> overlap(a, b) } }
                .sortedBy { it.first }
                // One group's own ranges may overlap each other, so two intersections can nest.
                .fold(ArrayList<IntRange>()) { merged, range ->
                    val last = merged.lastOrNull()
                    if (last != null && range.first - 1 <= last.last) {
                        merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                    } else {
                        merged += range
                    }
                    merged
                }
            if (overlaps.isNotEmpty()) {
                val spelled = overlaps.joinToString(", ") { it.spelled() }
                problems += "$configName claims episodes $spelled in both ${labels[i]} and ${labels[j]}"
            }
        }
    }

    return problems
}

/**
 * One line per problem with the matcher keys, the episode maps, a source's `files` or `general.outputName`
 * that the config alone can show, each starting with [configName]. Each caller applies its own
 * config-problem policy.
 */
fun bindingProblems(config: Config, configName: String): List<String> {
    val problems = ArrayList<String>()
    problems += matcherProblems(config.general.matching, "general", main = true, configName)
    // A declared empty name would name every output `.mkv`, and so would a blank one once it is sanitized.
    config.general.outputName?.let {
        if (it.text.isNullOrBlank()) problems += "$configName has an empty general.outputName"
    }

    config.groups.forEachIndexed { g, group ->
        group.additionalSources.forEachIndexed { i, source ->
            val path = "${config.groupPrefix(g)}additionalSources[$i]"
            val declared = source.matching.declaredKeys()
            when {
                !source.file.isNullOrEmpty() && source.files != null ->
                    problems += "$configName sets both file and files on $path; file names one file per " +
                        "episode, files binds what its masks match"
                source.files?.isEmpty() == true -> problems += "$configName has no usable mask in $path.files"
                source.files == null && declared.isNotEmpty() ->
                    problems += "$configName sets ${declared.first()} on $path, " +
                        "which has no files to bind"
            }
            problems += matcherProblems(source.matching, path, main = false, configName)
        }
    }
    return problems
}

/**
 * The config path of every key that binds files to episodes and is set, in config order: what a command that
 * does not bind yet has to refuse or warn about. `general.outputName` is not one; it names the output.
 */
fun bindingKeys(config: Config): List<String> {
    val keys = ArrayList<String>()
    keys += config.general.matching.declaredKeys().map { "general.$it" }
    config.groups.forEachIndexed { g, group ->
        group.additionalSources.forEachIndexed { i, source ->
            val path = "${config.groupPrefix(g)}additionalSources[$i]"
            if (source.files != null) keys += "$path.files"
            keys += source.matching.declaredKeys().map { "$path.$it" }
        }
    }
    return keys
}

/** A file list split by group. Data only: what a double claim or an unclaimed file *means* is the caller's to
 *  say. Every file is in exactly one of the three. */
data class GroupPartition(
    /** Per group, in group order, the files it alone claims. */
    val members: List<List<File>>,
    /** Each file claimed by more than one group, with the indices of every group claiming it. */
    val doubleClaimed: Map<File, List<Int>>,
    /** The files no group claims. */
    val unclaimed: List<File>,
)

/**
 * Split [files] by the group that claims each. A file is claimed when its episode, as [episodeOf] reports
 * it, falls in one of a group's ranges, or when its name matches one of the group's masks, which resolve
 * against [dir] as every file mask does; a null from [episodeOf] leaves only the masks. The implicit group
 * claims everything.
 */
fun partitionByGroup(
    groups: List<GroupConfig>,
    files: List<File>,
    dir: File,
    episodeOf: (File) -> Int?,
): GroupPartition {
    val masks = groups.map { compileMasks(it.membership?.files.orEmpty(), dir) }
    val claims = files.associateWith { file ->
        val episode = episodeOf(file)
        groups.indices.filter { i ->
            val membership = groups[i].membership ?: return@filter true
            (episode != null && membership.episodes.any { episode in it }) || masks[i].any { it(file) }
        }
    }

    return GroupPartition(
        members = groups.indices.map { i -> files.filter { claims.getValue(it) == listOf(i) } },
        doubleClaimed = claims.filterValues { it.size > 1 },
        unclaimed = files.filter { claims.getValue(it).isEmpty() },
    )
}

/** The config path group [index]'s entries start with: none for the implicit group, which is the top level. */
internal fun Config.groupPrefix(index: Int): String = if (declaredGroups == null) "" else "groups[$index]."

/** [path], naming the shared track its entry is built on when there is one. */
internal fun withRef(path: String, ref: String?): String = if (ref.isNullOrEmpty()) path else "$path (use: $ref)"

/**
 * What the check needs to know about the config: which ids are selected, and what each is titled.
 *
 * [videoIds] is `{0}` whenever there is a config at all, because `mux` hardcodes `0:` for video.
 * Everything is empty when inspecting without one: nothing is selected, so the check reports structure
 * only and skips the blocking/informational classification entirely.
 */
data class TrackSelection(
    val hasConfig: Boolean,
    val videoIds: Set<Int> = emptySet(),
    val audioIds: Set<Int> = emptySet(),
    val subtitleIds: Set<Int> = emptySet(),
    val titleById: Map<Int, String> = emptyMap(),
) {
    val selectedIds: Set<Int> = videoIds + audioIds + subtitleIds

    /**
     * Whether every track of [type] present anywhere in [infos] is being copied. When it is, ids cannot
     * select the wrong thing however they shift, so a difference there is informational.
     */
    fun copiesAllOfType(type: String, infos: List<ProbeResult.Probed>): Boolean {
        val selected = when (type) {
            "audio" -> audioIds
            "subtitles" -> subtitleIds
            else -> videoIds
        }
        val seen = infos.flatMapTo(HashSet()) { info ->
            info.tracks.values.filter { it.signature.type == type }.map { it.id }
        }
        return seen.isNotEmpty() && selected.containsAll(seen)
    }

    /** A discrepancy only corrupts output when it lands on a track the config picks by id *and* that
     *  type is not being copied wholesale. */
    fun isBlocking(trackId: Int, type: String, infos: List<ProbeResult.Probed>): Boolean =
        trackId in selectedIds && !copiesAllOfType(type, infos)

    companion object {
        /** Inspecting without a config: nothing is selected and nothing can block. */
        val NONE = TrackSelection(hasConfig = false)
    }
}

/** What [config] selects, or [TrackSelection.NONE] when there is no config to select anything. */
fun trackSelectionOf(config: Config?): TrackSelection {
    if (config == null) return TrackSelection.NONE

    val audio = config.mainSource.audioTracks
    val subtitles = config.mainSource.subtitleTracks
    return TrackSelection(
        hasConfig = true,
        videoIds = setOf(0),
        audioIds = audio.mapNotNullTo(HashSet()) { it.id },
        subtitleIds = subtitles.mapNotNullTo(HashSet()) { it.id },
        // A declared-but-empty title carries no text, which the report treats as no title at all.
        titleById = (audio + subtitles).mapNotNull { track ->
            val id = track.id ?: return@mapNotNull null
            val text = track.title?.text ?: return@mapNotNull null
            id to text
        }.toMap(),
    )
}

/** Every track entry of the group with the shared track it names, by config path within the group. */
private fun GroupConfig.trackRefs(): List<Pair<String, String?>> =
    listOf("mainSource.videoTrack" to mainSource.videoTrack?.ref) +
        mainSource.audioTracks.mapIndexed { i, track -> "mainSource.audioTracks[$i]" to track.ref } +
        mainSource.subtitleTracks.mapIndexed { i, track -> "mainSource.subtitleTracks[$i]" to track.ref } +
        additionalSources.flatMapIndexed { i, source ->
            source.tracks.mapIndexed { j, track -> "additionalSources[$i].tracks[$j]" to track.ref }
        }

private fun overlap(a: IntRange, b: IntRange): IntRange? =
    (maxOf(a.first, b.first)..minOf(a.last, b.last)).takeUnless { it.isEmpty() }

private fun IntRange.spelled(): String = if (first == last) "$first" else "$first-$last"

/** The pins a source accepts, cascade order then `sequence`. A main file takes no name pin: it has no main
 *  file of its own to be named after. */
private val NAME_MATCHERS = setOf("exact", "suffix")
private val MATCHERS = NAME_MATCHERS + setOf("regex", "sxxeyy", "sequence")

private fun MatcherConfig.declaredKeys(): List<String> = listOfNotNull(
    "matcher".takeIf { matcher != null },
    "episodeRegex".takeIf { episodeRegex != null },
    "seasonRegex".takeIf { seasonRegex != null },
    "episodeMap".takeIf { episodeMap != null },
)

private fun matcherProblems(matching: MatcherConfig, path: String, main: Boolean, configName: String): List<String> {
    val problems = ArrayList<String>()
    val pin = matching.pin
    // Quoted as written; the checks read the case-folded pin.
    val written = matching.matcher
    // A blank regex is reported as empty, and is no regex to every check after that.
    val regexes = listOf("episodeRegex" to matching.episodeRegex, "seasonRegex" to matching.seasonRegex)
        .mapNotNull { (key, regex) ->
            if (regex?.isBlank() == true) problems += "$configName has an empty $path.$key"
            regex?.takeUnless { it.isBlank() }?.let { key to it }
        }
    val keys = regexes.map { it.first }
    // A regex beside a pin that reads none is reported as stray, so it is not compiled as well.
    var regexRead = true
    when {
        pin == null -> if ("seasonRegex" in keys && "episodeRegex" !in keys) {
            problems += "$configName sets $path.seasonRegex without an episodeRegex"
        }
        pin.isEmpty() -> problems += "$configName has an empty $path.matcher"
        pin !in MATCHERS -> problems += "$configName has an unknown matcher '$written' at $path.matcher"
        main && pin in NAME_MATCHERS -> problems += "$configName pins '$written' at $path.matcher, which only " +
            "an additional source can use: a main file has nothing to be named after"
        pin == "regex" && "episodeRegex" !in keys ->
            problems += "$configName pins '$written' at $path.matcher with no episodeRegex"
        pin != "regex" -> {
            regexRead = false
            keys.forEach { problems += "$configName sets $path.$it beside the '$written' pin, which reads no regex" }
            // A file bound by name takes its main file's episode, which general.episodeMap has already mapped.
            if (pin in NAME_MATCHERS && matching.episodeMap != null) {
                problems += "$configName sets $path.episodeMap beside the '$written' pin, which binds by name " +
                    "and reads no number"
            }
        }
    }
    for ((key, regex) in regexes.takeIf { regexRead }.orEmpty()) {
        val groups = try {
            Pattern.compile(regex).matcher("").groupCount()
        } catch (e: PatternSyntaxException) {
            problems += "$configName has a regex at $path.$key that does not compile: ${e.description}"
            continue
        }
        if (groups < 1) {
            problems += "$configName has a regex at $path.$key that captures no group; group 1 is the number"
        }
    }
    matching.episodeMap?.let { problems += episodeMapProblems(it, "$path.episodeMap", configName) }
    return problems
}

private fun episodeMapProblems(map: EpisodeMap, path: String, configName: String): List<String> {
    if (map.notAMapping) return listOf("$configName sets $path to something that is not a mapping")
    if (map.entries.isEmpty()) return listOf("$configName has an empty $path")
    val problems = ArrayList<String>()
    for (entry in map.entries) {
        if (entry.fromRange == null) {
            problems += "$configName has a release range at $path that is not one: '${entry.from}'"
        }
        if (entry.toRange == null) {
            problems += "$configName has an episode range at $path that is not one: '${entry.to}'"
        }
        val from = entry.fromRange ?: continue
        val to = entry.toRange ?: continue
        if (from.last - from.first != to.last - to.first) {
            problems += "$configName maps ${from.spelled()} to ${to.spelled()} at $path, which differ in length"
        }
    }
    val valid = map.entries.mapNotNull { entry -> entry.fromRange?.let { from -> entry.toRange?.let { from to it } } }
    for (i in valid.indices) {
        for (j in i + 1 until valid.size) {
            overlap(valid[i].first, valid[j].first)?.let {
                problems += "$configName maps release episodes ${it.spelled()} twice at $path"
            }
            overlap(valid[i].second, valid[j].second)?.let {
                problems += "$configName maps two release numbers onto episodes ${it.spelled()} at $path"
            }
        }
    }
    return problems
}

private fun Map<*, *>?.mapAt(key: String): Map<*, *>? = this?.get(key) as? Map<*, *>

private fun Map<*, *>?.stringAt(key: String): String? = this?.get(key)?.toString()

private fun Map<*, *>?.stringsAt(key: String): List<String> =
    (this?.get(key) as? List<*>).orEmpty().mapNotNull { it?.toString() }

/** Present-with-no-value is a declared template; an absent key is no template at all. */
private fun Map<*, *>?.templateAt(key: String): Template? =
    if (this != null && containsKey(key)) Template(this[key]?.toString()) else null

private fun Map<*, *>?.mapsAt(key: String): List<Map<*, *>> =
    (this?.get(key) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

private fun Map<*, *>.trackOrderAt(): String? = stringAt("trackOrder")?.takeIf { it.isNotEmpty() }

private fun mainSourceOf(mainSource: Map<*, *>?, shared: Map<String, Map<*, *>>) = MainSourceConfig(
    videoTrack = mainSource.mapAt("videoTrack")?.overDefinition(shared)?.let {
        VideoTrackConfig(language = it.stringAt("language"), title = it.templateAt("title"), ref = it.useAt())
    },
    audioTracks = mainSource.tracksAt("audioTracks", shared),
    subtitleTracks = mainSource.tracksAt("subtitleTracks", shared),
    additionalOptions = mainSource.stringsAt("additionalOptions"),
)

private fun additionalSourcesOf(parent: Map<*, *>, shared: Map<String, Map<*, *>>) =
    parent.mapsAt("additionalSources").map { source ->
        AdditionalSource(
            file = source.stringAt("file"),
            tracks = source.sourceTracksAt("tracks", shared),
            additionalOptions = source.stringsAt("additionalOptions"),
            files = source.takeIf { it.containsKey("files") }?.masksAt("files"),
            matching = matcherConfigOf(source),
        )
    }

/** The four flat matcher keys, on `general` or on a source. A key with no value is declared, as empty. */
private fun matcherConfigOf(map: Map<*, *>?) = MatcherConfig(
    matcher = map?.declaredAt("matcher"),
    episodeRegex = map?.declaredAt("episodeRegex"),
    seasonRegex = map?.declaredAt("seasonRegex"),
    episodeMap = map?.takeIf { it.containsKey("episodeMap") }?.let { episodeMapOf(it["episodeMap"]) },
)

private fun episodeMapOf(value: Any?): EpisodeMap = when (value) {
    // Declared with no value: empty, which bindingProblems says, rather than something else.
    null -> EpisodeMap()
    is Map<*, *> ->
        EpisodeMap(value.map { (from, to) -> EpisodeMapEntry(from.toString().trim(), to?.toString().orEmpty().trim()) })
    else -> EpisodeMap(notAMapping = true)
}

/** Episode tokens come from a list or a comma-separated scalar, so `[1-10, 12]`, `"1-10, 12"` and `7` all
 *  read the same. */
private fun membershipOf(group: Map<*, *>): Membership {
    val tokens = when (val episodes = group["episodes"]) {
        null -> emptyList()
        is List<*> -> episodes.map { it?.toString().orEmpty() }
        else -> episodes.toString().split(',')
    }.map { it.trim() }.filter { it.isNotEmpty() }

    val ranges = tokens.map { it to episodeRangeOf(it) }

    return Membership(
        episodes = ranges.mapNotNull { it.second },
        malformedEpisodes = ranges.filter { it.second == null }.map { it.first },
        files = group.masksAt("files"),
    )
}

/** Masks from a list or a scalar, which is one mask. A blank mask matches nothing, so it is no mask. */
private fun Map<*, *>.masksAt(key: String): List<String> {
    val masks = this[key]
    return (if (masks is List<*>) masks.mapNotNull { it?.toString() } else listOfNotNull(masks?.toString()))
        .filter { it.isNotBlank() }
}

private val EPISODE_RANGE = Regex("""(\d+)(?:\s*-\s*(\d+))?""")

/** `N` or `N-M` with `min <= N <= M`, or null. */
private fun episodeRangeOf(token: String, min: Int = 1): IntRange? {
    val match = EPISODE_RANGE.matchEntire(token) ?: return null
    val first = match.groupValues[1].toIntOrNull() ?: return null
    val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull() ?: return null
    return if (first in min..last) first..last else null
}

/** The track entries at [key], each laid over the definition it uses. */
private fun Map<*, *>?.trackMapsAt(key: String, shared: Map<String, Map<*, *>>): List<Map<*, *>> =
    mapsAt(key).map { it.overDefinition(shared) }

/**
 * This track entry laid over the definition its `use:` names, or itself when it names none. The entry's
 * own keys win, which keeps the absent-versus-declared distinction an override relies on: `default: false`
 * beats a definition's `true`, and an empty `title:` clears a definition's title.
 */
private fun Map<*, *>.overDefinition(shared: Map<String, Map<*, *>>): Map<*, *> {
    val definition = stringAt("use")?.let(shared::get) ?: return this
    return LinkedHashMap<Any?, Any?>(definition).apply { putAll(this@overDefinition) }
}

/** Truthiness: absent, null and false all mean "not the default track". */
private fun Map<*, *>.isDefaultTrack(): Boolean = this["default"] == true || stringAt("default") == "true"

/** A `name:`, null when absent or empty: an empty name names nothing. */
private fun Map<*, *>.nameAt(): String? = stringAt("name")?.takeIf { it.isNotEmpty() }

/** The `use:` value, empty when the key is there with none, so validation can say so rather than drop it. */
private fun Map<*, *>.useAt(): String? = declaredAt("use")

/** [key]'s value, empty when the key is there with none, null when it is absent. */
private fun Map<*, *>.declaredAt(key: String): String? = if (containsKey(key)) stringAt(key).orEmpty() else null

private fun Map<*, *>?.tracksAt(key: String, shared: Map<String, Map<*, *>>): List<TrackConfig> =
    trackMapsAt(key, shared).map { track ->
        TrackConfig(
            // A quoted id is still an id: v1 coerced with `as Integer`, which read "2" as 2.
            id = (track["id"] as? Number)?.toInt() ?: track.stringAt("id")?.trim()?.toIntOrNull(),
            language = track.stringAt("language"),
            title = track.templateAt("title"),
            default = track.isDefaultTrack(),
            charset = track.stringAt("charset"),
            ref = track.useAt(),
        )
    }

private fun Map<*, *>?.sourceTracksAt(key: String, shared: Map<String, Map<*, *>>): List<SourceTrackConfig> =
    trackMapsAt(key, shared).map { track ->
        SourceTrackConfig(
            language = track.stringAt("language"),
            title = track.templateAt("title"),
            default = track.isDefaultTrack(),
            charset = track.stringAt("charset"),
            // The key, not its value: an empty `id:` or a harmless `id: 0` is refused the same way.
            idDeclared = track.containsKey("id"),
            ref = track.useAt(),
        )
    }
