package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult

/**
 * The consistency check as a model. A port of `runConsistencyCheck` from `src/lib/check.groovy`, split
 * per the output seam: this computes the answer and composes no text at all — every phrase v1 built here
 * is composed by the report's text renderer instead.
 *
 * What the check is for: `config.yaml` selects tracks by numeric id, which assumes every episode has the
 * same track layout. When that breaks — a translation added mid-season, an old one dropped, a different
 * release group ordering tracks differently — mkvmerge does not complain. It muxes whatever sits at that
 * id, and the result is a season where some episodes have the wrong dub labelled as something else.
 *
 * The answer works in **two layers**, which came out of a real mixed-layout season. Files are grouped by
 * *layout* (the type at each id, plus the set of external files attached), largest group first; then
 * within each group, by *value* per id. A per-id comparison alone put the same shifted file at three
 * different ids and was unreadable.
 */

/**
 * Which tracks a config selects, and therefore what a discrepancy costs.
 *
 * Built by the caller from its parsed config, so this file never learns the config's shape. [videoIds] is
 * `{0}` whenever there is a config at all, because `mux` hardcodes `0:` for video.
 *
 * Everything is empty when inspecting without a config: nothing is selected, so the check reports
 * structure only and skips the blocking/informational classification entirely.
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

/** Something the check found. [blocking] means it can change what gets muxed; everything else is worth
 *  saying but cannot corrupt output. */
sealed interface Finding {
    val blocking: Boolean
}

/**
 * A layout group that differs from the largest one. [internalDiffers] and [externalsDiffer] are kept
 * apart because a report has to say *which*: "a different track layout", said about a set of identical
 * `.mkv` files with a dub missing, sends the reader looking in the wrong place.
 */
data class LayoutFinding(
    val fileCount: Int,
    val internalDiffers: Boolean,
    val externalsDiffer: Boolean,
    val affectedIds: List<Int>,
    override val blocking: Boolean,
) : Finding

/** One track id whose value varies across the largest layout group. */
data class TrackValueFinding(
    val id: Int,
    val type: String,
    val configTitle: String?,
    val varying: List<SignatureField>,
    val groupCount: Int,
    override val blocking: Boolean,
) : Finding

/**
 * One external slot whose value varies — the same dub tagged Russian for half a season and untagged for
 * the rest. Never blocking, since nothing selects an external file by id, but the same class of surprise
 * as an internal split and worth saying.
 */
data class ExternalValueFinding(
    val label: String,
    val variantName: String,
    val type: String,
    val varying: List<SignatureField>,
    val groupCount: Int,
) : Finding {
    override val blocking: Boolean get() = false
}

/** Tracks that id-based selection cannot tell apart. Blocking when the config selects one of them. */
data class AmbiguousTracksFinding(
    val ids: List<Int>,
    val selectedIds: List<Int>,
    override val blocking: Boolean,
) : Finding

/** Chapters present in some files of the batch and absent in others. */
data object ChapterFinding : Finding {
    override val blocking: Boolean get() = false
}

/** A file mkvmerge could not use, and why. Excluded from every comparison. */
data class UnreadableFile(val fileName: String, val reason: String)

/**
 * One set of files that would need the same muxing command: the same track types at the same ids, and
 * the same external files attached.
 *
 * [videoNamesById] carries the distinct video-track titles seen across the group's files, which the
 * signatures deliberately drop — a video row stands for every file in the group, and their titles differ
 * by design, so a renderer shows the shared one where they agree and says so where they do not.
 */
data class LayoutGroup(
    val fileNames: List<String>,
    val episodeLabels: List<String>?,
    val trackTypes: List<String>,
    val externalLabels: List<String>,
    val trackGroups: List<SlotGroup<Int, TrackSlot>>,
    val externalGroups: List<SlotGroup<String, ExternalSlot>>,
    val videoNamesById: Map<Int, List<String>>,
)

/** Which files carry chapters and which do not, when the batch is split on it. */
data class ChapterSplit(
    val withChapters: List<String>,
    val withoutChapters: List<String>,
)

/**
 * Everything the check found. The root of its own result document, embedded by `inspect` and by `mux`'s
 * pre-flight — the same report, which is why [headerLabel] is the only thing that differs between them.
 */
data class CheckReport(
    val headerLabel: String,
    val hasConfig: Boolean,
    val hasExternals: Boolean,
    val readable: List<String>,
    val unreadable: List<UnreadableFile>,
    val layouts: List<LayoutGroup>,
    val duplicates: List<DuplicateTracks>,
    val chapters: ChapterSplit?,
    val findings: List<Finding>,
) : CommandResult {

    /** What `--strict` exits on. */
    val blockingCount: Int get() = findings.count { it.blocking }
}

/**
 * Build the check's answer for [infos], in the order given.
 *
 * [externalsOf] supplies the external files attached to each episode — `inspect` builds them from
 * discovery, `mux` supplies none, so every external path here is inert there and its pre-flight output is
 * unchanged by their existence.
 */
fun buildCheckReport(
    infos: List<ProbeResult>,
    externalsOf: (ProbeResult.Probed) -> Map<String, ExternalSlot> = { emptyMap() },
    selection: TrackSelection = TrackSelection.NONE,
    headerLabel: String = "Pre-flight check",
): CheckReport {
    val ok = infos.filterIsInstance<ProbeResult.Probed>()
    val unreadable = infos.filterIsInstance<ProbeResult.Failed>().map { UnreadableFile(it.file.name, it.reason) }

    if (ok.isEmpty()) {
        return CheckReport(
            headerLabel = headerLabel,
            hasConfig = selection.hasConfig,
            hasExternals = false,
            readable = emptyList(),
            unreadable = unreadable,
            layouts = emptyList(),
            duplicates = emptyList(),
            chapters = null,
            findings = emptyList(),
        )
    }

    // Largest group first, ties broken by name so the output is deterministic.
    val grouped = ok.groupBy { layoutKey(it, externalsOf(it)) }.values
        .sortedWith(
            compareByDescending<List<ProbeResult.Probed>> { it.size }.thenBy { it[0].file.name }
        )
    val largest = grouped[0]
    val findings = ArrayList<Finding>()

    val layouts = grouped.mapIndexed { index, group ->
        val trackGroups = groupTracks(group)
        val externalGroups = groupExternals(group, externalsOf)

        if (index > 0) {
            findings += layoutFinding(group, largest, externalsOf, selection)
        } else {
            for (slotGroup in trackGroups.filter { !it.consistent }) {
                findings += TrackValueFinding(
                    id = slotGroup.id,
                    type = slotGroup.type,
                    configTitle = selection.titleById[slotGroup.id],
                    varying = slotGroup.varying,
                    groupCount = slotGroup.groups.size,
                    blocking = selection.isBlocking(slotGroup.id, slotGroup.type, largest),
                )
            }
            for (slotGroup in externalGroups.filter { !it.consistent }) {
                // Within one layout group every file carries the same slot keys, so a present slot
                // always exists here.
                val slot = slotGroup.groups.firstNotNullOf { it.slot }
                findings += ExternalValueFinding(
                    label = slot.label,
                    variantName = slot.variantName,
                    type = slotGroup.type,
                    varying = slotGroup.varying,
                    groupCount = slotGroup.groups.size,
                )
            }
        }

        LayoutGroup(
            fileNames = group.map { it.file.name },
            episodeLabels = membershipLabels(group.map { it.file.name.substringBeforeLast('.') }),
            trackTypes = group[0].tracks.entries.sortedBy { it.key }.map { it.value.signature.type },
            externalLabels = externalsOf(group[0]).values.map { it.label }.distinct().sorted(),
            trackGroups = trackGroups,
            externalGroups = externalGroups,
            videoNamesById = videoNamesById(group),
        )
    }

    // Ambiguous duplicates and chapters are observations across the whole batch, reported once
    // regardless of layout.
    val duplicates = findDuplicates(ok)
    for (duplicate in duplicates) {
        val selected = duplicate.ids.filter { selection.isBlocking(it, duplicate.type, ok) }
        findings += AmbiguousTracksFinding(duplicate.ids, selected, blocking = selected.isNotEmpty())
    }

    val withChapters = ok.filter { it.chapters > 0 }.map { it.file.name }
    val withoutChapters = ok.filter { it.chapters == 0 }.map { it.file.name }
    val chapters = if (withChapters.isNotEmpty() && withoutChapters.isNotEmpty()) {
        ChapterSplit(withChapters, withoutChapters).also { findings += ChapterFinding }
    } else {
        null
    }

    return CheckReport(
        headerLabel = headerLabel,
        hasConfig = selection.hasConfig,
        hasExternals = ok.any { externalsOf(it).isNotEmpty() },
        readable = ok.map { it.file.name },
        unreadable = unreadable,
        layouts = layouts,
        duplicates = duplicates,
        chapters = chapters,
        findings = findings,
    )
}

/**
 * A non-largest layout group is a structural outlier, blocking when the layout change lands on a
 * selected id — that is where mkvmerge would silently copy the wrong track.
 */
private fun layoutFinding(
    group: List<ProbeResult.Probed>,
    largest: List<ProbeResult.Probed>,
    externalsOf: (ProbeResult.Probed) -> Map<String, ExternalSlot>,
    selection: TrackSelection,
): LayoutFinding {
    val affected = selection.selectedIds
        .filter { id -> group[0].tracks[id]?.signature?.type != largest[0].tracks[id]?.signature?.type }
        .sorted()
    return LayoutFinding(
        fileCount = group.size,
        internalDiffers = internalLayoutKey(group[0]) != internalLayoutKey(largest[0]),
        externalsDiffer = externalLayoutKey(externalsOf(group[0])) != externalLayoutKey(externalsOf(largest[0])),
        affectedIds = affected,
        blocking = affected.isNotEmpty(),
    )
}

/** The distinct video-track titles at each id across [group], in file order. */
private fun videoNamesById(group: List<ProbeResult.Probed>): Map<Int, List<String>> {
    val ids = group.flatMapTo(LinkedHashSet()) { info ->
        info.tracks.values.filter { it.videoName != null }.map { it.id }
    }
    return ids.associateWith { id -> group.mapNotNull { it.tracks[id]?.videoName }.distinct() }
}
