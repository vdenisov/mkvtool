package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.EpisodeSource
import org.plukh.mkvtool.core.ShowNameResolved
import org.plukh.mkvtool.core.applyRenamePlan
import org.plukh.mkvtool.core.buildRenamePlan
import org.plukh.mkvtool.core.loadEpisodeSource
import org.plukh.mkvtool.core.sanitizeForFilename
import org.plukh.mkvtool.out.Error
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool rename` — rename episode files to `Show - SxxEyy - Title[suffix].ext` from `episodes.yaml`
 * (preferred) or `episodes.txt`.
 *
 * Both metadata files carry names exactly as TheMovieDB spells them, `:` and `?` included, so that `mux`
 * can put the real spelling into a title. Making a name safe for a *file* name is this command's job
 * alone, and it happens here rather than at fetch time because a name stripped there could never be
 * recovered.
 *
 * Exit codes: 2 when there is no metadata or no show name (before anything is touched), 1 when the plan
 * is refused or a rename fails, else 0.
 */
@Command(
    name = "rename",
    mixinStandardHelpOptions = true,
    description = ["Rename episode files to 'Show - SxxEyy - Title.ext' using episodes.yaml (preferred) or episodes.txt."],
)
class RenameCommand : Callable<Int> {

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Show name. Optional when episodes.yaml supplies one"],
    )
    var showName: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        defaultValue = "1",
        description = [
            "Episode number of the first line of episodes.txt. " +
                "Applies to that file only; episodes.yaml carries real episode numbers",
        ],
    )
    var episodeOffset: Int = 1

    @Option(names = ["-n", "--dry-run"], description = ["Print planned renames without touching any files"])
    var dryRun: Boolean = false

    @Option(
        names = ["--external"],
        description = [
            "Also rename external files (dubs, subtitles) that belong to the renamed episodes, " +
                "wherever they live, keeping each one's own suffix and directory",
        ],
    )
    var renameExternal: Boolean = false

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer()
        val dir = File(".")

        val source = when (val loaded = loadEpisodeSource(dir, episodeOffset)) {
            is EpisodeSource.Loaded -> loaded
            is EpisodeSource.Problem -> {
                renderer.render(Error(loaded.message, hint = loaded.hint))
                return 2
            }
        }

        // Sanitizing is idempotent, so a hand-written episodes.txt already free of those characters
        // passes through untouched.
        val episodeNames = source.data.byEpisode.mapValues { (_, name) -> sanitizeForFilename(name) }

        val show = showName?.takeIf { it.isNotEmpty() } ?: run {
            val fromMetadata = sanitizeForFilename(source.data.show)
            if (fromMetadata.isEmpty()) {
                renderer.render(Error("No show name: pass one as the first argument, or fetch episodes.yaml first"))
                return 2
            }
            renderer.render(ShowNameResolved(fromMetadata, source.name))
            fromMetadata
        }

        val plan = buildRenamePlan(dir, show, episodeNames, source.name, renameExternal)
        renderer.render(plan)

        // One problem anywhere refuses the batch: the plan's whole point is that nothing is touched until
        // all of it checks out.
        if (plan.problems.isNotEmpty()) return 1
        if (plan.entries.isEmpty()) return 0

        val run = applyRenamePlan(plan, dryRun, renderer)
        return if (run.failed > 0) 1 else 0
    }
}
