package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.plural
import java.io.File

/**
 * `inspect`: everything that reports rather than muxes. A port of `src/inspect.groovy`.
 *
 * One spine serves both modes. Discovery, the single probing pass, the external legend and the leftovers
 * run whatever was asked for; `--identify` and the consistency check are two consumers hanging off the
 * middle of it. That is why they port together — splitting them would mean writing the spine twice.
 *
 * Nothing here can fail the run. A config is optional throughout, an unreadable file is a line in the
 * report, and a missing companion is a note; `--strict` is the caller saying "treat what you found as a
 * failure" and is the only path to a non-zero exit. That rule is load-bearing rather than politeness: a
 * stale `config.yaml` must not stand between the reader and the track table, least of all when reading
 * that table is how they would fix the config.
 */

/** One media file as `--identify` describes it. */
data class FileIdentification(
    val fileName: String,
    val listing: TrackListing,
    /**
     * The `additionalSources` this episode resolves to. Empty when the config declares none **and** when
     * the run never asked to identify — the seam's compute-gating: the field is empty because nothing
     * computed it, not because a flag reshaped the document.
     */
    val configuredSources: List<ConfiguredSource>,
    /** One entry per variant contributing to this episode, ordered as the legend labels them. */
    val externals: List<VariantExternals>,
) : CommandResult

/** What a container turned out to hold. */
sealed interface TrackListing {
    /** mkvmerge could not use the file; [reason] is its own phrase, verbatim. */
    data class Unreadable(val reason: String) : TrackListing

    /** The tracks in mkvmerge's own order — empty when the container holds none. */
    data class Tracks(val tracks: List<ProbedTrack>) : TrackListing
}

/**
 * One `additionalSources` entry, resolved for one episode.
 *
 * [path] is the substituted string exactly as the template produced it, never normalised through a
 * `File`: with templated paths, what a pattern expands to per episode is half of what one came to see,
 * and separators the config wrote by hand should read back as they were written.
 */
data class ConfiguredSource(val path: String, val listing: SourceListing)

sealed interface SourceListing {
    /** Not on disk. Never fatal here — `--identify` describes what exists rather than asserting it. */
    data object Missing : SourceListing

    data class Unreadable(val reason: String) : SourceListing

    data class Tracks(val tracks: List<ProbedTrack>) : SourceListing
}

/**
 * What one discovered variant contributes to one episode.
 *
 * Grouped by variant rather than by file because that is what the thing *is*: a merged variant handing
 * this episode a dub and a subtitle file is one dub group with two files, and reads as one block with its
 * extensions named in the header.
 */
data class VariantExternals(val variant: VariantIdentity, val files: List<ExternalFile>) {
    /** The distinct extensions, in the order the files are listed rather than sorted — that order is the
     *  release's own layout, and the block header says so. */
    val extensions: List<String> = files.map { it.extension }.distinct()
}

/** One discovered file, and what reading it (or declining to) yielded. */
data class ExternalFile(
    val relPath: String,
    /** Lower-cased, no dot. Carried rather than re-derived: discovery split the name once already. */
    val extension: String,
    val tier: MatchTier,
    val listing: ExternalListing,
) {
    val fileName: String = relPath.substringAfterLast('/')
}

sealed interface ExternalListing {
    /**
     * Said outright rather than folded into the never-probed path, where a truncated `.mka` would print
     * as the healthiest row in the table — and surfacing exactly that is what `--identify` is for.
     */
    data class Unreadable(val reason: String) : ExternalListing

    /** Empty only for a file that was probed and holds nothing; an unprobed file always has its one
     *  synthetic row. */
    data class Tracks(val tracks: List<ExternalTrack>) : ExternalListing
}

/**
 * One display row for an external file. A probed value wins field by field; what is missing falls back to
 * the extension for the codec and to the variant's language guess.
 *
 * Unlike [ProbedTrack] this carries resolved values rather than nullable raw ones, because the check
 * groups episodes by them: these fields become an `ExternalSlot` signature, and a value the renderer
 * invented would be a value the grouping never saw. That is why the `?` fallback for a missing [type] or
 * [codec] is composed here rather than in a renderer.
 *
 * [language] is the language itself either way — the tag the file carries, or the variant's guess when it
 * carries none — and null when neither says anything. **Where it came from is [guessed], and that is the
 * only place it lives**: the `rus?` a report shows is composed by the renderer from the two. A guessed
 * `rus` and a tagged `rus` are the same language, and a directory where some files are tagged and some are
 * not is ordinary (a dub group that started tagging mid-season), so marking the string would split such a
 * batch into two values that differ in provenance only. A genuine mis-tag — `jpn` inside `Rus sound` —
 * still differs, because the languages themselves do.
 *
 * [default] and [forced] are null for an unprobed file: not "no", *unknown*.
 */
data class ExternalTrack(
    val id: Int,
    val type: String,
    val codec: String,
    val language: String?,
    val guessed: Boolean,
    val default: Boolean?,
    val forced: Boolean?,
    val name: String,
)

/**
 * The legend printed once above the per-file tables: which label means which variant, where its files
 * live and how many there are.
 *
 * It belongs to both modes, not just `--identify`: the check report labels its external rows `[A]`, `[B]`
 * too, and a label whose key was never printed is a reference to nothing.
 */
data class ExternalLegend(val rows: List<LegendRow>) : CommandResult {
    val variantCount: Int = rows.map { it.variant.label }.distinct().size
}

/**
 * One variant × one kind of file, so a merged variant shows both under the same label.
 *
 * [dir] is the directory the path pattern anchors on, null at the top level, and [suffix] the text it
 * extends a main file's name with — null for a section holding only episode-number matches, which have no
 * name to extend. The pattern string itself is the renderer's to compose.
 */
data class LegendRow(
    val variant: VariantIdentity,
    val type: CompanionType,
    val dir: String?,
    val suffix: String?,
    val extensions: List<String>,
    val fileCount: Int,
)

/** What the walk found that belongs to nothing. Names only: never probed, never treated as sources,
 *  listed so their presence is not a surprise. */
data class ExternalLeftovers(val unmatched: List<String>, val extras: List<String>) : CommandResult {
    val isEmpty: Boolean = unmatched.isEmpty() && extras.isEmpty()
}

/** Why `--strict` is about to exit 2. Emitted only when it fires. */
data class StrictVerdict(val blockingCount: Int, val configProblems: Int) : CommandResult

/**
 * Everything one inspection found, and the document a machine-readable mode would serialize.
 *
 * **Returned but never emitted.** The children are the whole output — v1 prints no batch summary — so
 * emitting this as well would print them a second time. A command that wants something unshown simply
 * does not emit it, which is why no renderer is bound to this type.
 */
data class InspectReport(
    /** The batch after the masks, in inspection order. Empty means there was nothing to inspect. */
    val mediaFiles: List<String>,
    /** Empty in a check-only run: nothing computed them. */
    val files: List<FileIdentification>,
    val legend: ExternalLegend,
    val leftovers: ExternalLeftovers,
    /** Null in an `--identify`-only run. */
    val check: CheckReport?,
    val configProblems: Int,
) : CommandResult {
    val blockingCount: Int get() = check?.blockingCount ?: 0
}

/**
 * What one inspection was asked for. [configProblems] is counted by the caller — the config file plus
 * template validation — and travels here because the root document reports it; episode-metadata problems
 * are deliberately not in it, since metadata produces no findings and only decorates resolved paths.
 */
data class InspectOptions(
    val identify: Boolean,
    /** The consistency check, wanted by default. Consumed by the check stage. */
    val check: Boolean,
    val config: Config? = null,
    val configProblems: Int = 0,
    val substitution: SubstitutionEngine = SubstitutionEngine(),
    val fileMasks: List<String> = emptyList(),
    val excludeMasks: List<String> = emptyList(),
) {
    val allowedExtensions: Set<String> =
        config?.general?.allowedExtensions ?: DEFAULT_ALLOWED_EXTENSIONS
}

/**
 * Inspect [dir] and report what is there.
 *
 * [probe] is injected so the unit tier never launches mkvmerge; the command passes
 * `{ probeFile(it, mkvmergeExe) }`. Results are emitted through [renderer] as they complete and the whole
 * document is returned.
 */
fun inspectDirectory(
    dir: File,
    options: InspectOptions,
    renderer: Renderer,
    probe: (File) -> ProbeResult,
): InspectReport {
    val selection = selectMedia(dir, options.allowedExtensions, options.fileMasks, options.excludeMasks)

    if (selection.selected.isEmpty()) {
        renderer.render(
            Advisory(noMediaMessage(options.allowedExtensions, options.fileMasks, options.excludeMasks)),
        )
        renderer.render(Notice(""))
        return InspectReport(
            mediaFiles = emptyList(),
            files = emptyList(),
            legend = ExternalLegend(emptyList()),
            leftovers = ExternalLeftovers(emptyList(), emptyList()),
            check = null,
            configProblems = options.configProblems,
        )
    }

    // Discovery first: pure name work, no subprocesses, and its answer decides which files are worth
    // probing. It runs over every media file, *before* the masks — matching against the masked list would
    // dump the rest of the season's dubs into "unmatched", the exact opposite of what a mask asks for.
    val discovered = discoverCompanions(
        mains = selection.all,
        tree = walkTree(dir, excludedPaths(dir, options.config)),
        mainExtensions = options.allowedExtensions,
    )

    // One probe per file, keyed canonically so the same file reached by two different paths — a companion
    // that is also a configured source, which the canonical `${fileName}[Studio].mka` layout produces
    // every run — is read once.
    val probeCache = HashMap<String, ProbeResult?>()
    val probeCached = { file: File ->
        probeCache.getOrPut(pathKey(file)) { if (file.isFile) probe(file) else null }
    }

    val probeWorthy = discovered.variants
        .flatMap { it.entries }
        .filter { it.entry.ext in PROBE_EXTENSIONS && it.main in selection.selected }
        .distinctBy { pathKey(it.entry.file) }

    // Only --identify resolves them, so a check-only run costs no substitution and no extra subprocess.
    val configuredSources: Map<File, List<ResolvedSource>> =
        if (options.identify) selection.selected.associateWith { resolveSources(dir, it, options) }
        else emptyMap()

    val companionProbes = (probeWorthy.map { it.entry.file } +
        configuredSources.values.flatten().map { it.file }.filter { it.isFile })
        .distinctBy { pathKey(it) }

    // Everything costing a subprocess goes into one meter, so its total is the real one and nothing runs
    // on after it says it has finished. A season over a slow share is seconds of silence otherwise.
    val label = "*** Reading ${plural(selection.selected.size, "file")}" +
        if (companionProbes.isEmpty()) "" else " + ${plural(companionProbes.size, "companion file")}"
    val meter = renderer.progress(label, selection.selected.size + companionProbes.size)

    val infos = LinkedHashMap<File, ProbeResult>()
    selection.selected.forEach { file ->
        infos[file] = probe(file).also { probeCache[pathKey(file)] = it }
        meter.tick()
    }
    companionProbes.forEach { probeCached(it); meter.tick() }
    meter.finish()
    renderer.render(Notice(""))

    // Built from the cache alone: everything below is already probed, which is what keeps the meter honest.
    val externalsByFile = selection.selected.associateWith { main ->
        externalsFor(discovered, main) { file, ext -> probeExternal(file, ext, probeCache) }
    }

    val legend = ExternalLegend(legendRowsOf(discovered))
    renderer.render(legend)

    // Precomputed, because the report asks for a file's externals once per grouping, once per layout for
    // the labels and the findings, and once per file *per slot id* while grouping — a closure that
    // rebuilt them each time would be the hot path.
    val check = if (options.check) {
        val slotsByFile = externalsByFile.mapValues { (_, externals) -> externalSlotsFor(externals) }
        buildCheckReport(
            infos = selection.selected.map { infos.getValue(it) },
            externalsOf = { slotsByFile[it.file].orEmpty() },
            selection = trackSelectionOf(options.config),
            headerLabel = "Consistency check",
        ).also(renderer::render)
    } else {
        null
    }

    val files = if (options.identify) {
        selection.selected.map { file ->
            FileIdentification(
                fileName = file.name,
                listing = listingOf(infos.getValue(file)),
                configuredSources = configuredSources[file].orEmpty().map { it.toResult(probeCache) },
                externals = externalsByFile.getValue(file),
            ).also(renderer::render)
        }
    } else {
        emptyList()
    }

    val leftovers = ExternalLeftovers(
        unmatched = discovered.unmatched.map { it.relPath },
        extras = discovered.extras.map { it.relPath },
    )
    renderer.render(leftovers)

    return InspectReport(
        mediaFiles = selection.selected.map { it.name },
        files = files,
        legend = legend,
        leftovers = leftovers,
        check = check,
        configProblems = options.configProblems,
    )
}

/**
 * The rows for one external file: what its own tracks say, or what its name says when nothing read it.
 *
 * [probed] is null when the extension is not worth a subprocess (or the file is gone), which is the
 * common case — a `.srt` carries no metadata at all, so its name is the only source there ever was.
 */
fun externalRowsOf(ext: String, languageGuess: String?, probed: ProbeResult?): ExternalListing {
    if (probed is ProbeResult.Failed) return ExternalListing.Unreadable(probed.reason)

    val tracks = (probed as? ProbeResult.Probed)?.allTracks
    if (tracks == null) {
        // mkvmerge numbers the single track of a raw .ass or .srt as 0, and that is the id an
        // additionalSources entry has to name, so print it rather than dashing it out for not having
        // been probed.
        return ExternalListing.Tracks(
            listOf(
                ExternalTrack(
                    id = 0,
                    type = typeClassOf(ext).label,
                    codec = codecOf(ext),
                    language = languageGuess,
                    guessed = languageGuess != null,
                    default = null,
                    forced = null,
                    name = "",
                ),
            ),
        )
    }

    return ExternalListing.Tracks(
        tracks.map { track ->
            // 'und' counts as missing. Matroska has no other way to say "untagged", and an untagged .mka
            // is the common case here — a directory of Russian dubs where three of five report 'und'
            // says nothing the directory name did not already say.
            val language = track.language?.takeIf { it.isNotEmpty() && it != "und" }
            ExternalTrack(
                id = track.id,
                type = track.type.orIfEmpty("?"),
                codec = track.codec.orIfEmpty("?"),
                language = language ?: languageGuess,
                guessed = language == null && languageGuess != null,
                default = track.default,
                forced = track.forced,
                name = track.trackName.orEmpty(),
            )
        },
    )
}

/**
 * One episode's external files as slots the consistency check can group by, exactly as it groups the
 * tracks inside a container.
 *
 * The key is the variant, the kind of file and its **extension** — never an index, since external files
 * have no order and what a reader follows through a season is "does this episode have the [Омикрон] dub".
 * The extension is in it because one variant can hand a single episode two files of the same kind (a group
 * shipping both `.ass` and `.srt` for one episode and only `.ass` for the next): keyed on the kind alone
 * they collide, the second silently overwrites the first, and two episodes come out looking like one pass.
 * It also separates a variant that switched format mid-season, which is a real second pass — mkvmerge is
 * being handed a different file.
 *
 * A file mkvmerge could not read still occupies its slot. The episode has it, and pretending otherwise
 * would move that episode into a different muxing group over a defect reported elsewhere.
 */
fun externalSlotsFor(externals: List<VariantExternals>): Map<String, ExternalSlot> {
    val slots = LinkedHashMap<String, ExternalSlot>()

    for (variant in externals) {
        for (file in variant.files) {
            val type = typeClassOf(file.extension)
            val row = (file.listing as? ExternalListing.Tracks)?.tracks?.firstOrNull()
            slots["${variant.variant.label}/${type.label}/${file.extension}"] = ExternalSlot(
                key = "${variant.variant.label}/${type.label}/${file.extension}",
                signature = TrackSignature(
                    // The kind comes from the extension, not from the row: an unreadable file has no row
                    // and still belongs to the audio or the subtitle side of its variant.
                    type = type.label,
                    codec = row?.codec ?: codecOf(file.extension),
                    language = row?.language ?: "-",
                    name = row?.name.orEmpty(),
                    default = row?.default == true,
                    forced = row?.forced == true,
                ),
                guessed = row?.guessed ?: false,
                variant = variant.variant,
            )
        }
    }

    return slots
}

/** The legend's rows: one per variant, per kind of file it holds. */
fun legendRowsOf(discovered: DiscoveryResult): List<LegendRow> =
    discovered.variants.flatMap { variant ->
        val identity = VariantIdentity(variant)
        variant.sections.map { section ->
            LegendRow(
                variant = identity,
                type = section.type,
                dir = section.dir,
                suffix = section.suffix,
                extensions = section.extensions,
                fileCount = section.entries.size,
            )
        }
    }

/** What `typeClassOf` is called in a report — v1's own `'audio'` / `'subtitles'`. */
val CompanionType.label: String get() = name.lowercase()

/** What an unprobed file's codec column says: its format, named from its extension. */
private fun codecOf(ext: String): String = CODEC_BY_EXTENSION[ext] ?: ext.uppercase()

/** Everything discovered for one episode, grouped by variant and ordered as the legend labels them. */
private fun externalsFor(
    discovered: DiscoveryResult,
    main: File,
    probedOf: (File, String) -> ProbeResult?,
): List<VariantExternals> {
    data class Hit(val variant: Variant, val entry: CompanionEntry)

    val hits = discovered.variants
        .flatMap { variant -> variant.entries.filter { it.main == main }.map { Hit(variant, it) } }
        // Sorted on the two keys concatenated rather than as a pair, as `src/inspect.groovy` does. The
        // two agree below 27 variants, which is as far as labels stay one character wide.
        .sortedBy { "${it.variant.label}${it.entry.entry.relPath}" }

    return hits.groupBy { it.variant.label }.map { (_, group) ->
        VariantExternals(
            variant = VariantIdentity(group.first().variant),
            files = group.map { hit ->
                ExternalFile(
                    relPath = hit.entry.entry.relPath,
                    extension = hit.entry.entry.ext,
                    tier = hit.entry.tier,
                    listing = externalRowsOf(
                        ext = hit.entry.entry.ext,
                        languageGuess = hit.variant.languageGuess,
                        probed = probedOf(hit.entry.entry.file, hit.entry.entry.ext),
                    ),
                )
            },
        )
    }
}

/** An `additionalSources` path resolved for one episode, before it was read. */
private data class ResolvedSource(val path: String, val file: File) {
    fun toResult(cache: Map<String, ProbeResult?>): ConfiguredSource = ConfiguredSource(
        path = path,
        listing = when (val probed = cache[pathKey(file)]) {
            null -> SourceListing.Missing
            is ProbeResult.Failed -> SourceListing.Unreadable(probed.reason)
            is ProbeResult.Probed -> SourceListing.Tracks(probed.allTracks)
        },
    )
}

private fun resolveSources(dir: File, main: File, options: InspectOptions): List<ResolvedSource> =
    options.config?.additionalSources.orEmpty()
        .mapNotNull { it.file }
        .map { template ->
            val path = substitute(template, options.substitution.fileVarsFor(main).vars)
            ResolvedSource(path, resolveAgainst(dir, path))
        }

/** Only the formats worth a subprocess are ever read; everything else describes itself from its name. */
private fun probeExternal(file: File, ext: String, cache: Map<String, ProbeResult?>): ProbeResult? =
    if (ext in PROBE_EXTENSIONS) cache[pathKey(file)] else null

private fun listingOf(probed: ProbeResult): TrackListing = when (probed) {
    is ProbeResult.Failed -> TrackListing.Unreadable(probed.reason)
    is ProbeResult.Probed -> TrackListing.Tracks(probed.allTracks)
}

/**
 * The output directory, excluded by canonical path: muxed results carry their sources' base names and
 * would otherwise come back as external files of themselves.
 */
private fun excludedPaths(dir: File, config: Config?): Set<String> {
    val destination = config?.general?.destinationDir?.takeIf { it.isNotEmpty() } ?: return emptySet()
    val resolved = resolveAgainst(dir, destination)
    return if (resolved.isDirectory) setOf(canonicalOrAbsolute(resolved)) else emptySet()
}

/** The identity two references to one file share. Canonical, so `x` and `./x` are the same file — which
 *  is what stops a configured source that is also a discovered companion being read twice. */
private fun pathKey(file: File): String = canonicalOrAbsolute(file)

private fun canonicalOrAbsolute(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

private fun String?.orIfEmpty(fallback: String): String = if (isNullOrEmpty()) fallback else this
