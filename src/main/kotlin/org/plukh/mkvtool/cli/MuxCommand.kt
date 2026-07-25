package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.Config
import org.plukh.mkvtool.core.EpisodeMetadata
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.MkvToolNotFoundException
import org.plukh.mkvtool.core.MuxOptions
import org.plukh.mkvtool.core.SubstitutionEngine
import org.plukh.mkvtool.core.collectTemplateFields
import org.plukh.mkvtool.core.findMkvTool
import org.plukh.mkvtool.core.loadConfig
import org.plukh.mkvtool.core.loadEpisodeMetadata
import org.plukh.mkvtool.core.muxConfigProblem
import org.plukh.mkvtool.core.muxDirectory
import org.plukh.mkvtool.core.probeFile
import org.plukh.mkvtool.core.reportTemplateProblems
import org.plukh.mkvtool.core.resolveTrackOrder
import org.plukh.mkvtool.core.resolveUiLanguage
import org.plukh.mkvtool.core.validateTemplates
import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.Success
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool mux` — mux every media file in the current directory per `config.yaml`. A port of
 * `src/mux.groovy`.
 *
 * The config lives with the media: `config.yaml` in the current directory, or wherever `--config` points.
 * There is deliberately **no** fall-back to a template shipped with the tool — applying a demo config's
 * track selections to an unrelated directory produced confidently wrong output, and a wrong title on the
 * check verdict was the only thing that gave it away.
 *
 * Exit codes: 2 for anything wrong with the config or the episode metadata, all of it before a single file
 * is touched; otherwise 0, even when files failed. A partially successful batch is a normal outcome — a
 * mux is per file, and the twenty-three episodes that worked are worth keeping.
 *
 * Inspecting files *without* a config — track tables, the batch structure report — is what `inspect` is
 * for. This command only muxes, and muxing always needs a config.
 */
@Command(
    name = "mux",
    mixinStandardHelpOptions = true,
    description = ["Mux MKV files from multiple sources using mkvmerge."],
)
class MuxCommand : Callable<Int> {

    @Option(
        names = ["-c", "--config"],
        paramLabel = "PATH",
        description = ["Path to the config file (default: config.yaml in the current directory)"],
    )
    var configPath: String? = null

    @Option(
        names = ["-n", "--dry-run"],
        description = ["Print the mkvmerge command line for every matching file without executing it"],
    )
    var dryRun: Boolean = false

    // These two are declared here and act only once the pre-flights exist: there is no check yet to
    // suppress and no finding yet to be strict about, so a run behaves today as though both were given.
    @Option(
        names = ["--no-check"],
        description = ["Skip the automatic pre-flight consistency check before muxing"],
    )
    var noCheck: Boolean = false

    @Option(
        names = ["--strict"],
        description = [
            "Abort instead of warning when the consistency check finds a discrepancy affecting a track " +
                "that config.yaml selects",
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
            "File names or glob patterns to process; may be given more than once " +
                "(default: every file in the current directory)",
        ],
    )
    var fileMasks: MutableList<String> = mutableListOf()

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer()
        val dir = File(".")

        val config = loadOrRefuse(dir, renderer) ?: return 2

        // Fields the command line cannot be built without. Refused in the same sentence shape an
        // unreadable config gets, because it is the same answer: this config cannot be muxed with.
        muxConfigProblem(config)?.let {
            renderer.render(Error("$it; there is nothing to mux with."))
            return 2
        }

        val mkvmergeExe = config.general.mkvmergeExe ?: try {
            findMkvTool("mkvmerge")
        } catch (e: MkvToolNotFoundException) {
            renderer.render(Error(e.message.orEmpty()))
            return 2
        }
        val uiLanguage = resolveUiLanguage(mkvmergeExe)

        // Episode metadata, read from the media directory only — mux never goes to the network. It is
        // hand-editable, so it fails the same ways the config does and gets the same clean exit: a title
        // stamped from metadata that could not be read is the same confidently-wrong output.
        val episodes = loadEpisodeMetadata(dir, offset = 1)
        if (episodes is EpisodeMetadata.Unusable) {
            renderer.render(Error("${episodes.message}; delete it or fix it."))
            return 2
        }

        // Stage one: a name that is not a variable, or not one legal in this field, is a config error —
        // fatal, before anything is probed or muxed, in every mode. A typo'd ${epsiodeName} would
        // otherwise be stamped verbatim into the track names of an entire season. What comes back is which
        // variables the config actually uses, so everything derived from them stays gated.
        val validation = validateTemplates(collectTemplateFields(config))
        reportTemplateProblems(validation, renderer, fatal = true)
        if (validation.problems > 0) return 2

        // Resolved once rather than per file, so its warnings are printed once.
        val trackOrder = resolveTrackOrder(config)
        renderer.render(trackOrder)

        muxDirectory(
            dir = dir,
            options = MuxOptions(
                config = config,
                mkvmergeExe = mkvmergeExe,
                uiLanguage = uiLanguage,
                trackOrder = trackOrder.order,
                substitution = SubstitutionEngine((episodes as? EpisodeMetadata.Loaded)?.data),
                usesCodec = validation.usesCodec,
                dryRun = dryRun,
                fileMasks = fileMasks,
                excludeMasks = excludeMasks,
            ),
            renderer = renderer,
            probe = { probeFile(it, mkvmergeExe) },
        )

        renderer.render(Notice(""))
        renderer.render(Success("*** Done"))
        return 0
    }

    /** The config, or null after saying why there is none to mux with. */
    private fun loadOrRefuse(dir: File, renderer: Renderer): Config? {
        val configFile = configPath?.let(::File) ?: File(dir, "config.yaml")

        if (configFile.isFile) {
            return when (val load = loadConfig(configFile)) {
                is MappingLoad.Loaded -> load.value
                is MappingLoad.Problem -> {
                    renderer.render(Error("${load.message}; there is nothing to mux with."))
                    null
                }
            }
        }

        if (configPath != null) {
            renderer.render(Error("Config file not found: ${configFile.absolutePath}"))
            return null
        }

        renderer.render(
            Error(
                "No config.yaml in the current directory (${dir.absoluteFile.parent}).",
                hint = "See the example config: $EXAMPLE_CONFIG_URL\n" +
                    "Write your own next to your media, or pass --config <path>.",
            ),
        )
        return null
    }

    private companion object {
        /**
         * A binary on `PATH` has no directory to point at the way the script pointed at its own, and the
         * build deliberately ships no classpath resources, so the template is named where it actually
         * lives rather than as a file to copy from somewhere.
         */
        const val EXAMPLE_CONFIG_URL =
            "https://github.com/vdenisov/mkvtool/blob/main/src/config.example.yaml"
    }
}
