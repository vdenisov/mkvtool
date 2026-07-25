package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * `rename`: episode files to the canonical `Show - SxxEyy - Title[suffix].ext`, from `episodes.yaml`
 * (preferred) or `episodes.txt`. A port of `src/rename.groovy`.
 *
 * Renaming is destructive and the SxxEyy token is the only thing tying a file to its episode, so the plan
 * is built in full and checked before anything is touched: a failure halfway through would leave a
 * directory that has to be untangled by hand. One problem anywhere refuses the whole batch.
 */

/** What `rename` will touch. Wider than the main-file set: a companion in this directory is renamed too. */
val RENAMEABLE_EXTENSIONS = setOf("mkv", "mp4", "avi", "srt", "ass", "mks", "idx", "sub", "mka")

/**
 * What counts as a main file when looking for the external files that belong to one. Narrower than
 * [RENAMEABLE_EXTENSIONS] on purpose: an `.mka` in this directory is some episode's companion, not an
 * episode in its own right.
 */
private val MAIN_EXTENSIONS = setOf("mkv", "mp4", "avi")

/** A trailing `[Dub Studio]`, which identifies which group a file belongs to and so must survive a rename. */
private val TRAILING_BRACKET_SUFFIX = Regex("""(\[.+\])$""")

/** Episode metadata, or why there is none to work from. */
sealed interface EpisodeSource {
    /** [name] is the file it came from, which is what a "no title for episode 12" problem names. */
    data class Loaded(val data: EpisodeData, val name: String) : EpisodeSource

    data class Problem(val message: String, val hint: String? = null) : EpisodeSource
}

/**
 * Read `episodes.yaml`, else `episodes.txt`, from [dir].
 *
 * The yaml wins when present: it carries real episode numbers, so a season with a gap stays aligned, and
 * it supplies the show name. [offset] applies to the text file **alone** — that file has no numbers in it,
 * so the offset is part of how it is read, while the yaml needs no shifting and must not get one.
 *
 * Charsets differ by design: the yaml is machine-written and read back as explicit UTF-8, while the text
 * file is auto-detected because it is the format a human types (Notepad on a Cyrillic Windows writes
 * cp1251). Forcing UTF-8 there would mangle exactly that case and gain nothing.
 */
fun loadEpisodeSource(dir: File, offset: Int): EpisodeSource {
    val yamlFile = File(dir, "episodes.yaml")
    val textFile = File(dir, "episodes.txt")

    if (yamlFile.isFile) {
        // Guarded, unlike v1, which parsed it bare and met a hand-edited `episode: "one"` with a stack
        // trace. `mux` and `inspect` both guard the same file; this was the odd one out.
        return when (val load = loadMapping(yamlFile, Charsets.UTF_8, ::normalizeYaml)) {
            is MappingLoad.Loaded -> EpisodeSource.Loaded(load.value, yamlFile.name)
            is MappingLoad.Problem -> EpisodeSource.Problem("${load.message}; there is nothing to rename by")
        }
    }

    if (textFile.isFile) {
        return EpisodeSource.Loaded(
            EpisodeData(byEpisode = indexFromLines(readLinesDetected(textFile), offset)),
            textFile.name,
        )
    }

    return EpisodeSource.Problem(
        "No episode data: expected episodes.yaml or episodes.txt in the current directory",
        hint = "  - run mkvtool fetch-episodes, or write episodes.txt by hand (one episode name per line)",
    )
}

/** One rename that will be performed. [relPath] is set for an external file, whose path is what says
 *  which directory's copy is being renamed; the target stays a bare name because the directory never
 *  changes — that directory *is* the variant's identity. */
data class PlannedRename(
    val file: File,
    val newName: String,
    val relPath: String? = null,
    val external: Boolean = false,
)

/**
 * Something that stops the whole batch. Typed rather than a finished sentence, so the wording stays with
 * the renderer and the facts stay usable.
 */
sealed interface RenameProblem {
    /** Nothing in the name says which episode it is, so there is nothing to look a title up by. */
    data class NoEpisodeNumber(val fileName: String) : RenameProblem

    data class NoTitle(val episode: String, val source: String, val fileName: String) : RenameProblem

    /** The new name is taken by a different file. [path] carries the directory for an external target,
     *  since the same name in two directories is two different targets. */
    data class TargetExists(val path: String, val source: String) : RenameProblem

    /** Two files in one directory want the same new name — one would silently overwrite the other. */
    data class DuplicateTarget(val newName: String, val fileNames: List<String>) : RenameProblem
}

/**
 * Every rename that would happen, everything that stops them, and the external files left alone.
 *
 * [skippedExternals] are episode-number matches: their names carry no relation to the main file's, so
 * there is no "same suffix" to preserve and renaming them would be a guess. Never fatal — they are simply
 * left as they would have been without `--external` at all.
 */
data class RenamePlan(
    val entries: List<PlannedRename>,
    val problems: List<RenameProblem>,
    val skippedExternals: List<String>,
) : CommandResult

/** The show name came from the metadata rather than the command line, which is worth saying: it decides
 *  every new name in the batch. */
data class ShowNameResolved(val showName: String, val source: String) : CommandResult

/** What happened to one file. */
sealed interface RenameOutcome {
    data object Previewed : RenameOutcome
    data object Renamed : RenameOutcome
    data object Failed : RenameOutcome
}

/** [from] is a bare name for a main file and a relative path for an external one. */
data class FileRenamed(val from: String, val to: String, val outcome: RenameOutcome) : CommandResult

/** The batch: the plan it worked from, what it did, and whether it was only ever going to say so. */
data class RenameRun(
    val plan: RenamePlan,
    val files: List<FileRenamed>,
    val failed: Int,
    val dryRun: Boolean,
) : CommandResult {

    val external: Int get() = plan.entries.count { it.external }
}

/**
 * Work out every rename in [dir] without performing any.
 *
 * With [external] set, discovery runs **first**: it needs only the file list, and its answer decides which
 * files the ordinary pass must leave alone. A companion in this directory is claimed by the external rule
 * rather than the legacy one, which is strictly better for it too — the ordinary path keeps only a
 * trailing `[...]`, so `Show.S01E01.rus.srt` would lose its `.rus` and collide with its own English
 * sibling.
 */
fun buildRenamePlan(
    dir: File,
    showName: String,
    episodeNames: Map<String, String>,
    sourceName: String,
    external: Boolean,
): RenamePlan {
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && fileExtension(it.name) in RENAMEABLE_EXTENSIONS }

    // Absolute path -> the companion match that claims it, in discovery order.
    val externalMatches = LinkedHashMap<String, CompanionEntry>()
    val skippedExternals = ArrayList<String>()
    if (external) {
        val mains = files.filter { fileExtension(it.name) in MAIN_EXTENSIONS }
        discoverCompanions(mains, walkTree(dir)).variants.forEach { variant ->
            variant.entries.forEach { entry ->
                if (entry.tier != MatchTier.NAME) skippedExternals += entry.entry.relPath
                else externalMatches[entry.entry.file.absolutePath] = entry
            }
        }
    }

    val entries = ArrayList<PlannedRename>()
    val problems = ArrayList<RenameProblem>()

    for (file in files) {
        if (externalMatches.containsKey(file.absolutePath)) continue

        val baseName = file.name.substringBeforeLast('.')
        val extension = fileExtension(file.name, lowercase = false)

        val parsed = parseSeasonEpisode(baseName)
        if (parsed == null) {
            problems += RenameProblem.NoEpisodeNumber(file.name)
            continue
        }

        val episodeName = episodeNames[parsed.episode]
        if (episodeName == null) {
            problems += RenameProblem.NoTitle(parsed.episode, sourceName, file.name)
            continue
        }

        val suffix = TRAILING_BRACKET_SUFFIX.find(baseName)?.groupValues?.get(1).orEmpty()
        val newName = "$showName - S${parsed.season}E${parsed.episode} - $episodeName$suffix.$extension"

        val target = File(file.parentFile, newName)
        if (target.exists() && target.absolutePath != file.absolutePath) {
            problems += RenameProblem.TargetExists(newName, file.name)
            continue
        }

        entries += PlannedRename(file, newName)
    }

    // Every main file has a new name now, so the external files can take theirs from it: the main's new
    // base name, plus this file's own suffix verbatim and its own extension. No normalising, no suffix
    // invented from a directory name — predictable and idempotent beats tidy here.
    val newNameByPath = entries.associate { it.file.absolutePath to it.newName }
    for (entry in externalMatches.values) {
        // Its main file is itself blocked; the problem list already says why.
        val mainNewName = newNameByPath[entry.main.absolutePath] ?: continue

        val extension = fileExtension(entry.entry.file.name, lowercase = false)
        val newName = "${mainNewName.substringBeforeLast('.')}${entry.suffix.orEmpty()}.$extension"
        val target = File(entry.entry.file.parentFile, newName)
        if (target.exists() && target.absolutePath != entry.entry.file.absolutePath) {
            val parent = entry.entry.dirRel
            problems += RenameProblem.TargetExists(
                path = if (parent.isEmpty()) newName else "$parent/$newName",
                source = entry.entry.relPath,
            )
            continue
        }
        entries += PlannedRename(entry.entry.file, newName, relPath = entry.entry.relPath, external = true)
    }

    // Keyed by directory as well as by name: two files in different directories renaming to the same name
    // is not a collision, and with --external most of the plan lives outside this one.
    entries.groupBy { it.file.parentFile.absolutePath to it.newName }
        .filter { (_, group) -> group.size > 1 }
        .forEach { (key, group) ->
            problems += RenameProblem.DuplicateTarget(key.second, group.map { it.file.name })
        }

    return RenamePlan(entries, problems, skippedExternals)
}

/**
 * Perform [plan] (or, under [dryRun], only say what it would do), emitting each file as it goes.
 *
 * Callers check `plan.problems` first: a plan with any problem in it is refused whole, and nothing here
 * guards against being handed one anyway.
 */
fun applyRenamePlan(plan: RenamePlan, dryRun: Boolean, renderer: Renderer): RenameRun {
    val files = ArrayList<FileRenamed>(plan.entries.size)
    var failed = 0

    for (entry in plan.entries) {
        val from = entry.relPath ?: entry.file.name
        val outcome = when {
            dryRun -> RenameOutcome.Previewed
            entry.file.renameTo(File(entry.file.parentFile, entry.newName)) -> RenameOutcome.Renamed
            else -> {
                failed++
                RenameOutcome.Failed
            }
        }
        val result = FileRenamed(from, entry.newName, outcome)
        files += result
        renderer.render(result)
    }

    val run = RenameRun(plan, files, failed, dryRun)
    renderer.render(run)
    return run
}

/** An extension without its dot, lower-cased for matching and left alone for rebuilding a name. */
private fun fileExtension(name: String, lowercase: Boolean = true): String {
    val extension = name.substringAfterLast('.', "")
    return if (lowercase) extension.lowercase() else extension
}
