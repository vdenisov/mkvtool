package org.plukh.mkvtool.core

import java.io.File
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
 * How the main files get their episode numbers, as written: an [episodeRegex] is the rule when set, `SxxEyy`
 * otherwise. Values stay raw so [bindingProblems] can quote them.
 */
data class MatcherConfig(
    val episodeRegex: String? = null,
    val seasonRegex: String? = null,
    /** Null when absent, which is the identity map. */
    val episodeMap: EpisodeMap? = null,
)

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
 * An extra file muxed in alongside the main source. [file] is a template — `${'$'}{fileName}` and friends — naming
 * the file per episode, and its own text may carry wildcards, which binding resolves to exactly one file.
 */
data class AdditionalSource(
    val file: String? = null,
    val tracks: List<SourceTrackConfig> = emptyList(),
    val additionalOptions: List<String> = emptyList(),
)

/**
 * A `sharedTracks` entry as far as validation needs it. The definition itself is laid under every track
 * entry that names it at parse time, so nothing downstream ever resolves a reference.
 */
data class SharedTrack(val name: String?, val usesAnother: Boolean = false)

/**
 * Which files a group claims: those whose episode falls in [episodes]. [malformedEpisodes] keeps the tokens
 * that are not `N` or `N-M`, as written, for validation.
 */
data class Membership(
    val episodes: List<IntRange> = emptyList(),
    val malformedEpisodes: List<String> = emptyList(),
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
 * A `groups` entry as written. A null selection field is one the group does not write, which it takes from the
 * top level whole; a written one is the group's own, whatever its value. [trackOrder] is `""` when written empty,
 * which is the group's derived order rather than the top level's.
 */
data class DeclaredGroup(
    val name: String? = null,
    val membership: Membership = Membership(),
    val mainSource: MainSourceConfig? = null,
    val additionalSources: List<AdditionalSource>? = null,
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
 * iterates: the declared groups with the top-level fields they do not write, or one implicit group made of the
 * top-level fields.
 */
data class Config(
    val general: GeneralConfig = GeneralConfig(),
    val mainSource: MainSourceConfig = MainSourceConfig(),
    val additionalSources: List<AdditionalSource> = emptyList(),
    val trackOrder: String? = null,
    val sharedTracks: List<SharedTrack> = emptyList(),
    val declaredGroups: List<DeclaredGroup>? = null,
) {
    val groups: List<GroupConfig> = declaredGroups?.map { group ->
        GroupConfig(
            name = group.name,
            membership = group.membership,
            mainSource = group.mainSource ?: mainSource,
            additionalSources = group.additionalSources ?: additionalSources,
            trackOrder = if (group.trackOrder == null) trackOrder else group.trackOrder.ifEmpty { null },
        )
    } ?: listOf(GroupConfig(null, null, mainSource, additionalSources, trackOrder))
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
                // A key the group writes is its own whatever its value: `additionalSources: []` muxes none.
                DeclaredGroup(
                    name = group.nameAt(),
                    membership = membershipOf(group),
                    mainSource = group.ifWritten("mainSource") { mainSourceOf(group.mapAt("mainSource"), shared) },
                    additionalSources = group.ifWritten("additionalSources") { additionalSourcesOf(group, shared) },
                    trackOrder = group.ifWritten("trackOrder") { group.trackOrderAt().orEmpty() },
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
    // Groups inheriting one top-level source would each report it, under the same path.
    config.groups.flatMapIndexed { g, group ->
        group.additionalSources.flatMapIndexed { i, source ->
            source.tracks.mapIndexedNotNull { j, track ->
                if (track.idDeclared) {
                    val prefix = config.writtenPrefix(g) { it.additionalSources }
                    val path = withRef("${prefix}additionalSources[$i].tracks[$j]", track.ref)
                    "$configName sets an id on $path, which should be removed: " +
                        "an additional-source track is always track 0"
                } else {
                    null
                }
            }
        }
    }.distinct()

/**
 * One line per problem with `groups` or `sharedTracks` that the config alone can show, each starting with
 * [configName]. Each caller applies its own config-problem policy.
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

    config.groups.indices.forEach { g ->
        for ((path, ref) in config.trackRefs(g)) {
            when {
                ref == "" -> problems += "$configName has an empty use at $path"
                ref != null && ref !in sharedNames ->
                    problems += "$configName uses an unknown shared track '$ref' at $path"
            }
        }
    }

    val groups = config.declaredGroups ?: return problems.distinct()
    if (groups.isEmpty()) problems += "$configName declares groups but lists none"
    // A top-level field every group writes for itself is read by none of them.
    val unread = listOfNotNull(
        "mainSource".takeIf { config.mainSource != MainSourceConfig() && groups.all { it.mainSource != null } },
        "additionalSources".takeIf {
            config.additionalSources.isNotEmpty() && groups.all { it.additionalSources != null }
        },
        "trackOrder".takeIf { config.trackOrder != null && groups.all { it.trackOrder != null } },
    )
    if (groups.isNotEmpty() && unread.isNotEmpty()) {
        problems += "$configName sets ${unread.joinToString(", ")} at the top level, where nothing reads it; " +
            "every group sets its own"
    }

    val labels = groups.mapIndexed { i, group -> "groups[$i]" + group.name?.let { " ($it)" }.orEmpty() }
    groups.forEachIndexed { i, group ->
        val membership = group.membership
        membership.malformedEpisodes.forEach {
            problems += "$configName has an episode range in ${labels[i]} that is not one: '$it'"
        }
        if (membership == Membership()) problems += "$configName has no episodes for ${labels[i]}"
        if (group.name != null && groups.indexOfFirst { it.name == group.name } < i) {
            problems += "$configName has two groups named '${group.name}'"
        }
    }

    for (i in groups.indices) {
        for (j in i + 1 until groups.size) {
            val overlaps = groups[i].membership.episodes
                .flatMap { a -> groups[j].membership.episodes.mapNotNull { b -> overlap(a, b) } }
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

    // Groups inheriting one top-level entry would each report it, under the same path.
    return problems.distinct()
}

/**
 * One line per problem with the matcher keys, the episode map, `general.outputName` or a source's wildcard that
 * the config alone can show, each starting with [configName]. Each caller applies its own config-problem policy.
 */
fun bindingProblems(config: Config, configName: String): List<String> {
    val problems = ArrayList<String>()
    problems += mainMatcherProblems(config, configName)
    // A declared empty name would name every output `.mkv`, and so would a blank one once it is sanitized.
    config.general.outputName?.let {
        if (it.text.isNullOrBlank()) problems += "$configName has an empty general.outputName"
    }
    config.groups.forEachIndexed { g, group ->
        group.additionalSources.forEachIndexed { i, source ->
            val file = source.file ?: return@forEachIndexed
            // A wildcard is matched against the walked tree, which holds no ./, ../, absolute path or dot-folder.
            if (hasWildcard(file) && !walkReaches(file)) {
                val path = config.writtenPrefix(g) { it.additionalSources } + groupSourcePath(i)
                problems += "$configName has a wildcard in $path.file where the media directory's walk does not " +
                    "reach; a wildcard matches paths relative to it, with no ./, ../ or dot-folder"
            }
        }
    }
    return problems.distinct()
}


/** What [bindingProblems] finds in `general`'s matchers alone, which every main file is read by. */
fun mainMatcherProblems(config: Config, configName: String): List<String> =
    matcherProblems(config.general.matching, "general", configName)

/** A file list split by group. Data only: what an unclaimed file *means* is the caller's to say. Every file is
 *  in exactly one of the two. */
data class GroupPartition(
    /** Per group, in group order, the files it claims. */
    val members: List<List<File>>,
    /** The files no group claims. */
    val unclaimed: List<File>,
)

/**
 * Split [files] by the group that claims each: the one whose ranges hold the file's episode, as [episodeOf]
 * reports it, so a file with no episode is claimed by no declared group. The implicit group claims everything.
 */
fun partitionByGroup(groups: List<GroupConfig>, files: List<File>, episodeOf: (File) -> Int?): GroupPartition {
    val groupOf = files.associateWith { file ->
        val episode = episodeOf(file)
        // Overlapping ranges are a config problem, which inspect reports and carries on past: no group gets that
        // episode, rather than whichever is listed first.
        groups.indices.singleOrNull { i ->
            val membership = groups[i].membership ?: return@singleOrNull true
            episode != null && membership.episodes.any { episode in it }
        } ?: -1
    }

    return GroupPartition(
        members = groups.indices.map { i -> files.filter { groupOf.getValue(it) == i } },
        unclaimed = files.filter { groupOf.getValue(it) == -1 },
    )
}

/** A declared group as output names it: its [index] in the config, and its [name] when it has one. */
data class GroupRef(val index: Int, val name: String?)

/** Group [index] of a config declaring groups, or null for the implicit group, which output never names. */
fun Config.groupRef(index: Int): GroupRef? = declaredGroups?.let { GroupRef(index, it[index].name) }

/** `group N (name)`: counted from 1, the name only when there is one. Config paths stay in config problems. */
fun groupLabel(group: GroupRef): String = "group ${group.index + 1}" + group.name?.let { " ($it)" }.orEmpty()

/**
 * The config path an entry of group [group] is written under: the group's own when it writes the field [written]
 * picks, the top level's when it inherits it, and the top level's for the implicit group. Config problems name
 * this, the line to edit; output names a group's source by [sourcePath], as the group runs it.
 */
internal fun Config.writtenPrefix(group: Int, written: (DeclaredGroup) -> Any?): String =
    declaredGroups?.get(group)?.takeIf { written(it) != null }?.let { "groups[$group]." }.orEmpty()

/** Group [group]'s additional source [index] as the group runs it, inherited or not; the implicit group's is
 *  the top level's. */
internal fun Config.sourcePath(group: Int, index: Int): String =
    (if (declaredGroups == null) "" else "groups[$group].") + groupSourcePath(index)

/** The config path of additional source [index] within its group, as a pass that already names the group says it. */
internal fun groupSourcePath(index: Int): String = "additionalSources[$index]"

private val GROUP_PREFIX = Regex("""^groups\[(\d+)]\.""")

/** A [sourcePath] as output names it: its `groups[i].` prefix read back as the label of the group in [groups]. */
fun sourceLabel(path: String, groups: List<GroupRef>): String {
    val match = GROUP_PREFIX.find(path) ?: return path
    val group = groups.getOrNull(match.groupValues[1].toInt()) ?: return path
    return "${groupLabel(group)}: ${path.substring(match.range.last + 1)}"
}

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

/** What [mainSource] selects, or [TrackSelection.NONE] when there is no config to select anything. */
fun trackSelectionOf(mainSource: MainSourceConfig?): TrackSelection {
    if (mainSource == null) return TrackSelection.NONE

    val audio = mainSource.audioTracks
    val subtitles = mainSource.subtitleTracks
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

/** What a group muxes, in the form two groups' selections are compared in: equal means the same output. */
data class EffectiveSelection(
    val mainSource: MainSourceConfig,
    val additionalSources: List<AdditionalSource>,
    val trackOrder: String,
)

/** This group's [EffectiveSelection]. */
fun GroupConfig.effectiveSelection(): EffectiveSelection = EffectiveSelection(
    // A `use:` and the same keys written inline are one track; the name only says where they came from.
    mainSource = mainSource.copy(
        videoTrack = mainSource.videoTrack?.copy(ref = null),
        audioTracks = mainSource.audioTracks.map { it.copy(ref = null) },
        subtitleTracks = mainSource.subtitleTracks.map { it.copy(ref = null) },
    ),
    additionalSources = additionalSources.map { source ->
        source.copy(tracks = source.tracks.map { it.copy(ref = null) })
    },
    // An omitted order is the derived one, and mkvmerge ignores spaces around an entry; an empty entry stays,
    // since mkvmerge refuses it.
    trackOrder = (trackOrder ?: deriveTrackOrder(this)).split(',').joinToString(",") { it.trim() },
)

/** Every track entry of group [g] with the shared track it names, by the config path it is written under. */
private fun Config.trackRefs(g: Int): List<Pair<String, String?>> {
    val group = groups[g]
    val main = writtenPrefix(g) { it.mainSource } + "mainSource"
    val sources = writtenPrefix(g) { it.additionalSources } + "additionalSources"
    return listOf("$main.videoTrack" to group.mainSource.videoTrack?.ref) +
        group.mainSource.audioTracks.mapIndexed { i, track -> "$main.audioTracks[$i]" to track.ref } +
        group.mainSource.subtitleTracks.mapIndexed { i, track -> "$main.subtitleTracks[$i]" to track.ref } +
        group.additionalSources.flatMapIndexed { i, source ->
            source.tracks.mapIndexed { j, track -> "$sources[$i].tracks[$j]" to track.ref }
        }
}

private fun overlap(a: IntRange, b: IntRange): IntRange? =
    (maxOf(a.first, b.first)..minOf(a.last, b.last)).takeUnless { it.isEmpty() }

private fun IntRange.spelled(): String = if (first == last) "$first" else "$first-$last"

/** Whether the media directory's walk reaches [template]'s folders: relative, not climbing out, no dot-folder. */
private fun walkReaches(template: String): Boolean {
    val path = template.replace('\\', '/')
    val folders = path.split('/').dropLast(1)
    return !File(path).isAbsolute && !path.startsWith("/") && folders.none { it.startsWith(".") }
}

/** What [matching], written at [path], says wrong about itself: its regexes and its episode map. */
private fun matcherProblems(matching: MatcherConfig, path: String, configName: String): List<String> {
    val problems = ArrayList<String>()
    // A blank regex is reported as empty, and is no regex to every check after that.
    val regexes = listOf("episodeRegex" to matching.episodeRegex, "seasonRegex" to matching.seasonRegex)
        .mapNotNull { (key, regex) ->
            if (regex?.isBlank() == true) problems += "$configName has an empty $path.$key"
            regex?.takeUnless { it.isBlank() }?.let { key to it }
        }
    val keys = regexes.map { it.first }
    if ("seasonRegex" in keys && "episodeRegex" !in keys) {
        problems += "$configName sets $path.seasonRegex without an episodeRegex"
    }
    for ((key, regex) in regexes) {
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
        )
    }

/** The three flat matcher keys on `general`. A key with no value is declared, as empty. */
private fun matcherConfigOf(map: Map<*, *>?) = MatcherConfig(
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
    )
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

/** [read]'s value when [key] is written, whatever its value; null when it is absent. */
private fun <T> Map<*, *>.ifWritten(key: String, read: () -> T): T? = if (containsKey(key)) read() else null

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
