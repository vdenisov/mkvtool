package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.io.File

/**
 * The binding engine over name-only fixtures: binding reads no file, so no tree is staged on disk. Each
 * expectation is a digest line: the season, a main's episode and the run's rule (`-` for a main
 * that read none), a pairing, a problem.
 */
class BindingTest : FunSpec({

    context("bindFiles") {
        data class Case(
            val label: String,
            val mains: List<String>,
            val files: List<String>,
            val sources: List<AdditionalSource>,
            val expected: List<String>,
            val general: MatcherConfig = MatcherConfig(),
            val episodes: EpisodeData? = null,
        )

        val raw5 = "[Salender-Raws] Hellsing OVA - 05 (BD 1080p)"
        val raw6 = "[Salender-Raws] Hellsing OVA - 06 (BD 1080p)"
        val mn = "[Salender-Raws] Mnemosyne - Mnemosyne no Musume-tachi"
        val e01 = "Show - S01E01 - Title"
        val e02 = "Show - S01E02 - Next"
        val season1 = EpisodeData(season = "01")
        val hash = MatcherConfig(episodeRegex = """#(\d+)""")
        val dash = MatcherConfig(episodeRegex = """ - (\d+)$""")
        val inBrackets = MatcherConfig(episodeRegex = """ - (\d+) \(""")

        withData(
            nameFn = { it.label },
            Case(
                "a raw release's companions pair by exact name across directories",
                mains = listOf("$raw5.mkv", "$raw6.mkv"),
                files = listOf(
                    "Rus Sound/$raw5.mka",
                    "Rus Sound/$raw6.mka",
                    "Rus Subs/$raw5.ass",
                    "Rus Subs/$raw6.ass",
                ),
                sources = listOf(files("Rus Sound/*.mka"), files("Rus Subs/*.ass")),
                general = inBrackets,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main $raw5.mkv E05 REGEX",
                    "main $raw6.mkv E06 REGEX",
                    "additionalSources[0]: Rus Sound/$raw5.mka -> $raw5.mkv EXACT",
                    "additionalSources[0]: Rus Sound/$raw6.mka -> $raw6.mkv EXACT",
                    "additionalSources[1]: Rus Subs/$raw5.ass -> $raw5.mkv EXACT",
                    "additionalSources[1]: Rus Subs/$raw6.ass -> $raw6.mkv EXACT",
                ),
            ),
            Case(
                "the longest main name wins, so a file named after #12 is not #1's with a suffix",
                mains = listOf("Show #1.mkv", "Show #12.mkv"),
                files = listOf("Show #12.srt", "Show #1.rus.srt"),
                sources = listOf(files("*.srt")),
                general = hash,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main Show #1.mkv E01 REGEX",
                    "main Show #12.mkv E12 REGEX",
                    "additionalSources[0]: Show #12.srt -> Show #12.mkv EXACT",
                    "additionalSources[0]: Show #1.rus.srt -> Show #1.mkv SUFFIX",
                ),
            ),
            Case(
                // Why a caller passes every main, not the masked ones: this is what a masked-out sibling costs.
                "without its own main, a sibling pairs with the shorter name and a suffix",
                mains = listOf("Show #1.mkv"),
                files = listOf("Show #1 2.srt"),
                sources = listOf(files("*.srt")),
                general = hash,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main Show #1.mkv E01 REGEX",
                    "additionalSources[0]: Show #1 2.srt -> Show #1.mkv SUFFIX",
                ),
            ),
            Case(
                "a suffix may not continue the main's number, so #12 without its main pairs with nothing",
                mains = listOf("Show #1.mkv"),
                files = listOf("Show #12.srt", "Show #1a.srt"),
                sources = listOf(files("*.srt")),
                general = hash,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main Show #1.mkv E01 REGEX",
                    "additionalSources[0]: Show #1a.srt -> Show #1.mkv SUFFIX",
                    "problem: additionalSources[0]: Show #12.srt unpaired",
                ),
            ),
            Case(
                "a three-digit episode is its own, so E10 and E100 do not tie",
                mains = listOf("Show S01E10.mkv", "Show S01E100.mkv"),
                files = listOf("Subs/Other S01E10.srt", "Subs/Other S01E100.srt"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main Show S01E10.mkv E10 SXXEYY",
                    "main Show S01E100.mkv E100 SXXEYY",
                    "additionalSources[0]: Subs/Other S01E10.srt -> Show S01E10.mkv SXXEYY",
                    "additionalSources[0]: Subs/Other S01E100.srt -> Show S01E100.mkv SXXEYY",
                ),
            ),
            Case(
                "names match whatever their case",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/show - s01e01 - title.ASS"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "additionalSources[0]: Subs/show - s01e01 - title.ASS -> $e01.mkv EXACT",
                ),
            ),
            Case(
                "a VobSub .sub travels with its .idx whatever its case, and a lone .sub pairs on its own",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/show - s01e01 - title.sub", "Subs/$e02.sub"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "main $e02.mkv E02 SXXEYY",
                    "additionalSources[0]: Subs/$e01.idx +Subs/show - s01e01 - title.sub -> $e01.mkv EXACT",
                    "additionalSources[0]: Subs/$e02.sub -> $e02.mkv EXACT",
                ),
            ),
            Case(
                "a source's files are what its masks match, mains aside, whatever their extension",
                mains = listOf("$e01.mkv"),
                files = listOf("$e01.txt", "Subs/$e01.srt"),
                sources = listOf(files("*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "additionalSources[0]: $e01.txt -> $e01.mkv EXACT",
                ),
            ),
            Case(
                "two files of one source on one main are a problem",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01.ass", "Subs/Other S01E01.srt", "Subs/$e02.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "main $e02.mkv E02 SXXEYY",
                    "additionalSources[0]: Subs/$e01.ass -> $e01.mkv EXACT",
                    "additionalSources[0]: Subs/Other S01E01.srt -> $e01.mkv SXXEYY",
                    "additionalSources[0]: Subs/$e02.ass -> $e02.mkv EXACT",
                    "problem: additionalSources[0]: $e01.mkv shared by Subs/$e01.ass, Subs/Other S01E01.srt",
                ),
            ),
            Case(
                "one file two sources pair is a problem",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.ass"),
                sources = listOf(files("Subs/*"), files("Subs/*.ass")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "additionalSources[0]: Subs/$e01.ass -> $e01.mkv EXACT",
                    "additionalSources[1]: Subs/$e01.ass -> $e01.mkv EXACT",
                    "problem: Subs/$e01.ass in additionalSources[0], additionalSources[1]",
                ),
            ),
            Case(
                "two mains on one episode are a problem, and a file named after both a tie",
                mains = listOf("Show S01E01.mkv", "Show S01E01.mp4"),
                files = listOf("Subs/Show S01E01.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S01E01.mp4 E01 SXXEYY",
                    "problem: E01 claimed by Show S01E01.mkv, Show S01E01.mp4",
                    "problem: additionalSources[0]: Subs/Show S01E01.ass named after Show S01E01.mkv, Show S01E01.mp4",
                ),
            ),
            Case(
                "a file that pairs with no main is a problem",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/Bonus.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "problem: additionalSources[0]: Subs/Bonus.ass unpaired",
                ),
            ),
            Case(
                "a main no rule reads has no episode, and a file named after it pairs with it",
                mains = listOf("$e01.mkv", "NCOP.mkv"),
                files = listOf("Subs/$e01.ass", "Subs/NCOP.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "main NCOP.mkv -",
                    "additionalSources[0]: Subs/$e01.ass -> $e01.mkv EXACT",
                    "additionalSources[0]: Subs/NCOP.ass -> NCOP.mkv EXACT",
                ),
            ),
            Case(
                "un-numbered mains with no metadata have no season, and that is no problem",
                mains = listOf("Movie.mkv", "OVA.mkv"),
                files = emptyList(),
                sources = emptyList(),
                expected = listOf("main Movie.mkv -", "main OVA.mkv -"),
            ),
            Case(
                "a number rule cannot reach a main with no episode",
                mains = listOf("$e01.mkv", "NCOP.mkv"),
                files = listOf("Subs/01.srt", "Subs/02.srt"),
                sources = listOf(files("Subs/*", regex = """^(\d+)$""")),
                expected = listOf(
                    "season 01",
                    "main $e01.mkv E01 SXXEYY",
                    "main NCOP.mkv -",
                    "additionalSources[0]: Subs/01.srt -> $e01.mkv REGEX",
                    "problem: additionalSources[0]: Subs/02.srt unpaired",
                ),
            ),
            Case(
                "a main with an empty base name is named after by nothing",
                mains = listOf(".mkv"),
                files = listOf("Subs/Orphan.srt"),
                sources = listOf(files("Subs/*")),
                expected = listOf("main .mkv -", "problem: additionalSources[0]: Subs/Orphan.srt unpaired"),
            ),
            Case(
                "numbered-only mains take the metadata's season, and extras no mask reaches stay out",
                mains = listOf("$mn - 01 (BD 1080p).mkv", "$mn - 02 (BD 1080p).mkv"),
                files = listOf(
                    "Rus Sound/$mn - 01 (BD 1080p).mka",
                    "Rus Sound/$mn - 02 (BD 1080p).mka",
                    "Subs/$mn - 02 (BD 1080p).ass",
                    "Extras/$mn - NCOP (BD 1080p).mkv",
                    "Extras/$mn - NCOP (BD 1080p).ass",
                ),
                sources = listOf(files("Rus Sound/*.mka"), files("Subs/*.ass")),
                general = inBrackets,
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main $mn - 01 (BD 1080p).mkv E01 REGEX",
                    "main $mn - 02 (BD 1080p).mkv E02 REGEX",
                    "additionalSources[0]: Rus Sound/$mn - 01 (BD 1080p).mka -> $mn - 01 (BD 1080p).mkv EXACT",
                    "additionalSources[0]: Rus Sound/$mn - 02 (BD 1080p).mka -> $mn - 02 (BD 1080p).mkv EXACT",
                    "additionalSources[1]: Subs/$mn - 02 (BD 1080p).ass -> $mn - 02 (BD 1080p).mkv EXACT",
                ),
            ),
            Case(
                "numbered-only mains with no metadata have no season",
                mains = listOf("$mn - 01 (BD 1080p).mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = inBrackets,
                expected = listOf("main $mn - 01 (BD 1080p).mkv E01 REGEX", "problem: season unknown"),
            ),
            Case(
                "absolute numbering maps onto real episodes, where a companion's SxxEyy finds them",
                mains = listOf("Show - 13.mkv", "Show - 14.mkv"),
                files = listOf("Subs/Show S02E01.srt"),
                sources = listOf(files("Subs/*")),
                general = dash.copy(episodeMap = episodeMap("13-24" to "1-12")),
                episodes = EpisodeData(season = "02"),
                expected = listOf(
                    "season 02",
                    "main Show - 13.mkv E01 (read 13) REGEX",
                    "main Show - 14.mkv E02 (read 14) REGEX",
                    "additionalSources[0]: Subs/Show S02E01.srt -> Show - 13.mkv SXXEYY",
                ),
            ),
            Case(
                "with no episode map, the number read is the episode",
                mains = listOf("Raw - 05.mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = dash,
                episodes = season1,
                expected = listOf("season 01", "main Raw - 05.mkv E05 REGEX"),
            ),
            Case(
                "a number the map does not cover is itself, and a problem when it lands on a mapped one",
                mains = listOf("R - 01.mkv", "R - 02.mkv", "R - 03.mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = dash.copy(episodeMap = episodeMap("3" to "2")),
                episodes = season1,
                expected = listOf(
                    "season 01",
                    "main R - 01.mkv E01 REGEX",
                    "main R - 02.mkv E02 REGEX",
                    "main R - 03.mkv E02 (read 3) REGEX",
                    "problem: E02 claimed by R - 02.mkv, R - 03.mkv",
                ),
            ),
            Case(
                "files on two seasons are a problem",
                mains = listOf("Show S01E01.mkv", "Show S02E02.mkv"),
                files = emptyList(),
                sources = emptyList(),
                expected = listOf(
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S02E02.mkv E02 SXXEYY",
                    "problem: seasons 01 [Show S01E01.mkv], 02 [Show S02E02.mkv]",
                ),
            ),
            Case(
                "a season the files read against another in the metadata is a problem",
                mains = listOf("Season 3 E04.mkv"),
                files = emptyList(),
                sources = emptyList(),
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
                files = emptyList(),
                sources = emptyList(),
                general = MatcherConfig(episodeRegex = """ E(\d+)""", seasonRegex = """Season (\d+)"""),
                expected = listOf("season 01", "main Season 1 E01.mkv E01 REGEX", "main Show E05.mkv -"),
            ),
            Case(
                "a main whose regex capture is not a number has no episode",
                mains = listOf("A - 05.mkv", "A - x5.mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = MatcherConfig(episodeRegex = """ - (\w+)$"""),
                episodes = season1,
                expected = listOf("season 01", "main A - 05.mkv E05 REGEX", "main A - x5.mkv -"),
            ),
            Case(
                "a source's regex pairs a file named after nothing but its number",
                mains = listOf("A - S01E01.mkv"),
                files = listOf("Subs/01.srt"),
                sources = listOf(files("Subs/*", regex = """^(\d+)$""")),
                expected = listOf(
                    "season 01",
                    "main A - S01E01.mkv E01 SXXEYY",
                    "additionalSources[0]: Subs/01.srt -> A - S01E01.mkv REGEX",
                ),
            ),
            Case(
                "a source's regex is its rule: a number no main holds is a problem, whatever else the name says",
                mains = listOf("Show S01E01.mkv"),
                files = listOf("Subs/Sub #3 S01E01.ass"),
                sources = listOf(files("Subs/*", regex = """ #(\d+)""")),
                expected = listOf(
                    "season 01",
                    "main Show S01E01.mkv E01 SXXEYY",
                    "problem: additionalSources[0]: Subs/Sub #3 S01E01.ass unpaired",
                ),
            ),
            Case(
                "a source's sequence takes its files in path order as episodes 1..N",
                mains = listOf("Show S01E01.mkv", "Show S01E02.mkv"),
                files = listOf("Subs/b.srt", "Subs/a.srt"),
                sources = listOf(files("Subs/*", pin = "sequence")),
                expected = listOf(
                    "season 01",
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S01E02.mkv E02 SXXEYY",
                    "additionalSources[0]: Subs/a.srt -> Show S01E01.mkv SEQUENCE",
                    "additionalSources[0]: Subs/b.srt -> Show S01E02.mkv SEQUENCE",
                ),
            ),
            Case(
                "a source's sequence over more files than mains is a problem",
                mains = listOf("Show S01E01.mkv", "Show S01E02.mkv"),
                files = listOf("Subs/a.srt", "Subs/b.srt", "Subs/c.srt"),
                sources = listOf(files("Subs/*", pin = "sequence")),
                expected = listOf(
                    "season 01",
                    "main Show S01E01.mkv E01 SXXEYY",
                    "main Show S01E02.mkv E02 SXXEYY",
                    "problem: additionalSources[0] sequence over 3 files, expected 2",
                ),
            ),
            Case(
                "a source's sequence number no main holds is a problem",
                mains = listOf("Show S01E05.mkv", "Show S01E06.mkv"),
                files = listOf("Subs/a.srt", "Subs/b.srt"),
                sources = listOf(files("Subs/*", pin = "sequence")),
                expected = listOf(
                    "season 01",
                    "main Show S01E05.mkv E05 SXXEYY",
                    "main Show S01E06.mkv E06 SXXEYY",
                    "problem: additionalSources[0]: Subs/a.srt unpaired",
                    "problem: additionalSources[0]: Subs/b.srt unpaired",
                ),
            ),
            Case(
                "the main files' sequence takes them in name order, through the map",
                mains = listOf("b.mkv", "a.mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = MatcherConfig(matcher = "sequence", episodeMap = episodeMap("1-2" to "13-14")),
                episodes = EpisodeData(season = "01", byEpisode = mapOf("13" to "One", "14" to "Two")),
                expected = listOf("season 01", "main a.mkv E13 (read 1) SEQUENCE", "main b.mkv E14 (read 2) SEQUENCE"),
            ),
            Case(
                "the main files' sequence against another episode count is the one problem, sources added nothing",
                mains = listOf("a.mkv", "b.mkv"),
                files = listOf("Subs/Other S01E01.srt", "Seq/x.srt"),
                sources = listOf(files("Subs/*"), files("Seq/*", pin = "sequence")),
                general = MatcherConfig(matcher = "sequence"),
                episodes = EpisodeData(season = "01", byEpisode = mapOf("01" to "One", "02" to "Two", "03" to "Three")),
                // Refused, nothing else binds, so no season is settled and no source adds to the problem.
                expected = listOf(
                    "main a.mkv -",
                    "main b.mkv -",
                    "problem: general sequence over 2 files, expected 3",
                ),
            ),
            Case(
                "the main files' sequence with no metadata is a problem",
                mains = listOf("a.mkv", "b.mkv"),
                files = emptyList(),
                sources = emptyList(),
                general = MatcherConfig(matcher = "sequence"),
                expected = listOf(
                    "main a.mkv -",
                    "main b.mkv -",
                    "problem: general sequence over 2 files, expected nothing",
                ),
            ),
        ) { case ->
            val mainFiles = case.mains.map { File(ROOT, it) }
            val config = Config(general = GeneralConfig(matching = case.general), additionalSources = case.sources)
            val tree = (case.mains + case.files).map(::entry)

            digest(bindFiles(config, mainFiles, tree, case.episodes)) shouldContainExactlyInAnyOrder case.expected
        }

        test("each source is named by its config path, and a file: source binds nothing") {
            val config = Config(
                declaredGroups = listOf(
                    GroupConfig(additionalSources = listOf(files("a/*"))),
                    GroupConfig(additionalSources = listOf(AdditionalSource(file = "x.mka"), files("b/*"))),
                ),
            )

            bindFiles(config, emptyList(), emptyList()).sources.map { it.path } shouldContainExactly listOf(
                "groups[0].additionalSources[0]",
                "groups[1].additionalSources[1]",
            )
        }

        test("only the run the masks chose can clash over an episode or a season, and a number pairs within it") {
            // The sample beside episode 1 and a stray from season 2 are masked out, so this run has neither problem.
            val mains = listOf("S01E01.mkv", "S01E01.sample.mkv", "S02E05.mkv").map { File(ROOT, it) }
            val config = Config(additionalSources = listOf(files("Subs/*", regex = """^(\d+)$""")))

            val result = bindFiles(config, mains, listOf(entry("Subs/01.srt")), run = mains.take(1))

            digest(result) shouldContainExactlyInAnyOrder listOf(
                "season 01",
                "main S01E01.mkv E01 SXXEYY",
                "main S01E01.sample.mkv E01 SXXEYY",
                "main S02E05.mkv E05 SXXEYY",
                "additionalSources[0]: Subs/01.srt -> S01E01.mkv REGEX",
            )
            // With no mask the same directory clashes, loudly.
            val unmasked = bindFiles(config, mains, listOf(entry("Subs/01.srt")))
            unmasked.problems.map { it::class } shouldContainExactlyInAnyOrder
                listOf(DuplicateEpisode::class, SeasonConflict::class)
        }

        test("a source's sequence counts episodes, so a masked-out sample beside its episode needs no file") {
            val mains = listOf("S01E01.mkv", "S01E01.sample.mkv", "S01E02.mkv").map { File(ROOT, it) }
            val config = Config(additionalSources = listOf(files("Subs/*", pin = "sequence")))

            val subs = listOf(entry("Subs/a.srt"), entry("Subs/b.srt"))

            val result = bindFiles(config, mains, subs, run = mains - mains[1])

            digest(result) shouldContainExactlyInAnyOrder listOf(
                "season 01",
                "main S01E01.mkv E01 SXXEYY",
                "main S01E01.sample.mkv E01 SXXEYY",
                "main S01E02.mkv E02 SXXEYY",
                "additionalSources[0]: Subs/a.srt -> S01E01.mkv SEQUENCE",
                "additionalSources[0]: Subs/b.srt -> S01E02.mkv SEQUENCE",
            )
        }
    }
})

private val ROOT = File("media")

private fun files(vararg masks: String, pin: String? = null, regex: String? = null) =
    AdditionalSource(files = masks.toList(), matching = MatcherConfig(matcher = pin, episodeRegex = regex))

private fun episodeMap(vararg entries: Pair<String, String>) =
    EpisodeMap(entries.map { EpisodeMapEntry(it.first, it.second) })

/** What [walkTree] would report for [relPath] under [ROOT]. */
private fun entry(relPath: String): TreeEntry {
    val name = relPath.substringAfterLast('/')
    val dir = relPath.substringBeforeLast('/', "")
    return TreeEntry(
        file = File(ROOT, relPath),
        relPath = relPath,
        dirRel = dir,
        leaf = dir.substringAfterLast('/').ifEmpty { null },
        base = baseNameOf(name),
        ext = extensionOf(name),
    )
}

private fun digest(result: BindingResult): List<String> =
    listOfNotNull(result.season?.let { "season $it" }) +
        result.mains.map { main ->
            val episode = main.episode ?: return@map "main ${main.main.name} -"
            val read = if (main.read == episode) "" else " (read ${main.read})"
            "main ${main.main.name} ${spelled(episode)}$read ${result.rule}"
        } +
        result.sources.flatMap { source ->
            source.bound.map { "${source.path}: ${filesOf(it)} -> ${it.main.name} ${it.rule}" }
        } +
        result.problems.map { "problem: ${problemLine(it)}" }

private fun problemLine(problem: BindingProblem): String = when (problem) {
    SeasonUnknown -> "season unknown"
    is SeasonConflict -> (
        problem.bySeason.entries.sortedBy { it.key }.map { (season, names) -> "$season [${names.joinToString()}]" } +
            listOfNotNull(problem.metadata?.let { "metadata $it" })
        ).joinToString(", ", prefix = "seasons ")
    is DuplicateEpisode -> "${spelled(problem.episode)} claimed by ${names(problem.mains)}"
    is SequenceCount ->
        "${problem.path} sequence over ${problem.files} files, expected ${problem.expected ?: "nothing"}"
    is UnpairedFile -> "${problem.path}: ${problem.entry.relPath} unpaired"
    is NameTie -> "${problem.path}: ${problem.entry.relPath} named after ${names(problem.mains)}"
    is SharedMain -> "${problem.path}: ${problem.main.name} shared by ${problem.entries.joinToString { it.relPath }}"
    is SharedFile -> "${problem.entry.relPath} in ${problem.paths.joinToString()}"
}

private fun names(files: List<File>) = files.map { it.name }.sorted().joinToString()

private fun spelled(episode: Int) = "E" + pad(episode)

private fun filesOf(bound: BoundFile) = bound.entry.relPath + bound.sub?.let { " +${it.relPath}" }.orEmpty()
