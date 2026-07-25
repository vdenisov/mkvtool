package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * `mux`: one `mkvmerge` command per media file, built from `config.yaml`. A port of `src/mux.groovy`.
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

/**
 * The run root.
 *
 * **Returned but never emitted**, the same as `InspectReport`: v1 ends a mux with a bare `*** Done` and no
 * summary, so rendering this would be output v1 never had. The counts are computed rather than stored —
 * they cannot then disagree with the files they count.
 */
data class MuxRun(
    val files: List<FileMux>,
    val destinationDir: String,
    val trackOrder: String,
    val dryRun: Boolean,
) : CommandResult {
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

    /** `trackOrder` was omitted, so it follows from the configured tracks. Never has anything to warn about
     *  — it is generated from the very list it would be checked against. */
    data class Derived(override val order: String) : TrackOrder

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
    ) : TrackOrder
}

/** Everything one mux run was told, beyond the directory it runs in. */
data class MuxOptions(
    val config: Config,
    val mkvmergeExe: String,
    /** The spelling of `--ui-language` this mkvmerge accepts, or null to omit the option. */
    val uiLanguage: String?,
    val trackOrder: String,
    val substitution: SubstitutionEngine = SubstitutionEngine(),
    /** Whether any template uses `${'$'}{codec}`. False means no track is ever probed, so a config without
     *  it costs no subprocesses at all. */
    val usesCodec: Boolean = false,
    val dryRun: Boolean = false,
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
 * taken. A launch failure counts as a refusal, so a bogus `mkvmergeExe` fails at the mux rather than here.
 */
fun resolveUiLanguage(mkvmergeExe: String): String? =
    listOf("en", "en_US").firstOrNull { language ->
        try {
            ProcessBuilder(mkvmergeExe, "--ui-language", language, "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

/**
 * Why [config] cannot be muxed with, or null when it can.
 *
 * Only the fields the command line cannot be built without are here — this is not schema validation. Each
 * of them is something v1 dereferenced unconditionally and either crashed on or turned into the literal
 * text `null` (a directory named `null`, a `--language 0:null`), which no oracle case covers. Refusing up
 * front, in the same sentence shape an unreadable config gets, is the honest version of the same answer.
 *
 * Deliberately *not* here: a track with no `language` or no `title`. Both are genuinely optional — mkvmerge
 * keeps whatever the source track carries — so the builder omits the option instead of inventing a value.
 */
fun muxConfigProblem(config: Config): String? {
    if (config.general.destinationDir.isNullOrEmpty()) return "config.yaml has no general.destinationDir"

    for ((key, tracks) in listOf(
        "audioTracks" to config.mainSource.audioTracks,
        "subtitleTracks" to config.mainSource.subtitleTracks,
    )) {
        tracks.forEachIndexed { i, track ->
            // Without an id there is no track to select, name or order: every option mkvmerge would be
            // handed for it addresses a number that is not there.
            if (track.id == null) return "config.yaml has no id for mainSource.$key[$i]"
        }
    }

    config.additionalSources.forEachIndexed { i, source ->
        // A source with no file is nothing to mux: v1 threw on it, and passing mkvmerge an empty argument
        // between its parentheses would be a worse answer than saying so.
        if (source.file.isNullOrEmpty()) return "config.yaml has no file for additionalSources[$i]"
    }

    return null
}

/**
 * The order the config expresses: video first, then the audio and subtitle tracks in the order they are
 * listed, then one entry per additional source.
 *
 * One entry per *source*, not per track — a companion is a single-track file as mkvmerge sees it, so its
 * one track is always `0` and the source index is what distinguishes it.
 */
fun deriveTrackOrder(config: Config): String {
    val parts = ArrayList<String>()
    parts += "0:0"
    config.mainSource.audioTracks.forEach { parts += "0:${it.id}" }
    config.mainSource.subtitleTracks.forEach { parts += "0:${it.id}" }
    config.additionalSources.forEachIndexed { i, _ -> parts += "${i + 1}:0" }
    return parts.joinToString(",")
}

private val TRACK_ORDER_ENTRY = Regex("""^\d+:\d+$""")

/** [config]'s track order, derived when it names none and checked against the configured tracks when it
 *  does. */
fun resolveTrackOrder(config: Config): TrackOrder {
    val derived = deriveTrackOrder(config)
    val configured = config.trackOrder ?: return TrackOrder.Derived(derived)

    val entries = configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val wellFormed = entries.filter { TRACK_ORDER_ENTRY.matches(it) }

    return TrackOrder.Configured(
        order = configured,
        malformed = entries.filterNot { TRACK_ORDER_ENTRY.matches(it) },
        unknown = wellFormed - derived.split(',').toSet(),
        // Distinct, because v1 compared against a Set: two configured tracks sharing an id are one thing
        // the order can omit, not two.
        missing = derived.split(',').distinct() - entries.toSet(),
    )
}

/**
 * Builds the mkvmerge command line for one file, and owns the probe caches `${'$'}{codec}` needs.
 *
 * Substitution resolves **eagerly** here: the builder is called once per file, with that file's variables
 * already resolved, so nothing needs deferring the way v1's lazy GStrings did. Probing happens only when a
 * template actually asks for a codec, and [probe] is injected so the unit tier never launches mkvmerge.
 */
class MuxCommandBuilder(
    /** The media directory. Only the companion probe reads it — every path the command line itself
     *  carries stays relative, because mkvmerge is run with this as its working directory. */
    private val dir: File,
    private val options: MuxOptions,
    private val probe: (File) -> ProbeResult,
) {
    private val config = options.config
    private val mainProbes = HashMap<File, ProbeResult>()
    private val companionProbes = HashMap<String, ProbeResult?>()

    /** Where [file] will be written — always with a forward slash, and relative unless the config wrote an
     *  absolute `destinationDir`, exactly as v1 composed it. */
    fun outputPathFor(file: File): String = "${options.destinationDir}/${file.nameWithoutExtension}.mkv"

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
        val audio = config.mainSource.audioTracks
        val subtitles = config.mainSource.subtitleTracks
        command += if (audio.isEmpty()) listOf("--no-audio")
        else listOf("--audio-tracks", audio.mapNotNull { it.id }.joinToString(","))
        command += if (subtitles.isEmpty()) listOf("--no-subtitles")
        else listOf("--subtitle-tracks", subtitles.mapNotNull { it.id }.joinToString(","))

        command += config.mainSource.additionalOptions

        // The video track, always id 0. Its name defaults to the file name and is a different field from
        // the segment title below — many players conflate the two, but "Original Japanese" and the episode
        // title are worth setting separately.
        val video = config.mainSource.videoTrack
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
            command += trackNameOption(id, track) {
                expand(it, trackVarsFor(track.language, probedTrack(file, id)))
            }
            command += listOf("--default-track-flag", "$id:${flag(track.default)}")
        }

        for (track in subtitles) {
            val id = track.id ?: continue
            command += languageOption(id, track.language)
            command += trackNameOption(id, track) {
                expand(it, trackVarsFor(track.language, probedTrack(file, id)))
            }
            if (track.charset != null) command += listOf("--sub-charset", "$id:${track.charset}")
            command += listOf("--default-track-flag", "$id:${flag(track.default)}")
        }

        command += listOf("(", "$base.$extension", ")")

        for (source in config.additionalSources) {
            val path = expand(source.file)
            for (track in source.tracks) {
                // Always 0, whatever the config wrote: mkvmerge sees a companion as a single-track file,
                // and v1 hardcoded the id for that reason.
                command += languageOption(COMPANION_TRACK_ID, track.language)
                command += trackNameOption(COMPANION_TRACK_ID, track) {
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
            options.trackOrder,
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
    private fun trackNameOption(id: Int, track: TrackConfig, expand: (String?) -> String): List<String> {
        val title = track.title ?: return emptyList()
        return listOf("--track-name", "$id:${expand(title.text)}")
    }

    /** The probed record behind a configured track, for `${'$'}{codec}` alone. Normally already cached by
     *  the pre-flight; probed on demand when `--no-check` skipped it. */
    private fun probedTrack(file: File, trackId: Int): ProbedTrack? {
        if (!options.usesCodec) return null
        val info = mainProbes.getOrPut(file) { probe(file) }
        return (info as? ProbeResult.Probed)?.allTracks?.firstOrNull { it.id == trackId }
    }

    /** The same for a companion, which mkvmerge always reports as holding exactly track 0. */
    private fun companionTrack(path: String): ProbedTrack? {
        if (!options.usesCodec) return null
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
 * Mux every media file in [dir], reporting each as it completes.
 *
 * [runCommand] is injected so the orchestration is testable without mkvmerge; the default inherits the
 * child's streams, so mkvmerge's own progress reaches the console unchanged, and runs it with [dir] as the
 * working directory — the command line names its sources by bare relative name.
 *
 * Nothing here aborts. A file that is not media is named and passed over, a file mkvmerge rejects is
 * reported and the batch carries on, and the caller always exits 0.
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

    fun empty() = MuxRun(emptyList(), options.destinationDir, options.trackOrder, options.dryRun)

    // A mask that matches nothing must say so. Falling through to a bare "Done" after a typo'd pattern
    // looks exactly like a successful run that had no work to do.
    if (hasMasks && selection.matched.isEmpty()) {
        renderer.render(
            Advisory("*** No files match: ${maskDescription(options.fileMasks, options.excludeMasks)}"),
        )
        return empty()
    }

    // Matched something, but nothing that is media. A different message from the one above, because it is
    // a different mistake — and the same message `inspect` gives for the same situation.
    if (selection.selected.isEmpty()) {
        renderer.render(
            Advisory(noMediaMessage(options.allowedExtensions, options.fileMasks, options.excludeMasks)),
        )
        return empty()
    }

    // mkvmerge only creates a missing output directory in recent versions; older ones simply fail to open
    // the output file. Never on a dry run, which must leave the filesystem exactly as it found it.
    if (!options.dryRun) resolveAgainst(dir, options.destinationDir).mkdirs()

    val builder = MuxCommandBuilder(dir, options, probe)
    val results = ArrayList<FileMux>(selection.matched.size)

    for (file in selection.matched) {
        if (extensionOf(file.name) !in options.allowedExtensions) {
            results += FileMux(file.name, null, null, MuxOutcome.Skipped).also(renderer::render)
            continue
        }

        renderer.render(Header("*** Processing ${file.name}"))
        renderer.render(Notice(""))

        val command = builder.commandFor(file)
        val outputPath = builder.outputPathFor(file)

        val outcome = if (options.dryRun) {
            MuxOutcome.Previewed
        } else {
            val code = runCommand(command, dir)
            if (code == 0) MuxOutcome.Muxed else MuxOutcome.Failed(code)
        }

        results += FileMux(file.name, outputPath, command, outcome).also(renderer::render)
    }

    return MuxRun(results, options.destinationDir, options.trackOrder, options.dryRun)
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
