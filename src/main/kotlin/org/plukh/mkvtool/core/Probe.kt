package org.plukh.mkvtool.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Probing a media file with `mkvmerge -J`, and the signatures the consistency check compares. A port of
 * the probing half of `src/lib/check.groovy`.
 *
 * The `-J` document is mapped into typed records **at parse time**, which retires the Groovy `properties`
 * trap by construction: there are no raw maps to accidentally read a bean property off. [ProbedTrack]
 * carries every field any consumer needs — identify's table, the check's signatures, and the `${'$'}{codec}`
 * substitution's `codec_id` — so nothing downstream has to hold on to the parsed JSON.
 */

/** The fields compared per slot by the consistency check.
 *
 * A blocklist model: everything mkvmerge reports that should be stable across a season is compared, and
 * what legitimately varies per episode is left out. Duration, file size, muxing application and writing
 * library are never read at all. The video track's name is excluded by being nulled in [signatureOf],
 * not by being absent here, because it routinely carries the episode title and so differs by design.
 *
 * `DEFAULT` and `FORCED` are compared because a flag that flips halfway through a season is exactly the
 * silent wrong-output failure this check exists to catch.
 */
enum class SignatureField { TYPE, CODEC, LANGUAGE, NAME, DEFAULT, FORCED }

/**
 * What the check compares for one slot. Two slots belong to the same group when these are equal, so the
 * data class *is* the group key — anything carried for display only lives on the enclosing slot, never
 * here.
 *
 * [name] is null for a video track, nulled at construction so it can never leak into a key.
 */
data class TrackSignature(
    val type: String,
    val codec: String,
    val language: String,
    val name: String?,
    val default: Boolean,
    val forced: Boolean,
) {
    /** The value of one compared field, for saying *which* field differs across a group. */
    operator fun get(field: SignatureField): Any? = when (field) {
        SignatureField.TYPE -> type
        SignatureField.CODEC -> codec
        SignatureField.LANGUAGE -> language
        SignatureField.NAME -> name
        SignatureField.DEFAULT -> default
        SignatureField.FORCED -> forced
    }
}

/** Something the check can group: a track inside a file, or an external file attached to an episode. */
sealed interface Slot {
    val signature: TrackSignature
}

/**
 * One track of a probed file. [videoName] carries a video track's real name, which [TrackSignature] nulls
 * out: it cannot enter a group key, but a report may still show it where every file in a group agrees.
 */
data class TrackSlot(
    val id: Int,
    override val signature: TrackSignature,
    val videoName: String?,
) : Slot

/**
 * An external file attached to an episode, supplied by the caller rather than read from the container —
 * `inspect` builds these from discovery, `mux` sets none, so everything keyed on them is inert there.
 *
 * Keyed by identity rather than by an index: external files have no order, and what a reader tracks
 * through a season is "does this episode have the [Омикрон] dub", not "what is at position 2".
 * [guessed] rides outside [signature] exactly as [TrackSlot.videoName] does — a language inferred from a
 * folder is presented differently but must not change what groups with what.
 */
data class ExternalSlot(
    val key: String,
    override val signature: TrackSignature,
    val guessed: Boolean,
    val label: String,
    val variantName: String,
) : Slot

/** One track exactly as mkvmerge describes it, with nothing defaulted. Defaults belong to whoever
 *  displays or compares it: [signatureOf] fills them for the check, identify fills them differently. */
data class ProbedTrack(
    val id: Int,
    val type: String?,
    val codec: String?,
    val codecId: String?,
    val language: String?,
    val trackName: String?,
    val default: Boolean,
    val forced: Boolean,
)

/**
 * What one `mkvmerge -J` run yielded.
 *
 * [Failed] is not an error to abort on: an unreadable file is a line in the report, and the batch carries
 * on without it. Both variants carry their own file, so a result never depends on where it sits.
 */
sealed interface ProbeResult {
    val file: File

    /** mkvmerge could not use this file. [reason] is the phrase a report prints after the file name. */
    data class Failed(override val file: File, val reason: String) : ProbeResult

    /**
     * A readable file. [tracks] is the signature view keyed by track id, [allTracks] the full records in
     * mkvmerge's own order (identify prints them as they come), and [chapters] the total entry count
     * across every chapter edition.
     */
    data class Probed(
        override val file: File,
        val allTracks: List<ProbedTrack>,
        val tracks: Map<Int, TrackSlot>,
        val chapters: Int,
    ) : ProbeResult
}

/** The lenient reader for mkvmerge's output: it adds keys between releases, and none of them concern us. */
private val PROBE_JSON = Json { ignoreUnknownKeys = true }

/** The `-J` document, only as far as anything here reads it. */
@Serializable
private data class IdentificationDto(
    val container: ContainerDto = ContainerDto(),
    val tracks: List<TrackDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
private data class ContainerDto(
    val recognized: Boolean = false,
    val supported: Boolean = false,
)

@Serializable
private data class TrackDto(
    val id: Int = 0,
    val type: String? = null,
    val codec: String? = null,
    val properties: PropertiesDto = PropertiesDto(),
)

@Serializable
private data class PropertiesDto(
    @SerialName("codec_id") val codecId: String? = null,
    val language: String? = null,
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("default_track") val defaultTrack: Boolean = false,
    @SerialName("forced_track") val forcedTrack: Boolean = false,
)

@Serializable
private data class ChapterDto(
    @SerialName("num_entries") val numEntries: Int = 0,
)

/** What the check compares for [track], with mkvmerge's absences filled in. `und` is Matroska's spelling
 *  of "untagged", so an untagged track compares as one rather than as a hole. */
fun signatureOf(track: ProbedTrack): TrackSignature {
    val type = track.type ?: "?"
    return TrackSignature(
        type = type,
        codec = track.codec ?: "?",
        language = track.language ?: "und",
        // Nulled at construction so it can never leak into a group key; the real value rides on
        // TrackSlot.videoName for display.
        name = if (type == "video") null else (track.trackName ?: ""),
        default = track.default,
        forced = track.forced,
    )
}

/**
 * Turn one `-J` document into a result for [file].
 *
 * **mkvmerge exits 0 on a file it cannot read**, reporting the failure in `container.recognized` /
 * `container.supported` instead. Checking the exit code alone would let a corrupt file into the
 * comparison as a file with no tracks, which reads as "every track is absent here" and poisons the whole
 * report — so both flags are checked here, and the caller checks the exit code separately.
 *
 * Malformed output is a [ProbeResult.Failed] too: this is the one place a foreign process's bytes enter
 * the model, and an exception thrown here would abort a batch over one bad file.
 */
fun parseProbe(file: File, json: String): ProbeResult {
    val parsed = try {
        PROBE_JSON.decodeFromString<IdentificationDto>(json)
    } catch (e: SerializationException) {
        return ProbeResult.Failed(file, "mkvmerge output could not be parsed (${e.javaClass.simpleName})")
    }

    if (!parsed.container.recognized) return ProbeResult.Failed(file, "not recognised as a media file")
    if (!parsed.container.supported) return ProbeResult.Failed(file, "container not supported by mkvmerge")

    val allTracks = parsed.tracks.map { dto ->
        ProbedTrack(
            id = dto.id,
            type = dto.type,
            codec = dto.codec,
            codecId = dto.properties.codecId,
            language = dto.properties.language,
            trackName = dto.properties.trackName,
            default = dto.properties.defaultTrack,
            forced = dto.properties.forcedTrack,
        )
    }

    val tracks = LinkedHashMap<Int, TrackSlot>(allTracks.size)
    for (track in allTracks) {
        tracks[track.id] = TrackSlot(
            id = track.id,
            signature = signatureOf(track),
            videoName = if (track.type == "video") (track.trackName ?: "") else null,
        )
    }

    return ProbeResult.Probed(file, allTracks, tracks, parsed.chapters.sumOf { it.numEntries })
}

/**
 * One `mkvmerge -J` per file, parsed once. Identify and the check read the same record, so asking for
 * both does not double the number of subprocesses.
 */
fun probeFile(file: File, mkvmergeExe: String): ProbeResult {
    val process = ProcessBuilder(mkvmergeExe, "-J", file.absolutePath)
        // stdout carries the JSON and is read below; stderr is discarded at the OS level rather than left
        // as a pipe nobody drains, which a chatty mkvmerge could fill and then block on forever while this
        // side waits in waitFor(). Nothing observable changes — that stream was never read.
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val json = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()

    if (exit != 0) return ProbeResult.Failed(file, "mkvmerge exit $exit")
    return parseProbe(file, json)
}

/** One distinct value at a slot, and which files carry it. [slot] is null for the files that do not have
 *  the slot at all, which is how an absence stays visible instead of being dropped. */
data class SignatureGroup<S : Slot>(
    val slot: S?,
    val fileNames: List<String>,
    val minority: Boolean,
)

/**
 * Everything the batch carries at one slot: the distinct values largest-population first, which compared
 * fields [varying] across them, the files [missing] it entirely, and whether it is [consistent].
 */
data class SlotGroup<K, S : Slot>(
    val id: K,
    val type: String,
    val groups: List<SignatureGroup<S>>,
    val varying: List<SignatureField>,
    val missing: List<String>,
    val consistent: Boolean,
)

/**
 * Group [infos] by the value each carries at every slot [slotsOf] finds.
 *
 * Deliberately does **not** anchor on the first file: the reference is the largest population. If a
 * translation was dropped from episode 8 onward, anchoring on file one would report 17 files as deviant
 * against a sample of one. Which group is correct is the user's call, not this tool's. On an even split
 * nothing is the minority, so nothing gets singled out.
 */
fun <K : Comparable<K>, S : Slot> groupSlots(
    infos: List<ProbeResult.Probed>,
    slotsOf: (ProbeResult.Probed) -> Map<K, S>,
): List<SlotGroup<K, S>> {
    val allIds = infos.flatMapTo(HashSet()) { slotsOf(it).keys }.sorted()

    return allIds.map { id ->
        // Keyed by the signature itself, null meaning "these files do not have this slot at all".
        val filesByValue = LinkedHashMap<TrackSignature?, MutableList<String>>()
        val slotByValue = LinkedHashMap<TrackSignature?, S?>()
        for (info in infos) {
            val slot = slotsOf(info)[id]
            val key = slot?.signature
            if (!filesByValue.containsKey(key)) {
                filesByValue[key] = ArrayList()
                slotByValue[key] = slot
            }
            filesByValue.getValue(key) += info.file.name
        }

        val ordered = filesByValue
            .map { (key, files) -> SignatureGroup(slotByValue[key], files.toList(), minority = false) }
            .sortedWith(
                compareByDescending<SignatureGroup<S>> { it.fileNames.size }
                    .thenBy { it.fileNames[0] }
            )
        val maxSize = ordered[0].fileNames.size
        val groups = ordered.map { it.copy(minority = it.fileNames.size < maxSize) }

        val present = groups.mapNotNull { it.slot }
        val varying = SignatureField.entries.filter { field ->
            present.map { it.signature[field] }.distinct().size > 1
        }

        SlotGroup(
            id = id,
            type = present.firstOrNull()?.signature?.type ?: "?",
            groups = groups,
            varying = varying,
            missing = groups.firstOrNull { it.slot == null }?.fileNames ?: emptyList(),
            consistent = groups.size == 1,
        )
    }
}

/** The batch's internal tracks, grouped by value at each numeric track id. */
fun groupTracks(infos: List<ProbeResult.Probed>): List<SlotGroup<Int, TrackSlot>> =
    groupSlots(infos) { it.tracks }

/** The batch's external files, grouped by value at each slot key the caller attached. */
fun groupExternals(
    infos: List<ProbeResult.Probed>,
    externalsOf: (ProbeResult.Probed) -> Map<String, ExternalSlot>,
): List<SlotGroup<String, ExternalSlot>> = groupSlots(infos, externalsOf)

/** A file's internal layout: the type at each track id, ignoring codec, name and flags. */
fun internalLayoutKey(info: ProbeResult.Probed): String =
    info.tracks.entries.sortedBy { it.key }.joinToString(" ") { (id, slot) -> "$id:${slot.signature.type}" }

/** A file's external layout: which slots are attached, as a **set** — external files have no order. */
fun externalLayoutKey(externals: Map<String, ExternalSlot>): String =
    externals.keys.sorted().joinToString(" ")

/**
 * What decides whether two files can share a muxing pass: the same track types at the same ids **and**
 * the same set of external files attached. The external half is what makes the group count answer the
 * question the report is really asked — how many configs will this season need. A season whose dubs
 * arrive at different episodes is not one job however uniform its `.mkv` files are.
 *
 * The two halves stay separately addressable ([internalLayoutKey], [externalLayoutKey]) because a report
 * has to say *which* of them differs: "a different track layout", said about a set of identical `.mkv`
 * files with a dub missing, sends the reader looking in the wrong place. Splitting this composite back on
 * its separator would work only for as long as no slot key can contain one.
 */
fun layoutKey(info: ProbeResult.Probed, externals: Map<String, ExternalSlot> = emptyMap()): String {
    val internal = internalLayoutKey(info)
    val external = externalLayoutKey(externals)
    return if (external.isEmpty()) internal else "$internal + $external"
}

/** Two or more tracks in one file that ID-based selection cannot tell apart, and the files they occur in. */
data class DuplicateTracks(
    val type: String,
    val language: String,
    val codec: String,
    val name: String?,
    val ids: List<Int>,
    val fileNames: List<String>,
)

/**
 * The genuinely ambiguous same-language tracks across [infos].
 *
 * Two tracks are only ambiguous when type, language, codec **and** name all match, both being unnamed
 * included. AC-3 "English" and DTS "English" are perfectly distinguishable, and so is a track named
 * "Director's Commentary"; only where selection by id cannot be reasoned about at all is worth flagging.
 *
 * Aggregated by (signature, ids) rather than reported per file, so a 24-file batch that all shares an
 * ambiguity prints one note instead of twenty-four.
 */
fun findDuplicates(infos: List<ProbeResult.Probed>): List<DuplicateTracks> {
    val filesByAmbiguity = LinkedHashMap<Pair<Ambiguity, List<Int>>, MutableList<String>>()

    for (info in infos) {
        info.tracks.values
            .filter { it.signature.type != "video" }
            .groupBy { Ambiguity(it.signature) }
            .filter { (_, sameValue) -> sameValue.size > 1 }
            .forEach { (ambiguity, sameValue) ->
                val key = ambiguity to sameValue.map { it.id }.sorted()
                filesByAmbiguity.getOrPut(key) { ArrayList() } += info.file.name
            }
    }

    return filesByAmbiguity.map { (key, files) ->
        val (ambiguity, ids) = key
        DuplicateTracks(ambiguity.type, ambiguity.language, ambiguity.codec, ambiguity.name, ids, files)
    }
}

/** What makes two tracks indistinguishable to ID-based selection. Deliberately **not** the whole
 *  signature: two tracks alike in all of these but differing in a flag are still ambiguous to pick
 *  between, so `default`/`forced` are left out. */
private data class Ambiguity(
    val type: String,
    val language: String,
    val codec: String,
    val name: String?,
) {
    constructor(signature: TrackSignature) :
        this(signature.type, signature.language, signature.codec, signature.name)
}
