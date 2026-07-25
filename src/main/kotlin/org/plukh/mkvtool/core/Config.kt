package org.plukh.mkvtool.core

import java.io.File

/**
 * `config.yaml`: the muxing decisions, typed. A port of the reading v1 spread across `mux.groovy`,
 * `inspect.groovy` and `check.groovy`'s `makeSelection`.
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
)

/** The video track, which `mux` always copies and always addresses as track 0. */
data class VideoTrackConfig(
    val language: String? = null,
    val title: Template? = null,
)

/**
 * One configured track. [id] is the number it carries in its own file — for a track inside an additional
 * source that is always 0, since mkvmerge sees a single-track file there.
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
)

/** The main file's tracks. An absent list and an empty one mean the same thing — copy none of that type. */
data class MainSourceConfig(
    val videoTrack: VideoTrackConfig? = null,
    val audioTracks: List<TrackConfig> = emptyList(),
    val subtitleTracks: List<TrackConfig> = emptyList(),
    val additionalOptions: List<String> = emptyList(),
)

/** An extra file muxed in alongside the main source. [file] is templated — `${'$'}{fileName}` and friends. */
data class AdditionalSource(
    val file: String? = null,
    val tracks: List<TrackConfig> = emptyList(),
    val additionalOptions: List<String> = emptyList(),
)

/**
 * The whole config.
 *
 * [trackOrder] is left as the raw `sourceIndex:trackId` CSV: it is validated against the configured
 * tracks and warned about rather than parsed into a structure, and mkvmerge itself silently ignores ids
 * that are not there.
 */
data class Config(
    val general: GeneralConfig = GeneralConfig(),
    val mainSource: MainSourceConfig = MainSourceConfig(),
    val additionalSources: List<AdditionalSource> = emptyList(),
    val trackOrder: String? = null,
)

/** Load [file] as a config, or classify why it cannot be one. Auto-detected charset: `config.yaml` is
 *  hand-written, which is the contract [loadMapping] keeps for it. */
fun loadConfig(file: File): MappingLoad<Config> = loadMapping(file, transform = ::parseConfig)

/** Turn an already-parsed mapping into the model. Never throws: anything unreadable is simply absent. */
fun parseConfig(mapping: Map<*, *>): Config {
    val general = mapping.mapAt("general")
    val mainSource = mapping.mapAt("mainSource")

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
        ),
        mainSource = MainSourceConfig(
            videoTrack = mainSource.mapAt("videoTrack")?.let {
                VideoTrackConfig(language = it.stringAt("language"), title = it.templateAt("title"))
            },
            audioTracks = mainSource.tracksAt("audioTracks"),
            subtitleTracks = mainSource.tracksAt("subtitleTracks"),
            additionalOptions = mainSource.stringsAt("additionalOptions"),
        ),
        additionalSources = (mapping["additionalSources"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { source ->
                AdditionalSource(
                    file = source.stringAt("file"),
                    // Always track 0: mkvmerge sees a companion as a single-track file.
                    tracks = source.tracksAt("tracks").map { it.copy(id = it.id ?: 0) },
                    additionalOptions = source.stringsAt("additionalOptions"),
                )
            },
        trackOrder = mapping.stringAt("trackOrder")?.takeIf { it.isNotEmpty() },
    )
}

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

private fun Map<*, *>?.mapAt(key: String): Map<*, *>? = this?.get(key) as? Map<*, *>

private fun Map<*, *>?.stringAt(key: String): String? = this?.get(key)?.toString()

private fun Map<*, *>?.stringsAt(key: String): List<String> =
    (this?.get(key) as? List<*>).orEmpty().mapNotNull { it?.toString() }

/** Present-with-no-value is a declared template; an absent key is no template at all. */
private fun Map<*, *>?.templateAt(key: String): Template? =
    if (this != null && containsKey(key)) Template(this[key]?.toString()) else null

private fun Map<*, *>?.tracksAt(key: String): List<TrackConfig> =
    (this?.get(key) as? List<*>).orEmpty()
        .mapNotNull { it as? Map<*, *> }
        .map { track ->
            TrackConfig(
                // A quoted id is still an id: v1 coerced with `as Integer`, which read "2" as 2.
                id = (track["id"] as? Number)?.toInt() ?: track.stringAt("id")?.trim()?.toIntOrNull(),
                language = track.stringAt("language"),
                title = track.templateAt("title"),
                // Truthiness: absent, null and false all mean "not the default track".
                default = track["default"] == true || track.stringAt("default") == "true",
                charset = track.stringAt("charset"),
            )
        }
