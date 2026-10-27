package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.plural
import java.io.File
import java.util.Locale

/**
 * `mux`: one `mkvmerge` command per media file, built from `config.yaml`.
 *
 * The command line is the whole product — everything else in this file exists to get one built correctly
 * and then to run it. What goes into it is a human decision recorded in the config; this only executes it,
 * which is why nothing here guesses at a track and why an id the config names is used exactly as written.
 *
 * The batch is **continue-on-error and always exits 0**: a season where one episode fails to mux is a
 * normal outcome, and stopping would leave the other twenty-three unmuxed for no gain. The exits that do
 * happen are all *before* the batch — a config that could not be read or understood — because muxing
 * against a config this did not understand is exactly the confidently-wrong output the whole design
 * refuses to produce.
 */

/** What happened to one file the batch walked over. */
sealed interface MuxOutcome {
    /** Not one of the allowed extensions: named and passed over. Nothing was built for it. */
    data object Skipped : MuxOutcome

    /** `--dry-run`: the command was built and shown, and nothing ran. */
    data object Previewed : MuxOutcome

    data object Muxed : MuxOutcome

    /** mkvmerge exited non-zero. Its own diagnosis already reached the console. */
    data class Failed(val exitCode: Int) : MuxOutcome
}

/**
 * One file's result, carrying its own identity.
 *
 * [command] is the argv exactly as mkvmerge received it — the dry run's whole answer, and the field a
 * machine-readable mode exists to expose. Both it and [outputPath] are null for a skipped file, where
 * nothing was built.
 */
data class FileMux(
    val fileName: String,
    val outputPath: String?,
    val command: List<String>?,
    val outcome: MuxOutcome,
) : CommandResult

/** One file-scope variable a template asked for, and the episodes no source could supply it for. */
data class UnresolvedVariable(val name: String, val fileNames: List<String>)

/**
 * Episodes dropped because a variable their titles use has no value for them — validation's **second**
 * stage.
 *
 * The split from stage one is the point. A name that is not a variable at all is config-static and affects
 * every file, so it is fatal before anything is read; a valid variable with no data for episode 25 is
 * *data*-shaped — TheMovieDB missing an entry, a stray file with no episode number in its name — so that
 * episode is dropped and the rest of the season still muxes. Exactly the companion pre-flight's philosophy,
 * which is why the two sit together and read alike.
 *
 * [episodeSource] is the metadata file the batch had, or null when there was none — worth saying outright,
 * since "no episodes.yaml or episodes.txt here" explains every unresolved variable at once.
 */
data class SubstitutionDrops(
    val variables: List<UnresolvedVariable>,
    val fileNames: List<String>,
    val episodeSource: String?,
) : CommandResult

/** One `additionalSources` pattern, and the episodes it resolves to nothing for. [pattern] is the template
 *  as the config wrote it, since that is the line a reader has to go and look at. */
data class MissingCompanion(val pattern: String, val fileNames: List<String>)

/**
 * Episodes dropped because a companion file they need is not there.
 *
 * Never an abort, `--strict` included: a dub studio releasing 22 of 24 episodes is an ordinary situation,
 * and those two episodes would have failed in mkvmerge anyway — partway through a long batch, which is the
 * whole reason to check up front instead.
 */
data class CompanionDrops(
    val sources: List<MissingCompanion>,
    val fileNames: List<String>,
) : CommandResult

/**
 * Why the batch stopped before anything was written.
 *
 * One leaf per verdict, because each is reached at its own moment and says what it found. The first two are
 * `--strict`'s; binding problems and output collisions stop every run, since muxing past either writes
 * confidently wrong files.
 */
sealed interface MuxAbort : CommandResult {
    /** Stage two found episodes it could not resolve a title for. */
    data class UnresolvedVariables(val fileCount: Int) : MuxAbort

    /** The pre-flight check found discrepancies on tracks the config selects by id. */
    data class BlockingDiscrepancies(val count: Int) : MuxAbort

    /** Binding found [count] problems, which the [BindingReport] emitted before it lists. */
    data class Unbound(val count: Int) : MuxAbort

    /** Several files would be written to one output: each such path, with the files resolving to it. */
    data class OutputCollisions(val byPath: Map<String, List<String>>) : MuxAbort
}

/** One muxing pass: a group's gates and the files it walked, which are empty when a gate stopped the batch or
 *  left nothing to run. */
data class MuxPassRun(
    /** Null for the implicit group. */
    val group: GroupRef?,
    val trackOrder: String,
    val files: List<FileMux>,
    /** Null when nothing was dropped — not an empty report, which would claim the stage ran and found
     *  nothing to say. Same for [companionDrops]. */
    val substitutionDrops: SubstitutionDrops? = null,
    val companionDrops: CompanionDrops? = null,
    /** Null under `--no-check`: the field is empty because nothing computed it, not because a flag
     *  reshaped the document. */
    val check: CheckReport? = null,
)

/**
 * The run root.
 *
 * **Returned but never emitted**, the same as `InspectReport`: v1 ends a mux with a bare `*** Done` and no
 * summary, so rendering this would be output v1 never had. The counts are computed rather than stored —
 * they cannot then disagree with the files they count.
 */
data class MuxRun(
    /** One per group with files in the run, in config order, up to the one that stopped the batch. */
    val passes: List<MuxPassRun>,
    val destinationDir: String,
    val dryRun: Boolean,
    /** A grouped run's non-media files, which no group claims; the implicit group's ride along in its pass. */
    val nonMedia: List<FileMux> = emptyList(),
    /** Null when the run stopped before binding: no file matched, or none was media. */
    val binding: BindingResult? = null,
    /** Set only when the batch was stopped; the caller turns it into exit 2. */
    val aborted: MuxAbort? = null,
) : CommandResult {
    val files: List<FileMux> = nonMedia + passes.flatMap { it.files }
    val muxed: Int = files.count { it.outcome is MuxOutcome.Muxed }
    val failed: Int = files.count { it.outcome is MuxOutcome.Failed }
    val skipped: Int = files.count { it.outcome is MuxOutcome.Skipped }
    val previewed: Int = files.count { it.outcome is MuxOutcome.Previewed }
}

/**
 * How the output track order was arrived at, and what did not line up.
 *
 * Two leaves rather than one type with a flag, because they answer different questions: a derived order is
 * an announcement (this is what the config implies), a configured one is a verification (this is what you
 * wrote, and here is what is wrong with it). Each gets its own renderer.
 */
sealed interface TrackOrder : CommandResult {
    val order: String

    /** The group whose order this is; null for the implicit group. */
    val group: GroupRef?

    /** `trackOrder` was omitted, so it follows from the configured tracks. Never has anything to warn about
     *  — it is generated from the very list it would be checked against. */
    data class Derived(override val order: String, override val group: GroupRef? = null) : TrackOrder

    /**
     * `trackOrder` was written in the config and is used exactly as written.
     *
     * The three lists are warnings, never failures: mkvmerge silently ignores an entry matching no muxed
     * track, so a stale order fails quietly, and saying so is the whole point — but an existing config that
     * works today must keep working.
     */
    data class Configured(
        override val order: String,
        /** Entries that are not `sourceIndex:trackId` at all. */
        val malformed: List<String>,
        /** Well-formed entries naming a track the config does not configure. */
        val unknown: List<String>,
        /** Configured tracks the order does not place. They are still muxed, just wherever mkvmerge puts
         *  them. */
        val missing: List<String>,
        override val group: GroupRef? = null,
    ) : TrackOrder
}

/** Everything one mux run was told, beyond the directory it runs in. */
data class MuxOptions(
    val config: Config,
    val mkvmergeExe: String,
    /** The spelling of `--ui-language` this mkvmerge accepts, or null to omit the option. */
    val uiLanguage: String?,
    val substitution: SubstitutionEngine = SubstitutionEngine(),
    /** Which metadata file supplied the episode data, or null when there was none. Stage two says so. */
    val episodeSource: String? = null,
    val dryRun: Boolean = false,
    /** The pre-flight consistency check, wanted unless `--no-check` says otherwise. */
    val check: Boolean = true,
    /** Turn the pre-flights' findings into an abort instead of a report. */
    val strict: Boolean = false,
    val fileMasks: List<String> = emptyList(),
    val excludeMasks: List<String> = emptyList(),
) {
    val allowedExtensions: Set<String> =
        config.general.allowedExtensions ?: DEFAULT_ALLOWED_EXTENSIONS

    /** Non-null by the time a run starts: [muxConfigProblem] refuses the config otherwise. */
    val destinationDir: String = config.general.destinationDir.orEmpty()
}

/**
 * The `--ui-language` spelling this mkvmerge understands, or null when neither works.
 *
 * mkvmerge's UI language codes changed across versions — `en_US` up to at least v82, `en` afterwards — so
 * the accepted spelling is probed rather than assumed, and the option is dropped entirely when neither is
 * taken. A launch failure counts as a refusal.
 */
fun resolveUiLanguage(mkvmergeExe: String): String? =
    listOf("en", "en_US").firstOrNull { runs(mkvmergeExe, "--ui-language", it, "--version") }

/**
 * Why [config] cannot be muxed with, or null when it can. The reason starts with [configName], the config
 * file as the user named it.
 *
 * Only fields the command line cannot be built without, or would have to ignore, are here — this is not
 * schema validation. The first kind v1 dereferenced unconditionally and either crashed on or turned into
 * the literal text `null` (a directory named `null`, a `--language 0:null`); the second is an `id` on an
 * additional-source track, which can only look like a decision. Refusing up front, in the same sentence
 * shape an unreadable config gets, is the honest version of the same answer.
 *
 * Deliberately *not* here: a track with no `language` or no `title`. Both are genuinely optional — mkvmerge
 * keeps whatever the source track carries — so the builder omits the option instead of inventing a value.
 */
fun muxConfigProblem(config: Config, configName: String): String? {
    if (config.general.destinationDir.isNullOrEmpty()) return "$configName has no general.destinationDir"
    groupProblems(config, configName).firstOrNull()?.let { return it }
    bindingProblems(config, configName).firstOrNull()?.let { return it }

    config.groups.forEachIndexed { g, group ->
        val prefix = config.writtenPrefix(g) { it.mainSource }
        for ((key, tracks) in listOf(
            "audioTracks" to group.mainSource.audioTracks,
            "subtitleTracks" to group.mainSource.subtitleTracks,
        )) {
            tracks.forEachIndexed { i, track ->
                // Without an id there is no track to select, name or order: every option mkvmerge would be
                // handed for it addresses a number that is not there.
                if (track.id == null) {
                    return "$configName has no id for ${withRef("${prefix}mainSource.$key[$i]", track.ref)}"
                }
            }
        }

        group.additionalSources.forEachIndexed { i, source ->
            // A source with no file is nothing to mux: v1 threw on it, and passing mkvmerge an empty argument
            // between its parentheses would be a worse answer than saying so.
            // A source with files: names none on purpose; binding pairs one with each main.
            if (source.file.isNullOrEmpty() && source.files == null) {
                return "$configName has no file for ${config.writtenPrefix(g) { it.additionalSources }}" +
                    groupSourcePath(i)
            }
        }
    }

    additionalSourceIdProblems(config, configName).firstOrNull()?.let { return it }
    return null
}

/**
 * The order [group] expresses: video first, then the audio and subtitle tracks in the order they are
 * listed, then one entry per additional source.
 *
 * One entry per *source*, not per track — a companion is a single-track file as mkvmerge sees it, so its
 * one track is always `0` and the source index is what distinguishes it.
 */
fun deriveTrackOrder(group: GroupConfig): String {
    val parts = ArrayList<String>()
    parts += "0:0"
    group.mainSource.audioTracks.forEach { parts += "0:${it.id}" }
    group.mainSource.subtitleTracks.forEach { parts += "0:${it.id}" }
    group.additionalSources.forEachIndexed { i, _ -> parts += "${i + 1}:0" }
    return parts.joinToString(",")
}

private val TRACK_ORDER_ENTRY = Regex("""^\d+:\d+$""")

/** [group]'s track order, derived when it names none and checked against the configured tracks when it
 *  does. [ref] names the group in what is reported, null for the implicit one. */
fun resolveTrackOrder(group: GroupConfig, ref: GroupRef? = null): TrackOrder {
    val derived = deriveTrackOrder(group)
    val configured = group.trackOrder ?: return TrackOrder.Derived(derived, ref)

    val entries = configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val wellFormed = entries.filter { TRACK_ORDER_ENTRY.matches(it) }

    return TrackOrder.Configured(
        order = configured,
        malformed = entries.filterNot { TRACK_ORDER_ENTRY.matches(it) },
        unknown = wellFormed - derived.split(',').toSet(),
        // Distinct, because v1 compared against a Set: two configured tracks sharing an id are one thing
        // the order can omit, not two.
        missing = derived.split(',').distinct() - entries.toSet(),
        group = ref,
    )
}

/**
 * Builds the mkvmerge command line for one file of one group's pass, and owns the probe caches `${'$'}{codec}`
 * needs. The tracks and sources are the group's; the output name, the title and the destination are `general`'s.
 *
 * Substitution resolves **eagerly** here: the builder is called once per file, with that file's variables
 * already resolved, so nothing needs deferring the way v1's lazy GStrings did. Probing happens only when a
 * template actually asks for a codec, and [probe] is injected so the unit tier never launches mkvmerge.
 *
 * **[probe] is expected to memoize**, because the caller owns the batch and has usually read these files
 * already — the pre-flight check probes every one of them. A builder holding its own cache would read a
 * season twice over a network share for the sake of `${'$'}{codec}`. Companions are the exception and are
 * cached here: nothing else reads them.
 */
class MuxCommandBuilder(
    /** The media directory. Only the companion probe reads it — every path the command line itself
     *  carries stays relative, because mkvmerge is run with this as its working directory. */
    private val dir: File,
    private val options: MuxOptions,
    /** The index in the config of the group whose selection the command lines carry. */
    private val groupIndex: Int,
    /** The group's track order, resolved. */
    private val trackOrder: String,
    private val probe: (File) -> ProbeResult,
    /** The batch's binding, which says what each `files:` source's files paired with. */
    private val binding: BindingResult,
    /** Whether any of the group's templates uses `${'$'}{codec}`. False means no track is ever probed, so a group
     *  without it costs no subprocesses at all. */
    private val usesCodec: Boolean = false,
) {
    val config: Config = options.config
    val group: GroupConfig = config.groups[groupIndex]
    val substitution: SubstitutionEngine = options.substitution
    private val companionProbes = HashMap<String, ProbeResult?>()

    /**
     * Where [file] will be written — always with a forward slash, and relative unless the config wrote an
     * absolute `destinationDir`, exactly as v1 composed it. The name is `general.outputName` resolved for
     * [file] and made safe for a file name, or the main file's own base name.
     */
    fun outputPathFor(file: File): String {
        val name = config.general.outputName?.let {
            sanitizeForFilename(substitute(it.text.orEmpty(), options.substitution.fileVarsFor(file).vars))
        } ?: file.nameWithoutExtension
        return "${options.destinationDir}/$name.mkv"
    }

    /** The config path of the group's additional source [index]. */
    fun sourcePath(index: Int): String = config.sourcePath(groupIndex, index)

    /** The file binding paired [file] with for `files:` source [index], or null when it paired none. */
    fun boundFileFor(index: Int, file: File): BoundFile? = binding.boundFile(sourcePath(index), file)

    fun commandFor(file: File): List<String> {
        val base = file.nameWithoutExtension
        val extension = extensionOf(file.name)
        val fileVars = options.substitution.fileVarsFor(file).vars

        fun expand(template: String?, extra: Map<String, String?> = emptyMap()): String =
            substitute(template.orEmpty(), if (extra.isEmpty()) fileVars else fileVars + extra)

        val command = ArrayList<String>()
        command += options.mkvmergeExe
        if (options.uiLanguage != null) command += listOf("--ui-language", options.uiLanguage)
        command += listOf("--priority", PRIORITY, "--output", outputPathFor(file))

        // An absent list and an empty one mean the same thing, and it is not "all of them": the config
        // says which tracks to keep, so saying nothing keeps nothing.
        val audio = group.mainSource.audioTracks
        val subtitles = group.mainSource.subtitleTracks
        command += if (audio.isEmpty()) listOf("--no-audio")
        else listOf("--audio-tracks", audio.mapNotNull { it.id }.joinToString(","))
        command += if (subtitles.isEmpty()) listOf("--no-subtitles")
        else listOf("--subtitle-tracks", subtitles.mapNotNull { it.id }.joinToString(","))

        command += group.mainSource.additionalOptions

        // The video track, always id 0. Its name defaults to the file name and is a different field from
        // the segment title below — many players conflate the two, but "Original Japanese" and the episode
        // title are worth setting separately.
        val video = group.mainSource.videoTrack
        command += languageOption(0, video?.language)
        command += listOf(
            "--track-name",
            "0:" + when (val title = video?.title) {
                null -> base
                else -> expand(title.text, trackVarsFor(video.language, probedTrack(file, 0)))
            },
        )

        // Two loops rather than one over the concatenation: only a subtitle track takes a `--sub-charset`,
        // and v1 never emitted one for an audio track however the config was written.
        for (track in audio) {
            val id = track.id ?: continue
            command += languageOption(id, track.language)
            command += trackNameOption(id, track.title) {
                expand(it, trackVarsFor(track.language, probedTrack(file, id)))
            }
            command += listOf("--default-track-flag", "$id:${flag(track.default)}")
        }

        for (track in subtitles) {
            val id = track.id ?: continue
            command += languageOption(id, track.language)
            command += trackNameOption(id, track.title) {
                expand(it, trackVarsFor(track.language, probedTrack(file, id)))
            }
            if (track.charset != null) command += listOf("--sub-charset", "$id:${track.charset}")
            command += listOf("--default-track-flag", "$id:${flag(track.default)}")
        }

        command += listOf("(", "$base.$extension", ")")

        group.additionalSources.forEachIndexed { i, source ->
            // A pair goes on as its primary half alone: mkvmerge finds the partner beside it.
            val path = if (source.files != null) {
                boundFileFor(i, file)?.entry?.relPath.orEmpty()
            } else {
                expand(source.file)
            }
            for (track in source.tracks) {
                // Always 0, and the config cannot set it: mkvmerge sees a companion as a single-track file.
                command += languageOption(COMPANION_TRACK_ID, track.language)
                command += trackNameOption(COMPANION_TRACK_ID, track.title) {
                    expand(it, trackVarsFor(track.language, companionTrack(path)))
                }
                if (track.charset != null) {
                    command += listOf("--sub-charset", "$COMPANION_TRACK_ID:${track.charset}")
                }
                command += listOf("--default-track-flag", "$COMPANION_TRACK_ID:${flag(track.default)}")
            }
            command += source.additionalOptions
            command += listOf("(", path, ")")
        }

        command += listOf(
            "--title",
            config.general.title?.let { expand(it.text) } ?: base,
            "--track-order",
            trackOrder,
        )

        return command
    }

    /**
     * A track's name option, or nothing at all when the config declares no title.
     *
     * A **declared but empty** `title:` is a different thing and still emits the option with an empty
     * value, which clears whatever name the source track carried — that distinction is what [Template]
     * exists for. v1 had no third case here: it called `toString()` on the absent title and threw.
     */
    private fun trackNameOption(id: Int, title: Template?, expand: (String?) -> String): List<String> {
        if (title == null) return emptyList()
        return listOf("--track-name", "$id:${expand(title.text)}")
    }

    /** The probed record behind a configured track, for `${'$'}{codec}` alone. Normally already read by
     *  the pre-flight; probed on demand when `--no-check` skipped it. */
    private fun probedTrack(file: File, trackId: Int): ProbedTrack? {
        if (!usesCodec) return null
        return (probe(file) as? ProbeResult.Probed)?.allTracks?.firstOrNull { it.id == trackId }
    }

    /** The same for a companion, which mkvmerge always reports as holding exactly track 0. */
    private fun companionTrack(path: String): ProbedTrack? {
        if (!usesCodec) return null
        val info = companionProbes.getOrPut(path) {
            resolveAgainst(dir, path).takeIf { it.isFile }?.let(probe)
        }
        return (info as? ProbeResult.Probed)?.allTracks?.firstOrNull { it.id == COMPANION_TRACK_ID }
    }

    private companion object {
        /** v1 hardcodes it, so muxing never competes with playback for the machine. */
        const val PRIORITY = "lower"
        const val COMPANION_TRACK_ID = 0

        /**
         * A track's language, or nothing when the config names none.
         *
         * Omitting is what "not configured" has to mean: mkvmerge then keeps the source track's own tag,
         * where v1 emitted the literal text `null` and mkvmerge rejected the whole file.
         */
        fun languageOption(id: Int, language: String?): List<String> =
            if (language.isNullOrEmpty()) emptyList() else listOf("--language", "$id:$language")

        fun flag(value: Boolean): String = if (value) "yes" else "no"
    }
}

/**
 * Mux every media file in [dir], reporting each as it completes. [options]' config has passed
 * [muxConfigProblem].
 *
 * Six gates run before anything is written, in this order. The masks and binding are the batch's; stage-two
 * substitution, the companion pre-flight, output collisions and the consistency check are each pass's, over one
 * group's selection and the files it claims. There is one pass per group with files in the run, in config order,
 * and a file no group claims is in none. Every pass's gates run before the output directory is created and
 * before any pass runs, so a fully blocked batch leaves no litter behind.
 *
 * A binding problem, an output collision and `--strict` stop the run, by
 * returning [MuxRun.aborted] rather than by throwing. Otherwise nothing here aborts: a file that is not media is
 * named and passed over, a file mkvmerge rejects is reported, the batch carries on, and the caller exits 0.
 *
 * [runCommand] is injected so the orchestration is testable without mkvmerge; the default inherits the
 * child's streams, so mkvmerge's own progress reaches the console unchanged, and runs it with [dir] as the
 * working directory — the command line names its sources by bare relative name.
 */
fun muxDirectory(
    dir: File,
    options: MuxOptions,
    renderer: Renderer,
    probe: (File) -> ProbeResult,
    runCommand: (List<String>, File) -> Int = ::runMkvmerge,
): MuxRun {
    val selection = selectMedia(dir, options.allowedExtensions, options.fileMasks, options.excludeMasks)
    val hasMasks = options.fileMasks.isNotEmpty() || options.excludeMasks.isNotEmpty()

    var binding: BindingResult? = null

    // A run that stops early still reports everything that made it stop, its passes' reports included.
    fun stopped(passes: List<MuxPassRun> = emptyList(), aborted: MuxAbort? = null) = MuxRun(
        passes = passes,
        destinationDir = options.destinationDir,
        dryRun = options.dryRun,
        binding = binding,
        aborted = aborted,
    )

    // A mask that matches nothing must say so. Falling through to a bare "Done" after a typo'd pattern
    // looks exactly like a successful run that had no work to do.
    if (hasMasks && selection.matched.isEmpty()) {
        renderer.render(
            Advisory("*** No files match: ${maskDescription(options.fileMasks, options.excludeMasks)}"),
        )
        return stopped()
    }

    // Matched something, but nothing that is media. A different message from the one above, because it is
    // a different mistake — and the same message `inspect` gives for the same situation.
    if (selection.selected.isEmpty()) {
        renderer.render(
            Advisory(noMediaMessage(options.allowedExtensions, options.fileMasks, options.excludeMasks)),
        )
        return stopped()
    }

    // Every main, or a masked-out `X 2.mkv` would hand `X 2.srt` to `X.mkv`; the episode checks take the run.
    val config = options.config
    // Only a files: source reads the tree, and on a network share the walk is the slow part.
    val walks = config.groups.any { group -> group.additionalSources.any { it.files != null } }
    val tree = if (walks) walkTree(dir, excludedPaths(dir, config)) else emptyList()
    val bound = bindFiles(dir, config, selection.all, tree, options.substitution.episodeData, run = selection.selected)
    binding = bound
    // Reported only as far as this run reaches: a masked-out episode was never asked for.
    renderer.render(BindingReport(bound.narrowedTo(selection.matched.toSet()), fatal = true))
    if (bound.problems.isNotEmpty()) {
        val abort = MuxAbort.Unbound(bound.problems.size)
        renderer.render(abort)
        return stopped(aborted = abort)
    }
    val batchOptions = options.copy(substitution = options.substitution.boundTo(bound))

    // `mkvmerge -J` over a season takes a couple of seconds where muxing takes minutes per file, so the
    // check is essentially free and runs by default. The probes it fills are the same ones `${'$'}{codec}`
    // reads in the builder, which is why the cache is the batch's: a file is read once, whichever pass reads it.
    val probeCache = HashMap<File, ProbeResult>()
    val probeCached = { file: File -> probeCache.getOrPut(file) { probe(file) } }

    fun pass(index: Int, files: List<File>) = MuxPass(
        index = index,
        group = config.groupRef(index),
        trackOrder = resolveTrackOrder(config.groups[index]).order,
        // A variable only another group's templates use cannot drop this group's episode, nor make it probe.
        templates = validateTemplates(collectTemplateFields(config, index)),
        files = files,
    )
    // The implicit group's pass walks every matched file, non-media riding along to be named in place. A declared
    // group's walks its own members; non-media belong to no group, so they are named once before the passes.
    val passes: List<MuxPass>
    val nonMedia: List<File>
    if (config.declaredGroups == null) {
        passes = listOf(pass(0, selection.matched))
        nonMedia = emptyList()
    } else {
        val run = selection.selected.toSet()
        passes = bound.partition.members.mapIndexedNotNull { g, members ->
            members.filter { it in run }.takeIf { it.isNotEmpty() }?.let { pass(g, it) }
        }
        nonMedia = selection.matched - run
    }

    fun label(n: Int, group: GroupRef) = "*** Pass ${n + 1} of ${passes.size}: ${groupLabel(group)}"

    // Outputs the passes prepared so far will write, so a later group cannot write over an earlier one's.
    val outputs = HashMap<String, MutableList<Pair<String, String>>>()
    val preps = ArrayList<PassPrep>()
    for ((n, pass) in passes.withIndex()) {
        pass.group?.let { renderer.render(Header("${label(n, it)}, ${plural(pass.files.size, "file")}")) }
        val prep = preparePass(dir, pass, batchOptions, bound, probeCached, outputs, renderer)
        preps += prep
        if (prep is PassPrep.Stopped) return stopped(passes.zip(preps) { p, r -> report(p, r) }, prep.abort)
    }

    // The gates emptied the batch: said outright, since a bare green "Done" would read as success.
    if (preps.none { it is PassPrep.Ready }) {
        renderer.render(Advisory("*** Nothing left to mux"))
        return stopped(passes.zip(preps) { p, r -> report(p, r) })
    }

    // mkvmerge only creates a missing output directory in recent versions; older ones simply fail to open
    // the output file. Never on a dry run, which must leave the filesystem exactly as it found it — and
    // after every pass's gates, so a fully blocked batch leaves no empty directory behind.
    if (!options.dryRun) resolveAgainst(dir, options.destinationDir).mkdirs()

    val skipped = nonMedia.map { FileMux(it.name, null, null, MuxOutcome.Skipped).also(renderer::render) }
    val runs = passes.zip(preps).mapIndexed { n, (pass, prep) ->
        if (prep !is PassPrep.Ready) return@mapIndexed report(pass, prep)
        pass.group?.let { renderer.render(Header(label(n, it))) }
        report(pass, prep, runPass(dir, prep, batchOptions, renderer, runCommand))
    }

    return MuxRun(
        passes = runs,
        destinationDir = options.destinationDir,
        dryRun = options.dryRun,
        nonMedia = skipped,
        binding = binding,
    )
}

/**
 * One muxing pass: group [index] of the config, named by [group] when the config declares groups, with its
 * resolved [trackOrder], what its templates use ([templates]), and the [files] it walks.
 */
private data class MuxPass(
    val index: Int,
    val group: GroupRef?,
    val trackOrder: String,
    val templates: TemplateValidation,
    val files: List<File>,
)

/** What [pass] reports, its gates' findings from [prep] and the [files] it walked. */
private fun report(pass: MuxPass, prep: PassPrep, files: List<FileMux> = emptyList()) = MuxPassRun(
    group = pass.group,
    trackOrder = pass.trackOrder,
    files = files,
    substitutionDrops = prep.substitutionDrops,
    companionDrops = prep.companionDrops,
    check = prep.check,
)

/** A pass after its gates: what they reported, and whether anything is left to run. */
private sealed interface PassPrep {
    val substitutionDrops: SubstitutionDrops?
    val companionDrops: CompanionDrops?
    val check: CheckReport?

    /** Files survived every gate: [files] is what the run walks, [builder] what builds their command lines. */
    data class Ready(
        val builder: MuxCommandBuilder,
        val files: List<File>,
        override val substitutionDrops: SubstitutionDrops?,
        override val companionDrops: CompanionDrops?,
        override val check: CheckReport?,
    ) : PassPrep

    /** The drops left no media file. */
    data class Empty(
        override val substitutionDrops: SubstitutionDrops?,
        override val companionDrops: CompanionDrops?,
    ) : PassPrep {
        override val check: CheckReport? get() = null
    }

    /** A gate stopped the batch with [abort], already rendered. */
    data class Stopped(
        val abort: MuxAbort,
        override val substitutionDrops: SubstitutionDrops?,
        override val companionDrops: CompanionDrops?,
        override val check: CheckReport?,
    ) : PassPrep
}

/**
 * Runs [pass]'s gates — stage-two substitution, the companion pre-flight, output collisions and the check — and
 * says what is left to run. [outputs] holds what earlier passes will write, and gains this pass's. Writes nothing;
 * each gate renders its report as it finds it.
 */
private fun preparePass(
    dir: File,
    pass: MuxPass,
    options: MuxOptions,
    binding: BindingResult,
    probe: (File) -> ProbeResult,
    outputs: MutableMap<String, MutableList<Pair<String, String>>>,
    renderer: Renderer,
): PassPrep {
    // The gates drop episodes by name, so the pass shrinks as they run. Non-media files ride along in it: the
    // run reports them as skipped, and nothing can drop them.
    var files = pass.files
    fun media() = files.filter { extensionOf(it.name) in options.allowedExtensions }

    val substitutionDrops = substitutionDropsFor(media(), pass.templates.usedFileVars, options)
    if (substitutionDrops != null) {
        if (options.strict) {
            val abort = MuxAbort.UnresolvedVariables(substitutionDrops.fileNames.size)
            renderer.render(abort)
            return PassPrep.Stopped(abort, substitutionDrops, null, null)
        }
        renderer.render(substitutionDrops)
        val names = substitutionDrops.fileNames.toSet()
        files = files.filterNot { it.name in names }
        if (media().isEmpty()) return PassPrep.Empty(substitutionDrops, null)
    }

    val builder =
        MuxCommandBuilder(dir, options, pass.index, pass.trackOrder, probe, binding, pass.templates.usesCodec)

    // Never strict, unlike stage two: a dub studio releasing 22 of 24 episodes is ordinary, and the two
    // that are missing would have failed in mkvmerge anyway.
    val companionDrops = companionDropsFor(dir, media(), builder)
    if (companionDrops != null) {
        renderer.render(companionDrops)
        val names = companionDrops.fileNames.toSet()
        files = files.filterNot { it.name in names }
        if (media().isEmpty()) return PassPrep.Empty(substitutionDrops, companionDrops)
    }

    // After the drops, since a dropped episode writes nothing; mkvmerge would overwrite one output with the next,
    // whichever pass wrote the first.
    val collisions = outputCollisions(media(), builder, outputs)
    if (collisions.isNotEmpty()) {
        val abort = MuxAbort.OutputCollisions(collisions)
        renderer.render(abort)
        return PassPrep.Stopped(abort, substitutionDrops, companionDrops, null)
    }

    if (!options.check) return PassPrep.Ready(builder, files, substitutionDrops, companionDrops, null)

    val mediaFiles = media()
    val meter = renderer.progress("*** Reading ${plural(mediaFiles.size, "file")}", mediaFiles.size)
    val infos = mediaFiles.map { probe(it).also { _ -> meter.tick() } }
    meter.finish()
    renderer.render(Notice(""))

    // No externals: `mux` discovers nothing, so every external path in the report is inert here and it
    // renders exactly as `inspect`'s does bar the header.
    val check = buildCheckReport(
        infos = infos,
        selection = trackSelectionOf(builder.group.mainSource),
        headerLabel = "Pre-flight check",
    )
    renderer.render(check)

    if (check.blockingCount > 0 && options.strict) {
        val abort = MuxAbort.BlockingDiscrepancies(check.blockingCount)
        renderer.render(abort)
        return PassPrep.Stopped(abort, substitutionDrops, companionDrops, check)
    }
    return PassPrep.Ready(builder, files, substitutionDrops, companionDrops, check)
}

/** Builds and runs, or on a dry run shows, the command line for each media file [ready] left, in name order. */
private fun runPass(
    dir: File,
    ready: PassPrep.Ready,
    options: MuxOptions,
    renderer: Renderer,
    runCommand: (List<String>, File) -> Int,
): List<FileMux> = ready.files.map { file ->
    if (extensionOf(file.name) !in options.allowedExtensions) {
        return@map FileMux(file.name, null, null, MuxOutcome.Skipped).also(renderer::render)
    }

    renderer.render(Header("*** Processing ${file.name}"))
    renderer.render(Notice(""))

    val command = ready.builder.commandFor(file)
    val outputPath = ready.builder.outputPathFor(file)

    val outcome = if (options.dryRun) {
        MuxOutcome.Previewed
    } else {
        val code = runCommand(command, dir)
        if (code == 0) MuxOutcome.Muxed else MuxOutcome.Failed(code)
    }

    FileMux(file.name, outputPath, command, outcome).also(renderer::render)
}

/**
 * Stage two: which episodes a template's variables have no value for, or null when every one resolves.
 *
 * Only the variables the pass's templates actually use are checked ([usedFileVars]) — a season with no show
 * name in its metadata is nobody's problem if no template asks for one.
 */
private fun substitutionDropsFor(
    mediaFiles: List<File>,
    usedFileVars: Set<String>,
    options: MuxOptions,
): SubstitutionDrops? {
    if (usedFileVars.isEmpty()) return null

    val byVariable = LinkedHashMap<String, MutableList<String>>()
    val blocked = LinkedHashSet<String>()

    for (file in mediaFiles) {
        val missing = options.substitution.fileVarsFor(file).missing intersect usedFileVars
        missing.forEach { byVariable.getOrPut(it) { ArrayList() } += file.name }
        if (missing.isNotEmpty()) blocked += file.name
    }

    if (blocked.isEmpty()) return null
    return SubstitutionDrops(
        variables = byVariable.map { (name, names) -> UnresolvedVariable(name, names) },
        fileNames = blocked.toList(),
        episodeSource = options.episodeSource,
    )
}

/**
 * The companion pre-flight: which episodes an `additionalSources` pattern resolves to nothing for, or a
 * `files:` source paired no file with, or null when every companion is there. A `files:` source is named by
 * its masks, under its path within the group: the pass's label already names the group.
 */
private fun companionDropsFor(dir: File, mediaFiles: List<File>, builder: MuxCommandBuilder): CompanionDrops? {
    val sources = builder.group.additionalSources
    if (sources.isEmpty()) return null

    val bySource = LinkedHashMap<String, MutableList<String>>()
    val blocked = LinkedHashSet<String>()

    for (file in mediaFiles) {
        val vars = builder.substitution.fileVarsFor(file).vars
        sources.forEachIndexed { i, source ->
            val masks = source.files
            val (pattern, present) = if (masks != null) {
                "${groupSourcePath(i)}.files: ${masks.joinToString(", ")}" to
                    (builder.boundFileFor(i, file) != null)
            } else {
                val template = source.file ?: return@forEachIndexed
                template to resolveAgainst(dir, substitute(template, vars)).isFile
            }
            if (!present) {
                bySource.getOrPut(pattern) { ArrayList() } += file.name
                blocked += file.name
            }
        }
    }

    if (blocked.isEmpty()) return null
    return CompanionDrops(
        sources = bySource.map { (pattern, names) -> MissingCompanion(pattern, names) },
        fileNames = blocked.toList(),
    )
}

/**
 * Each output path more than one file resolves to, with those files' names: [mediaFiles] against each other and
 * against [outputs], the paths earlier passes will write (lower-cased, to each as written and the file writing
 * it), which [mediaFiles]' outputs are added to. Compared ignoring case on every platform: Windows and macOS file
 * systems ignore it by default, and an output may be copied to one.
 */
private fun outputCollisions(
    mediaFiles: List<File>,
    builder: MuxCommandBuilder,
    outputs: MutableMap<String, MutableList<Pair<String, String>>>,
): Map<String, List<String>> {
    val written = LinkedHashSet<String>()
    for (file in mediaFiles) {
        val path = builder.outputPathFor(file)
        val key = path.lowercase(Locale.ROOT)
        outputs.getOrPut(key) { ArrayList() } += path to file.name
        written += key
    }
    return written.map { outputs.getValue(it) }
        .filter { it.size > 1 }
        .associate { files -> files.first().first to files.map { it.second } }
}

/** The real runner: mkvmerge with its streams inherited, in [dir]. */
private fun runMkvmerge(command: List<String>, dir: File): Int {
    val process = ProcessBuilder(command).directory(dir).inheritIO().start()
    return try {
        RunningMux.track(process)
        process.waitFor()
    } finally {
        RunningMux.release()
    }
}

/**
 * The mkvmerge currently running, so a JVM being shut down takes it with it.
 *
 * v1's shutdown hook, and it earns its keep on a long batch: muxing one episode takes minutes, and a mux
 * left running after the tool is gone keeps writing a file nobody is waiting for. The message goes
 * straight to stdout rather than through a renderer because there is no renderer at shutdown — the same
 * reason v1 used a bare `println` there.
 */
private object RunningMux {

    @Volatile
    private var process: Process? = null

    private var hookInstalled = false

    @Synchronized
    fun track(started: Process) {
        if (!hookInstalled) {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    process?.let {
                        println("*** Killing mkvtoolnix process ${it.pid()}")
                        it.destroy()
                    }
                },
            )
            hookInstalled = true
        }
        process = started
    }

    fun release() {
        process = null
    }
}
