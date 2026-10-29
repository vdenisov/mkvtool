package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The binding engine. Reading the main files is name work over unstaged fixtures; resolving the companions stages
 * its files in a temporary directory, since a literal template asks the disk. Each expectation is a digest line:
 * the season, a main's episode and the run's rule (`-` for a main that read none), a resolved companion, a problem.
 */
class BindingTest : FunSpec({

    context("bindFiles: reading the main files") {
        data class Case(
            val label: String,
            val mains: List<String>,
            val expected: List<String>,
            val general: MatcherConfig = MatcherConfig(),
            val episodes: EpisodeData? = null,
        )

        val mn = "[Salender-Raws] Mnemosyne - Mnemosyne no Musume-tachi"
        val season1 = EpisodeData(season = "01")
        val dash = MatcherConfig(episodeRegex = """ - (\d+)$""")
        val inBrackets = MatcherConfig(episodeRegex = """ - (\d+) \(""")

        withData(
            nameFn = { it.label },
            Case(
                "a raw release reads by its regex, and takes the metadata's season",
                mains = listOf("$mn - 01 (BD 1080p).mkv", "$mn - 02 (BD 1080p).mkv"),
                general = inBrackets,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main $mn - 01 (BD 1080p).mkv E01 REGEX",
                    "main $mn - 02 (BD 1080p).mkv E02 REGEX",
                ),
            ),
            Case(
                "a three-digit episode is its own, so E10 and E100 do not clash",
                mains = listOf("Show S01E10.mkv", "Show S01E100.mkv"),
                expected = listOf("season 01", "main Show S01E10.mkv E10 SXXEYY", "main Show S01E100.mkv E100 SXXEYY"),
            ),
            Case(
                "two mains on one episode are a problem",
                mains = listOf("Show S01E01.mkv", "Show S01E01.mp4"),
                expected = listOf(
                    "season 01",
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S01E01.mp4 E01 SXXEYY",
                    "problem: E01 claimed by Show S01E01.mkv, Show S01E01.mp4",
                ),
            ),
            Case(
                "a main no rule reads has no episode",
                mains = listOf("Show - S01E01 - Title.mkv", "NCOP.mkv"),
                expected = listOf("season 01", "main Show - S01E01 - Title.mkv E01 SXXEYY", "main NCOP.mkv -"),
            ),
            Case(
                "un-numbered mains with no metadata have no season, and that is no problem",
                mains = listOf("Movie.mkv", "OVA.mkv"),
                expected = listOf("main Movie.mkv -", "main OVA.mkv -"),
            ),
            Case(
                "numbered-only mains with no metadata have no season",
                mains = listOf("$mn - 01 (BD 1080p).mkv"),
                general = inBrackets,
                expected = listOf("main $mn - 01 (BD 1080p).mkv E01 REGEX", "problem: season unknown"),
            ),
            Case(
                "a season split after the fact is a regex and a map, and keeps the number read",
                mains = listOf("[Grp] Show - 13.mkv", "[Grp] Show - 14.mkv"),
                general = dash.copy(episodeMap = episodeMap("13-24" to "1-12")),
                episodes = EpisodeData(season = "02"),
                expected = listOf(
                    "season 02",
                    "main [Grp] Show - 13.mkv E01 (read 13) REGEX",
                    "main [Grp] Show - 14.mkv E02 (read 14) REGEX",
                ),
            ),
            Case(
                "the number read is kept as the name spells it",
                mains = listOf("R - 013.mkv", "R - 014.mkv"),
                general = dash.copy(episodeMap = episodeMap("13-14" to "1-2")),
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main R - 013.mkv E01 (read 013) REGEX",
                    "main R - 014.mkv E02 (read 014) REGEX",
                ),
            ),
            Case(
                "with no episode map, the number read is the episode",
                mains = listOf("Raw - 05.mkv"),
                general = dash,
                episodes = season1,
                expected = listOf("season 01", "main Raw - 05.mkv E05 REGEX"),
            ),
            Case(
                "a number the map does not cover is itself, and a problem when it lands on a mapped one",
                mains = listOf("R - 01.mkv", "R - 02.mkv", "R - 03.mkv"),
                general = dash.copy(episodeMap = episodeMap("3" to "2")),
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main R - 01.mkv E01 REGEX",
                    "main R - 02.mkv E02 REGEX",
                    "main R - 03.mkv E02 (read 03) REGEX",
                    "problem: E02 claimed by R - 02.mkv, R - 03.mkv",
                ),
            ),
            Case(
                "files on two seasons are a problem",
                mains = listOf("Show S01E01.mkv", "Show S02E02.mkv"),
                expected = listOf(
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S02E02.mkv E02 SXXEYY",
                    "problem: seasons 01 [Show S01E01.mkv], 02 [Show S02E02.mkv]",
                ),
            ),
            Case(
                "a season the files read against another in the metadata is a problem",
                mains = listOf("Season 3 E04.mkv"),
                general = MatcherConfig(episodeRegex = """ E(\d+)""", seasonRegex = """Season (\d+)"""),
                episodes = EpisodeData(season = "02"),
                expected = listOf(
                    "main Season 3 E04.mkv E04 REGEX",
                    "problem: seasons 03 [Season 3 E04.mkv], metadata 02",
                ),
            ),
            Case(
                "a main its seasonRegex misses has no episode",
                mains = listOf("Season 1 E01.mkv", "Show E05.mkv"),
                general = MatcherConfig(episodeRegex = """ E(\d+)""", seasonRegex = """Season (\d+)"""),
                expected = listOf("season 01", "main Season 1 E01.mkv E01 REGEX", "main Show E05.mkv -"),
            ),
            Case(
                "a main whose regex capture is not a number has no episode",
                mains = listOf("A - 05.mkv", "A - x5.mkv"),
                general = MatcherConfig(episodeRegex = """ - (\w+)$"""),
                episodes = season1,
                expected = listOf("season 01", "main A - 05.mkv E05 REGEX", "main A - x5.mkv -"),
            ),
        ) { case ->
            val config = Config(general = GeneralConfig(matching = case.general))

            val result = bindFiles(ROOT, config, case.mains.map { File(ROOT, it) }, emptyList(), case.episodes)

            digest(result) shouldContainExactlyInAnyOrder case.expected
        }

        test("only the run the masks chose can clash over an episode or a season") {
            // The sample beside episode 1 and a stray from season 2 are masked out, so this run has neither problem.
            val mains = listOf("S01E01.mkv", "S01E01.sample.mkv", "S02E05.mkv").map { File(ROOT, it) }

            bindFiles(ROOT, Config(), mains, emptyList(), run = mains.take(1)).problems.shouldBeEmpty()
            // With no mask the same directory clashes, loudly.
            bindFiles(ROOT, Config(), mains, emptyList()).problems.map { it::class } shouldContainExactlyInAnyOrder
                listOf(DuplicateEpisode::class, SeasonConflict::class)
        }
    }

    context("bindFiles: resolving the companions") {
        data class Case(
            val label: String,
            val mains: List<String>,
            val files: List<String>,
            val sources: List<String>,
            val expected: List<String>,
            val general: MatcherConfig = MatcherConfig(),
            val episodes: EpisodeData? = null,
            /** The mains the masks selected, every one when null. */
            val run: List<String>? = null,
            val title: String? = null,
        )

        val e01 = "Show - S01E01"
        val e02 = "Show - S01E02"

        withData(
            nameFn = { it.label },
            Case(
                "a literal template names the file, or none, as it always did",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("$e01[Studio].mka"),
                sources = listOf("\${fileName}[Studio].mka"),
                expected = listOf("additionalSources[0]: $e01[Studio].mka -> $e01.mkv"),
            ),
            Case(
                "a wildcard finds the file a CRC makes unguessable",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/[Dub] Show - 01 [AB12CD34].ass", "Subs/[Dub] Show - 02 [99FF00AA].ass"),
                sources = listOf("Subs/* - \${episodeNum} [*].ass"),
                expected = listOf(
                    "additionalSources[0]: Subs/[Dub] Show - 01 [AB12CD34].ass -> $e01.mkv",
                    "additionalSources[0]: Subs/[Dub] Show - 02 [99FF00AA].ass -> $e02.mkv",
                ),
            ),
            Case(
                "a wildcard matching several files is a problem; one matching none is not",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/Show 01 a.ass", "Subs/Show 01 b.ass"),
                sources = listOf("Subs/Show \${episodeNum} *.ass"),
                expected = listOf(
                    "additionalSources[0]: $e01.mkv matches Subs/Show 01 a.ass, Subs/Show 01 b.ass",
                    "problem: additionalSources[0]: $e01.mkv matches Subs/Show 01 a.ass, Subs/Show 01 b.ass",
                ),
            ),
            Case(
                "a VobSub pair is one match, its .sub travelling with the .idx",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/$e01.sub"),
                sources = listOf("Subs/\${fileName}.*"),
                expected = listOf("additionalSources[0]: Subs/$e01.idx +$e01.sub -> $e01.mkv"),
            ),
            Case(
                "a template naming the .idx alone still takes its .sub along",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01 x.idx", "Subs/$e01 x.sub", "Subs/$e02.idx", "Subs/$e02.sub"),
                sources = listOf("Subs/\${fileName}*.idx"),
                expected = listOf(
                    "additionalSources[0]: Subs/$e01 x.idx +$e01 x.sub -> $e01.mkv",
                    "additionalSources[0]: Subs/$e02.idx +$e02.sub -> $e02.mkv",
                ),
            ),
            Case(
                "a template naming the .sub alone names the pair, through its .idx",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/$e01.sub"),
                sources = listOf("Subs/\${fileName}*.sub"),
                expected = listOf("additionalSources[0]: Subs/$e01.idx +$e01.sub -> $e01.mkv"),
            ),
            Case(
                "one source naming a pair's .idx and another its .sub name one track twice",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/$e01.sub"),
                sources = listOf("Subs/\${fileName}.idx", "Subs/\${fileName}*.sub"),
                expected = listOf(
                    "additionalSources[0]: Subs/$e01.idx +$e01.sub -> $e01.mkv",
                    "additionalSources[1]: Subs/$e01.idx +$e01.sub -> $e01.mkv",
                    "problem: Subs/$e01.idx picked for $e01.mkv by additionalSources[0], additionalSources[1]",
                ),
            ),
            Case(
                "a wildcard never picks a main file",
                mains = listOf("$e01.mkv"),
                files = listOf("$e01.ass"),
                sources = listOf("*E\${episodeNum}*"),
                expected = listOf("additionalSources[0]: $e01.ass -> $e01.mkv"),
            ),
            Case(
                "only the run's mains are resolved, so a masked-out sample beside its episode costs nothing",
                mains = listOf("S01E01.mkv", "S01E01.sample.mkv"),
                files = listOf("Subs/S01E01.ass"),
                sources = listOf("Subs/*E\${episodeNum}*.ass"),
                run = listOf("S01E01.mkv"),
                expected = listOf("additionalSources[0]: Subs/S01E01.ass -> S01E01.mkv"),
            ),
            Case(
                "one file a wildcard picks for two episodes is the template's to get right, not binding's to refuse",
                mains = listOf("Show S01E01.mkv", "Show S01E10.mkv"),
                files = listOf("Subs/S01E10.ass"),
                sources = listOf("Subs/*E\${episodeN}*.ass"),
                expected = listOf(
                    "additionalSources[0]: Subs/S01E10.ass -> Show S01E01.mkv",
                    "additionalSources[0]: Subs/S01E10.ass -> Show S01E10.mkv",
                ),
            ),
            Case(
                "one file a template names for every episode is no problem",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("fonts.mks"),
                sources = listOf("fonts.mks"),
                expected = listOf(
                    "additionalSources[0]: fonts.mks -> $e01.mkv",
                    "additionalSources[0]: fonts.mks -> $e02.mkv",
                ),
            ),
            Case(
                "one file two sources pick for one episode is a problem, since it would be muxed twice",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/E01 [Full].ass"),
                sources = listOf("Subs/*\${episodeNum}*.ass", "Subs/*\${episodeNum}*[Full].ass"),
                expected = listOf(
                    "additionalSources[0]: Subs/E01 [Full].ass -> $e01.mkv",
                    "additionalSources[1]: Subs/E01 [Full].ass -> $e01.mkv",
                    "problem: Subs/E01 [Full].ass picked for $e01.mkv by additionalSources[0], additionalSources[1]",
                ),
            ),
            Case(
                "a main stage two will drop raises no problem, its ambiguity included, which is still recorded",
                mains = listOf("Show S01E01.mkv"),
                files = listOf("Subs/E01 a.ass", "Subs/E01 b.ass"),
                sources = listOf("Subs/*\${episodeNum}*.ass"),
                title = "\${episodeName}",
                expected = listOf("additionalSources[0]: Show S01E01.mkv matches Subs/E01 a.ass, Subs/E01 b.ass"),
            ),
            Case(
                "a main stage two will drop is still resolved, so --identify can show what is there",
                mains = listOf("Show S01E01.mkv"),
                files = listOf("Subs/E01.ass"),
                sources = listOf("Subs/*\${episodeNum}*.ass"),
                title = "\${episodeName}",
                expected = listOf("additionalSources[0]: Subs/E01.ass -> Show S01E01.mkv"),
            ),
            Case(
                "a template naming a variable nothing knows resolves nothing, rather than a ** across folders",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/a/x.ass", "Subs/b/y.ass"),
                sources = listOf("Subs/*\${fileNmae}*.ass"),
                expected = emptyList(),
            ),
            Case(
                "a template needing a variable a main has no value for is left to stage two",
                mains = listOf("Show S01E01.mkv"),
                files = listOf("Subs/Pilot.ass"),
                sources = listOf("Subs/\${episodeName}*.ass"),
                expected = emptyList(),
            ),
            Case(
                "a companion numbered like the release is named by the number read",
                mains = listOf("[Grp] Show - 13.mkv"),
                files = listOf("Subs/13.srt"),
                sources = listOf("Subs/\${episodeRead}.srt"),
                general = MatcherConfig(episodeRegex = """ - (\d+)$""", episodeMap = episodeMap("13" to "1")),
                episodes = EpisodeData(season = "01"),
                expected = listOf("additionalSources[0]: Subs/13.srt -> [Grp] Show - 13.mkv"),
            ),
        ) { case ->
            val dir = tempdir()
            (case.mains + case.files).forEach { File(dir, it).apply { parentFile.mkdirs() }.writeText("x") }
            val mains = case.mains.map { File(dir, it) }
            val config = Config(
                general = GeneralConfig(matching = case.general, title = case.title?.let(::Template)),
                additionalSources = case.sources.map { AdditionalSource(file = it) },
            )
            val run = case.run?.map { File(dir, it) } ?: mains

            val result = bindFiles(dir, config, mains, walkTree(dir), case.episodes ?: EpisodeData(season = "01"), run)

            companionDigest(result) shouldContainExactlyInAnyOrder case.expected
        }

        test("each group resolves its own sources, named by their config paths") {
            val dir = tempdir()
            listOf("S01E01.mkv", "S01E02.mkv", "S01E01.ass", "S01E02.mka").forEach { File(dir, it).writeText("x") }
            val config = Config(
                declaredGroups = listOf(
                    group(1..1, sources = listOf(AdditionalSource(file = "\${fileName}.ass"))),
                    group(2..2, sources = listOf(AdditionalSource(), AdditionalSource(file = "\${fileName}.mka"))),
                ),
            )

            val result = bindFiles(dir, config, listOf(File(dir, "S01E01.mkv"), File(dir, "S01E02.mkv")), walkTree(dir))

            result.sources.map { it.path } shouldContainExactly
                listOf("groups[0].additionalSources[0]", "groups[1].additionalSources[1]")
            companionDigest(result) shouldContainExactlyInAnyOrder listOf(
                "groups[0].additionalSources[0]: S01E01.ass -> S01E01.mkv",
                "groups[1].additionalSources[1]: S01E02.mka -> S01E02.mkv",
            )
        }
    }

    context("bindFiles with groups") {
        data class Case(
            val label: String,
            val mains: List<String>,
            /** Null for a config without groups. */
            val groups: List<DeclaredGroup>?,
            val expected: List<String>,
            val general: MatcherConfig = MatcherConfig(),
            val episodes: EpisodeData? = null,
        )

        val e = (1..4).map { "Show S01E0$it" }

        withData(
            nameFn = { it.label },
            Case(
                "an unclaimed main is no group's, without a problem",
                mains = e.take(3).map { "$it.mkv" },
                groups = listOf(group(1..2)),
                expected = listOf(
                    "season 01",
                    "main ${e[0]}.mkv E01 SXXEYY",
                    "main ${e[1]}.mkv E02 SXXEYY",
                    "main ${e[2]}.mkv E03 SXXEYY",
                    "group 0: ${e[0]}.mkv, ${e[1]}.mkv",
                    "unclaimed ${e[2]}.mkv",
                ),
            ),
            Case(
                "a main that reads no episode is no declared group's",
                mains = listOf("${e[0]}.mkv", "NCOP.mkv"),
                groups = listOf(group(1..1)),
                expected = listOf(
                    "season 01",
                    "main ${e[0]}.mkv E01 SXXEYY",
                    "main NCOP.mkv -",
                    "group 0: ${e[0]}.mkv",
                    "unclaimed NCOP.mkv",
                ),
            ),
            Case(
                "a group claims by the episode a number maps to, not the number read",
                mains = listOf("Show - 13.mkv", "Show - 14.mkv"),
                groups = listOf(group(1..1), group(2..2)),
                general = MatcherConfig(episodeRegex = """ - (\d+)$""", episodeMap = episodeMap("13-14" to "1-2")),
                episodes = EpisodeData(season = "01"),
                expected = listOf(
                    "season 01",
                    "main Show - 13.mkv E01 (read 13) REGEX",
                    "main Show - 14.mkv E02 (read 14) REGEX",
                    "group 0: Show - 13.mkv",
                    "group 1: Show - 14.mkv",
                ),
            ),
            Case(
                "a config without groups is one group of every main",
                mains = listOf("${e[0]}.mkv", "NCOP.mkv"),
                groups = null,
                expected = listOf(
                    "season 01",
                    "main ${e[0]}.mkv E01 SXXEYY",
                    "main NCOP.mkv -",
                    "group 0: NCOP.mkv, ${e[0]}.mkv",
                ),
            ),
        ) { case ->
            val config = Config(general = GeneralConfig(matching = case.general), declaredGroups = case.groups)

            val result = bindFiles(ROOT, config, case.mains.map { File(ROOT, it) }, emptyList(), case.episodes)

            groupDigest(result) shouldContainExactlyInAnyOrder case.expected
        }

        test("narrowing to a run narrows the partition") {
            val mains = e.take(3).map { File(ROOT, "$it.mkv") }
            val config = Config(declaredGroups = listOf(group(1..1), group(2..2)))

            val narrowed = bindFiles(ROOT, config, mains, emptyList()).narrowedTo(setOf(mains[1], mains[2]))

            narrowed.partition shouldBe GroupPartition(
                members = listOf(emptyList(), listOf(mains[1])),
                unclaimed = listOf(mains[2]),
            )
        }

        test("a grouped config names its groups by index, and by name where they have one") {
            val raw = DeclaredGroup(name = "raw", membership = Membership(episodes = listOf(1..2)))
            val config = Config(declaredGroups = listOf(raw, group(3..3)))

            bindFiles(ROOT, config, emptyList(), emptyList()).groups shouldBe
                listOf(GroupRef(0, "raw"), GroupRef(1, null))
        }

        test("a config without groups has no group to name") {
            bindFiles(ROOT, Config(), listOf(File(ROOT, "${e[0]}.mkv")), emptyList()).groups.shouldBeEmpty()
        }
    }
})

private val ROOT = File("media")

private fun group(vararg episodes: IntRange, sources: List<AdditionalSource> = emptyList()) =
    DeclaredGroup(membership = Membership(episodes = episodes.toList()), additionalSources = sources)

private fun episodeMap(vararg entries: Pair<String, String>) =
    EpisodeMap(entries.map { EpisodeMapEntry(it.first, it.second) })

private fun digest(result: BindingResult): List<String> =
    listOfNotNull(result.season?.let { "season $it" }) +
        result.mains.map { main ->
            val episode = main.episode ?: return@map "main ${main.main.name} -"
            val read = if (main.read == pad(episode)) "" else " (read ${main.read})"
            "main ${main.main.name} ${spelled(episode)}$read ${result.rule}"
        } +
        result.problems.map { "problem: ${problemLine(it)}" }

/** The companions each source resolved, what it found several files for, and the problems. */
private fun companionDigest(result: BindingResult): List<String> =
    result.sources.flatMap { source ->
        source.companions.map { companion ->
            val partner = companion.partner?.let { " +${it.name}" }.orEmpty()
            "${source.path}: ${companion.path}$partner -> ${companion.main.name}"
        } + source.ambiguous.map { (main, matches) ->
            "${source.path}: ${main.name} matches ${matches.sorted().joinToString()}"
        }
    } + result.problems.map { "problem: ${problemLine(it)}" }

/** [digest], plus a line per group's members and per unclaimed main. */
private fun groupDigest(result: BindingResult): List<String> =
    digest(result) +
        result.partition.members.mapIndexed { g, members -> "group $g: ${names(members)}" } +
        result.partition.unclaimed.map { "unclaimed ${it.name}" }

private fun problemLine(problem: BindingProblem): String = when (problem) {
    SeasonUnknown -> "season unknown"
    is SeasonConflict -> (
        problem.bySeason.entries.sortedBy { it.key }.map { (season, names) -> "$season [${names.joinToString()}]" } +
            listOfNotNull(problem.metadata?.let { "metadata $it" })
        ).joinToString(", ", prefix = "seasons ")
    is DuplicateEpisode -> "${spelled(problem.episode)} claimed by ${names(problem.mains)}"
    is AmbiguousCompanion -> "${problem.path}: ${problem.main.name} matches ${problem.matches.sorted().joinToString()}"
    is RepeatedCompanion -> "${problem.path} picked for ${problem.main.name} by ${problem.sources.joinToString()}"
}

private fun names(files: List<File>) = files.map { it.name }.sorted().joinToString()

private fun spelled(episode: Int) = "E" + pad(episode)
