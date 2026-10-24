package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The binding engine over name-only fixtures: binding reads no file, so no tree is staged on disk. Each
 * expectation is a digest line naming the rule that bound a file or the reason nothing did.
 */
class BindingTest : FunSpec({

    context("bindFiles") {
        data class Case(
            val label: String,
            val mains: List<String>,
            val files: List<String>,
            val sources: List<AdditionalSource>,
            val expected: List<String>,
        )

        val raw5 = "[Salender-Raws] Hellsing OVA - 05 (BD 1080p)"
        val raw6 = "[Salender-Raws] Hellsing OVA - 06 (BD 1080p)"
        val e01 = "Show - S01E01 - Title"
        val e02 = "Show - S01E02 - Next"

        withData(
            nameFn = { it.label },
            Case(
                "a raw release's companions bind by exact name across directories",
                mains = listOf("$raw5.mkv", "$raw6.mkv"),
                files = listOf(
                    "Rus Sound/$raw5.mka",
                    "Rus Sound/$raw6.mka",
                    "Rus Subs/$raw5.ass",
                    "Rus Subs/$raw6.ass",
                ),
                sources = listOf(files("Rus Sound/*.mka"), files("Rus Subs/*.ass")),
                // Neither main has an episode, and both still receive their files.
                expected = listOf(
                    "main $raw5.mkv unbound",
                    "main $raw6.mkv unbound",
                    "additionalSources[0]: Rus Sound/$raw5.mka -> $raw5.mkv EXACT",
                    "additionalSources[0]: Rus Sound/$raw6.mka -> $raw6.mkv EXACT",
                    "additionalSources[1]: Rus Subs/$raw5.ass -> $raw5.mkv EXACT",
                    "additionalSources[1]: Rus Subs/$raw6.ass -> $raw6.mkv EXACT",
                ),
            ),
            Case(
                "the longest main name wins, so a suffixed sibling binds to its own main",
                mains = listOf("Title.mkv", "Title 2.mkv"),
                files = listOf("Title 2.srt", "Title.rus.srt"),
                sources = listOf(files("*.srt")),
                expected = listOf(
                    "main Title.mkv unbound",
                    "main Title 2.mkv unbound",
                    "additionalSources[0]: Title 2.srt -> Title 2.mkv EXACT",
                    "additionalSources[0]: Title.rus.srt -> Title.mkv SUFFIX",
                ),
            ),
            Case(
                // Why a caller passes every main, not the masked ones: this is what a masked-out sibling costs.
                "without its own main, a sibling binds to the shorter name with a suffix",
                mains = listOf("Title.mkv"),
                files = listOf("Title 2.srt"),
                sources = listOf(files("*.srt")),
                expected = listOf("main Title.mkv unbound", "additionalSources[0]: Title 2.srt -> Title.mkv SUFFIX"),
            ),
            Case(
                "names match whatever their case",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/show - s01e01 - title.ASS"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "additionalSources[0]: Subs/show - s01e01 - title.ASS -> $e01.mkv EXACT",
                ),
            ),
            Case(
                "two files of one source for one main: neither binds, and each keeps its rule",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01.ass", "Subs/Other S01E01.srt", "Subs/$e02.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "main $e02.mkv S01E02 SXXEYY",
                    "additionalSources[0]: Subs/$e01.ass -> $e01.mkv EXACT contested",
                    "additionalSources[0]: Subs/Other S01E01.srt -> $e01.mkv SXXEYY contested",
                    "additionalSources[0]: Subs/$e02.ass -> $e02.mkv EXACT",
                ),
            ),
            Case(
                "an episode two mains claim belongs to neither, and binds no file",
                mains = listOf("A S01E01.mkv", "B S01E01.mkv", "C S01E02.mkv"),
                files = listOf("Subs/Other S01E01.srt", "Subs/Late S01E02.srt"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main A S01E01.mkv S01E01 SXXEYY contested",
                    "main B S01E01.mkv S01E01 SXXEYY contested",
                    "main C S01E02.mkv S01E02 SXXEYY",
                    "additionalSources[0]: Subs/Other S01E01.srt EPISODE_AMBIGUOUS A S01E01.mkv, B S01E01.mkv",
                    "additionalSources[0]: Subs/Late S01E02.srt -> C S01E02.mkv SXXEYY",
                ),
            ),
            Case(
                "a file no rule binds is reported",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/Bonus.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf("main $e01.mkv S01E01 SXXEYY", "additionalSources[0]: Subs/Bonus.ass NO_MATCH"),
            ),
            Case(
                "two mains with one base name bind no file by name",
                mains = listOf("X.mkv", "X.mp4"),
                files = listOf("Subs/X.ass"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main X.mkv unbound",
                    "main X.mp4 unbound",
                    "additionalSources[0]: Subs/X.ass NAME_AMBIGUOUS X.mkv, X.mp4",
                ),
            ),
            Case(
                "a main with an empty base name adopts nothing",
                mains = listOf(".mkv"),
                files = listOf("Subs/Orphan.srt"),
                sources = listOf(files("Subs/*")),
                expected = listOf("main .mkv unbound", "additionalSources[0]: Subs/Orphan.srt NO_MATCH"),
            ),
            Case(
                "the exact pin refuses a suffixed name, even one its SxxEyy would bind",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.rus.srt"),
                sources = listOf(files("Subs/*", pin = "exact")),
                expected = listOf("main $e01.mkv S01E01 SXXEYY", "additionalSources[0]: Subs/$e01.rus.srt NO_MATCH"),
            ),
            Case(
                "the suffix pin binds an exact name too",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.srt"),
                sources = listOf(files("Subs/*", pin = "suffix")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "additionalSources[0]: Subs/$e01.srt -> $e01.mkv SUFFIX",
                ),
            ),
            Case(
                "the sxxeyy pin skips the name rules",
                mains = listOf("$e01.mkv", "Raw 05.mkv"),
                files = listOf("Subs/$e01.srt", "Subs/Raw 05.srt"),
                sources = listOf(files("Subs/*", pin = "sxxeyy")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "main Raw 05.mkv unbound",
                    "additionalSources[0]: Subs/$e01.srt -> $e01.mkv SXXEYY",
                    "additionalSources[0]: Subs/Raw 05.srt NO_MATCH",
                ),
            ),
            Case(
                "a VobSub .sub travels with its .idx, and a lone .sub binds on its own",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/$e01.sub", "Subs/$e02.sub"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "main $e02.mkv S01E02 SXXEYY",
                    "additionalSources[0]: Subs/$e01.idx +Subs/$e01.sub -> $e01.mkv EXACT",
                    "additionalSources[0]: Subs/$e02.sub -> $e02.mkv EXACT",
                ),
            ),
            Case(
                "a VobSub pair pairs whatever the case of its names",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.idx", "Subs/show - s01e01 - title.sub"),
                sources = listOf(files("Subs/*")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "additionalSources[0]: Subs/$e01.idx +Subs/show - s01e01 - title.sub -> $e01.mkv EXACT",
                ),
            ),
            Case(
                // The .sub counts whether a source binds it alone or as the half of a pair.
                "a file two sources bind binds to neither",
                mains = listOf("$e01.mkv", "$e02.mkv"),
                files = listOf("Subs/$e01.ass", "Subs/$e02.idx", "Subs/$e02.sub"),
                sources = listOf(files("Subs/*"), files("Subs/$e01.ass", "Subs/*.sub")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "main $e02.mkv S01E02 SXXEYY",
                    "additionalSources[0]: Subs/$e01.ass -> $e01.mkv EXACT contested",
                    "additionalSources[0]: Subs/$e02.idx +Subs/$e02.sub -> $e02.mkv EXACT contested",
                    "additionalSources[1]: Subs/$e01.ass -> $e01.mkv EXACT contested",
                    "additionalSources[1]: Subs/$e02.sub -> $e02.mkv EXACT contested",
                ),
            ),
            Case(
                "a file one source binds is not contested by a source that leaves it unbound",
                mains = listOf("$e01.mkv"),
                files = listOf("Subs/$e01.rus.ass"),
                sources = listOf(files("Subs/*"), files("Subs/*", pin = "exact")),
                expected = listOf(
                    "main $e01.mkv S01E01 SXXEYY",
                    "additionalSources[0]: Subs/$e01.rus.ass -> $e01.mkv SUFFIX",
                    "additionalSources[1]: Subs/$e01.rus.ass NO_MATCH",
                ),
            ),
            Case(
                "a source's files are what its masks match, mains aside, whatever their extension",
                mains = listOf("$e01.mkv"),
                files = listOf("$e01.txt", "Subs/$e01.srt"),
                sources = listOf(files("*")),
                expected = listOf("main $e01.mkv S01E01 SXXEYY", "additionalSources[0]: $e01.txt -> $e01.mkv EXACT"),
            ),
        ) { (_, mains, files, sources, expected) ->
            val mainFiles = mains.map { File(ROOT, it) }
            val config = Config(additionalSources = sources)

            digest(bindFiles(config, mainFiles, (mains + files).map(::entry))) shouldContainExactlyInAnyOrder expected
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

        test("the main files' sxxeyy pin, in any case, binds as the cascade does") {
            val mains = listOf("$e01.mkv", "Raw 05.mkv").map { File(ROOT, it) }
            val pinned = Config(general = GeneralConfig(matching = MatcherConfig(matcher = "SxxEyy")))

            bindFiles(pinned, mains, emptyList()) shouldBe bindFiles(Config(), mains, emptyList())
        }
    }
})

private val ROOT = File("media")

private fun files(vararg masks: String, pin: String? = null) =
    AdditionalSource(files = masks.toList(), matching = MatcherConfig(matcher = pin))

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
    result.mains.bound.map { "main ${it.main.name} ${spelled(it.episode)} ${it.rule}" } +
        result.mains.contested.map { "main ${it.main.name} ${spelled(it.episode)} ${it.rule} contested" } +
        result.mains.unbound.map { "main ${it.name} unbound" } +
        result.sources.flatMap { source ->
            source.bound.map { "${source.path}: ${filesOf(it.entry, it.sub)} -> ${it.main.name} ${it.rule}" } +
                source.contested.map {
                    "${source.path}: ${filesOf(it.entry, it.sub)} -> ${it.main.name} ${it.rule} contested"
                } +
                source.unbound.map { unbound ->
                    val claimants = unbound.claimants.map { it.name }.sorted().joinToString(", ")
                    "${source.path}: ${filesOf(unbound.entry, unbound.sub)} ${unbound.reason} $claimants".trimEnd()
                }
        }

private fun spelled(episode: SeasonEpisode) = "S${episode.season}E${episode.episode}"

private fun filesOf(entry: TreeEntry, sub: TreeEntry?) = entry.relPath + sub?.let { " +${it.relPath}" }.orEmpty()
