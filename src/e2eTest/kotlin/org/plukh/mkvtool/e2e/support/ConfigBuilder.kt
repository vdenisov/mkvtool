package org.plukh.mkvtool.e2e.support

/**
 * Building the `config.yaml` a `mux` case runs against.
 *
 * This emits **text**, deliberately, rather than serialising the production `Config` model: the point of
 * an end-to-end case is to exercise the real parser, and a builder that shared the model's code could not
 * fail on a config the parser mishandles.
 *
 * One distinction here is load-bearing and easy to lose. A track list that is **absent** and one that is
 * **empty** mean different things to `mux`: an absent key leaves the type unconfigured, an empty list says
 * "copy none of these", and both are separately pinned by cases. Groovy expressed it with `containsKey`;
 * Kotlin expresses it as `null` versus `emptyList()`, the same shape `core/Config.kt` draws between a
 * missing template and a declared-but-empty one.
 */

/** A track selected out of the main source, by the id it carries there. */
data class TrackSpec(
    val id: Int,
    val language: String,
    val title: String,
    val default: Boolean = false,
    val charset: String? = null,
)

/** A track inside an additional source, which mkvmerge always sees as track 0 — hence no id. */
data class SourceTrackSpec(
    val language: String,
    val title: String,
    val default: Boolean = false,
    val charset: String? = null,
)

data class AdditionalSourceSpec(
    val file: String,
    val tracks: List<SourceTrackSpec>,
    val additionalOptions: List<String> = emptyList(),
)

/**
 * Render a `config.yaml`.
 *
 * [audioTracks] and [subtitleTracks] are null by default, meaning the key is omitted entirely. Pass
 * `emptyList()` to write `[]`, which is a different instruction. [trackOrder] is likewise omitted when
 * null, so a case can exercise the derivation.
 */
fun cfg(
    destinationDir: String = "mkv",
    extensions: List<String> = listOf("mkv", "avi", "mp4"),
    mkvmergeExe: String = requireNotNull(org.plukh.mkvtool.e2e.support.mkvmergeExe) {
        "mkvmerge is required to build a config — this case should have been gated on needsMkvmerge"
    },
    generalTitle: String? = null,
    videoLang: String = "en",
    videoTitle: String? = null,
    audioTracks: List<TrackSpec>? = null,
    subtitleTracks: List<TrackSpec>? = null,
    mainAdditionalOptions: List<String> = emptyList(),
    trackOrder: String? = null,
    additionalSources: List<AdditionalSourceSpec> = emptyList(),
): String = buildString {
    append("general:\n")
    append("  destinationDir: \"$destinationDir\"\n")
    append("  allowedExtensions: [${extensions.joinToString(", ") { "\"$it\"" }}]\n")
    append("  mkvmergeExe: \"${mkvmergeExe.yamlEscaped()}\"\n")
    // Omitted unless asked for, so the default — the file name — stays what every other case exercises.
    generalTitle?.let { append("  title: \"$it\"\n") }

    append("mainSource:\n")
    append("  videoTrack:\n")
    append("    language: \"$videoLang\"\n")
    videoTitle?.let { append("    title: \"$it\"\n") }

    audioTracks?.let { append(trackList("audioTracks", it, indent = "  ")) }
    subtitleTracks?.let { append(trackList("subtitleTracks", it, indent = "  ")) }

    if (mainAdditionalOptions.isNotEmpty()) {
        append("  additionalOptions:\n")
        mainAdditionalOptions.forEach { append("    - \"$it\"\n") }
    }

    trackOrder?.let { append("trackOrder: \"$it\"\n") }

    if (additionalSources.isNotEmpty()) {
        append("additionalSources:\n")
        additionalSources.forEach { source ->
            append("  - file: \"${source.file.yamlEscaped()}\"\n")
            append("    tracks:\n")
            source.tracks.forEach { track ->
                append("      - language: \"${track.language}\"\n")
                append("        title: \"${track.title}\"\n")
                track.charset?.let { append("        charset: \"$it\"\n") }
                append("        default: ${track.default}\n")
            }
            if (source.additionalOptions.isNotEmpty()) {
                append("    additionalOptions:\n")
                source.additionalOptions.forEach { append("      - \"$it\"\n") }
            }
        }
    }
}

/**
 * The config the consistency-check cases run against: audio 1 and 2, subtitle 6.
 *
 * Those three ids are exactly the ones the check cases perturb with `fullCopy(names = ...)`, and a
 * seven-track copy is what makes an id in this config address the track it names. No `trackOrder`, so
 * derivation stays exercised.
 */
fun checkCfg(
    mkvmergeExe: String = requireNotNull(org.plukh.mkvtool.e2e.support.mkvmergeExe) {
        "mkvmerge is required to build a config — this case should have been gated on needsMkvmerge"
    },
): String = cfg(
    mkvmergeExe = mkvmergeExe,
    audioTracks = listOf(
        TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
        TrackSpec(id = 2, language = "ru", title = "Russian"),
    ),
    subtitleTracks = listOf(TrackSpec(id = 6, language = "ja", title = "Signs")),
)

/**
 * The config the companion pre-flight cases run against: one audio track plus a `${'$'}{fileName}` source.
 *
 * The `1:0` in the track order is what makes a missing companion a hard mkvmerge failure rather than a
 * quietly smaller output - which is the situation the pre-flight exists to catch before the batch starts.
 *
 * Note the escape in the source pattern: the placeholder has to reach the YAML as literal text for the
 * tool to resolve it, and Kotlin's string interpolation is the new way to break what Groovy's
 * single-quoted literals protected.
 */
fun companionCfg(
    mkvmergeExe: String = requireNotNull(org.plukh.mkvtool.e2e.support.mkvmergeExe) {
        "mkvmerge is required to build a config — this case should have been gated on needsMkvmerge"
    },
): String = cfg(
    mkvmergeExe = mkvmergeExe,
    audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true)),
    trackOrder = "0:0,0:1,1:0",
    additionalSources = listOf(
        AdditionalSourceSpec(
            file = "\${fileName}[Studio].mka",
            tracks = listOf(SourceTrackSpec(language = "ru", title = "Studio Dub")),
        ),
    ),
)

/** An empty list is written as `[]` rather than omitted — see the distinction in the file comment. */
private fun trackList(key: String, tracks: List<TrackSpec>, indent: String): String {
    if (tracks.isEmpty()) return "$indent$key: []\n"
    return buildString {
        append("$indent$key:\n")
        tracks.forEach { track ->
            append("$indent  - id: ${track.id}\n")
            append("$indent    language: \"${track.language}\"\n")
            append("$indent    title: \"${track.title}\"\n")
            track.charset?.let { append("$indent    charset: \"$it\"\n") }
            append("$indent    default: ${track.default}\n")
        }
    }
}

/**
 * A Windows path lands in a YAML double-quoted scalar, where a backslash is an escape. Doubling them is
 * what keeps `C:\Program Files\MKVToolNix\mkvmerge.exe` readable as itself rather than as a string with a
 * form feed in it.
 */
private fun String.yamlEscaped(): String = replace("\\", "\\\\")
