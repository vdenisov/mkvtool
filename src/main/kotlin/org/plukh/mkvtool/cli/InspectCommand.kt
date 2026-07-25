package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.Config
import org.plukh.mkvtool.core.EpisodeMetadata
import org.plukh.mkvtool.core.InspectOptions
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.MkvToolNotFoundException
import org.plukh.mkvtool.core.StrictVerdict
import org.plukh.mkvtool.core.SubstitutionEngine
import org.plukh.mkvtool.core.collectTemplateFields
import org.plukh.mkvtool.core.findMkvTool
import org.plukh.mkvtool.core.inspectDirectory
import org.plukh.mkvtool.core.loadConfig
import org.plukh.mkvtool.core.loadEpisodeMetadata
import org.plukh.mkvtool.core.probeFile
import org.plukh.mkvtool.core.reportTemplateProblems
import org.plukh.mkvtool.core.validateTemplates
import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.RenderHints
import org.plukh.mkvtool.out.Success
import org.plukh.mkvtool.out.Warning
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool inspect` — per-file track tables and a batch consistency report. A port of
 * `src/inspect.groovy`.
 *
 * It writes nothing and **exits 0 whatever it finds**. A config is optional throughout: missing, empty,
 * malformed or merely stale, each is a warning and the run carries on, because reading the track table is
 * how one would fix the config in the first place. `--strict` is the only path to a non-zero exit — the
 * caller saying "treat what you found as a failure", which is the one context in which a report is also
 * a verdict.
 *
 * That is the opposite of `mux`'s policy on the same files, for the same reason `mux` has it: `mux`
 * cannot mux against a config it did not understand, and this command is not about to change anything.
 */
@Command(
    name = "inspect",
    mixinStandardHelpOptions = true,
    description = [
        "Inspect media files: per-file track tables and a batch consistency report. " +
            "Never modifies anything.",
    ],
)
class InspectCommand : Callable<Int> {

    @Option(
        names = ["-c", "--config"],
        paramLabel = "PATH",
        description = [
            "Path to a config file (default: config.yaml in the current directory if present). " +
                "Optional: with one, findings are classified against the tracks it selects and " +
                "configured sources are resolved per episode",
        ],
    )
    var configPath: String? = null

    @Option(
        names = ["--identify"],
        description = ["Print a track table for every matching file (default mode is --check)"],
    )
    var identifyOnly: Boolean = false

    @Option(
        names = ["--check"],
        description = [
            "Compare track structure across all matching files (the default when no mode is given; " +
                "name it explicitly to combine with --identify)",
        ],
    )
    var checkOnly: Boolean = false

    @Option(
        names = ["--check-verbose"],
        description = ["List every file in the consistency report instead of truncating long lists"],
    )
    var checkVerbose: Boolean = false

    @Option(
        names = ["--strict"],
        description = [
            "Exit 2 when the consistency check finds a discrepancy affecting a track that the config selects",
        ],
    )
    var strict: Boolean = false

    @Option(
        names = ["-x", "--exclude"],
        paramLabel = "PATTERN",
        description = ["File name or glob pattern to skip; may be given more than once"],
    )
    var excludeMasks: MutableList<String> = mutableListOf()

    @Parameters(
        index = "0..*",
        arity = "0..*",
        paramLabel = "FILE",
        description = [
            "File names or glob patterns to inspect; may be given more than once " +
                "(default: every media file in the current directory)",
        ],
    )
    var fileMasks: MutableList<String> = mutableListOf()

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer(RenderHints(verboseFileLists = checkVerbose))
        val dir = File(".")

        var configProblems = 0
        val configFile = configPath?.let(::File) ?: File(dir, "config.yaml")
        var config: Config? = null

        if (configFile.isFile) {
            when (val load = loadConfig(configFile)) {
                is MappingLoad.Loaded -> config = load.value
                is MappingLoad.Problem -> {
                    renderer.render(
                        Warning(
                            "${load.message}; continuing without it.",
                            // Aligned under "*** Warning: " so the two read as one thought.
                            hint = "***          Findings will not be classified against selected tracks.",
                        ),
                    )
                    configProblems++
                }
            }
        } else if (configPath != null) {
            renderer.render(Warning("Config file not found: ${configFile.absolutePath} - continuing without it."))
            configProblems++
        }

        // Episode metadata only decorates the source paths --identify resolves; it produces no findings,
        // so an unusable one is a warning and deliberately *not* a config problem. --strict must not fail
        // over something the report never classified anything against.
        val episodes = loadEpisodeMetadata(dir, offset = 1)
        if (episodes is EpisodeMetadata.Unusable) {
            renderer.render(Warning("${episodes.message}; continuing without episode metadata."))
        }
        val episodeData = (episodes as? EpisodeMetadata.Loaded)?.data

        // Diagnosed, never fatal — the same validation `mux` exits 2 on. A template typo would put a
        // wrong name on a whole season there; here it only means a path resolves to less than it should.
        val validation = validateTemplates(collectTemplateFields(config))
        reportTemplateProblems(validation, renderer, fatal = false)
        configProblems += validation.problems

        val mkvmergeExe = config?.general?.mkvmergeExe ?: try {
            findMkvTool("mkvmerge")
        } catch (e: MkvToolNotFoundException) {
            renderer.render(Error(e.message.orEmpty()))
            return 2
        }

        val report = inspectDirectory(
            dir = dir,
            options = InspectOptions(
                identify = identifyOnly,
                check = wantCheck(identifyOnly, checkOnly, checkVerbose),
                config = config,
                configProblems = configProblems,
                substitution = SubstitutionEngine(episodeData),
                fileMasks = fileMasks,
                excludeMasks = excludeMasks,
            ),
            renderer = renderer,
            probe = { probeFile(it, mkvmergeExe) },
        )

        // An empty batch has already returned its own advisory, and there is nothing for strictness to be
        // strict about — v1 returns before this point in that case, so a broken config there still exits 0.
        if (strict && report.mediaFiles.isNotEmpty() &&
            (report.blockingCount > 0 || report.configProblems > 0)
        ) {
            renderer.render(StrictVerdict(report.blockingCount, report.configProblems))
            return 2
        }

        renderer.render(Success("*** Done"))
        return 0
    }
}

/**
 * Whether to run the consistency check.
 *
 * The batch report is the default mode, since it is the question one usually arrives with, and
 * `--identify` is the per-file drill-down that replaces it. Naming both runs both. `--check-verbose` is
 * simply a modifier here — there is nothing else it could have meant.
 */
internal fun wantCheck(identifyOnly: Boolean, checkOnly: Boolean, checkVerbose: Boolean): Boolean =
    checkOnly || checkVerbose || !identifyOnly
