package org.plukh.mkvtool.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The identity half of the episodes library: the SxxEyy join key, the canonical-name parser, the file-name
 * sanitizer and the two `episodes.*` shapes. The Groovy suite pins none of these directly — they are only
 * observable there through `rename`/`mux`/`inspect` subprocess runs — so the cases that came from the
 * harness name the case they came from, and the rest close a gap the oracle never covered.
 *
 * The display-only half (batch labels, ranges, membership) lives in `BatchLabelsTest`, following the line
 * the library itself draws: identity must be answerable for one file, display is batch-relative.
 */
class EpisodesTest : FunSpec({

    context("parseSeasonEpisode") {
        data class Case(
            val label: String,
            val baseName: String?,
            val expected: SeasonEpisode?,
        )

        withData(
            nameFn = { it.label },
            // harness 104: the fixtures are lower-case with dot separators
            Case("a dotted lower-case name", "Show.s01e02", SeasonEpisode("01", "02")),
            Case("the canonical upper-case name", "My Show - S01E04 - Title", SeasonEpisode("01", "04")),
            Case("the s01.e01 spelling", "Show.s01.e01", SeasonEpisode("01", "01")),
            Case("a token anywhere in the name", "[Group] Show - S01E07 (1080p)", SeasonEpisode("01", "07")),
            Case("two-digit seasons", "SHOW.S12E34", SeasonEpisode("12", "34")),
            // Groovy truth on the matcher is find(), so the groups are the leftmost match's.
            Case("the leftmost token when a name holds two", "S01E01 vs S02E02", SeasonEpisode("01", "01")),
            Case("unpadded numbers do not match", "Show S1E1", null),
            Case("three-digit numbering is out of scope", "Show s001e001", null),
            Case("only a dot separates the two halves", "Show s01_e01", null),
            Case("a name with no token at all", "Alpha", null),
            Case("an empty name", "", null),
            Case("null", null, null),
        ) { parseSeasonEpisode(it.baseName) shouldBe it.expected }
    }

    context("sanitizeForFilename") {
        data class Case(
            val label: String,
            val name: String?,
            val expected: String,
        )

        withData(
            nameFn = { it.label },
            // harness 94: the characters are deleted, not replaced — no space is invented before "Question"
            Case("illegal characters are removed, not substituted", "Slash/Colon: Question?", "SlashColon Question"),
            Case("every character Windows rejects", """a\b/c:d*e?f"g<h>i|j""", "abcdefghij"),
            // harness 38: a Cyrillic title keeps every letter and loses only the colon
            Case("non-ASCII text is untouched", "Тест: второй эпизод", "Тест второй эпизод"),
            // harness 95: a legacy episodes.txt line is already clean and must stay so
            Case("an already-clean name is unchanged", "Already Sanitized", "Already Sanitized"),
            Case("trailing dots are cut", "Episode One...", "Episode One"),
            Case("trailing spaces are cut", "Episode One   ", "Episode One"),
            Case("a mixed trailing run is cut whole", "Episode One . . ", "Episode One"),
            Case("interior dots survive", "A. B. C", "A. B. C"),
            Case("leading spaces survive — Windows accepts them", "  Episode", "  Episode"),
            Case("a name that is nothing but illegal characters", "???", ""),
            Case("an empty name", "", ""),
            // Load-bearing: this is how rename detects that no show name is available.
            Case("null", null, ""),
        ) { sanitizeForFilename(it.name) shouldBe it.expected }

        test("is idempotent, so re-sanitizing a legacy episodes.txt changes nothing") {
            val once = sanitizeForFilename("""Show: "The/One" <Part 1>. """)
            sanitizeForFilename(once) shouldBe once
        }
    }

    context("parseCanonicalName") {
        data class Case(
            val label: String,
            val baseName: String?,
            val expected: CanonicalName?,
        )

        withData(
            nameFn = { it.label },
            // harness 97: what ${episodeName} falls back to when there is no episodes.yaml
            Case(
                "rename's own output",
                "My Show - S01E04 - From Name",
                CanonicalName("My Show", "01", "04", "From Name"),
            ),
            Case(
                "a trailing suffix is stripped from the title",
                "My Show - S01E01 - Title [Salender-Raws]",
                CanonicalName("My Show", "01", "01", "Title"),
            ),
            // Greedy inside the brackets: it strips from the first one to the end.
            Case(
                "two bracket groups are stripped together",
                "My Show - S01E01 - Title [A] [B]",
                CanonicalName("My Show", "01", "01", "Title"),
            ),
            Case(
                "a title that is only a suffix comes back empty",
                "My Show - S01E01 - [Studio]",
                CanonicalName("My Show", "01", "01", ""),
            ),
            Case(
                "lower case s and e are accepted",
                "My Show - s01e04 - From Name",
                CanonicalName("My Show", "01", "04", "From Name"),
            ),
            // Non-greedy: the shortest show name that lets the rest match wins.
            Case(
                "the shortest prefix wins",
                "Show - S01E01 - S02E02 - Title",
                CanonicalName("Show", "01", "01", "S02E02 - Title"),
            ),
            Case(
                "a show name may contain the separator",
                "A - B - S01E01 - Title",
                CanonicalName("A - B", "01", "01", "Title"),
            ),
            // `$` matches before a final line terminator, as v1's find() did.
            Case(
                "a trailing newline does not spoil the match",
                "My Show - S01E01 - Title\n",
                CanonicalName("My Show", "01", "01", "Title"),
            ),
            // Unlike parseSeasonEpisode, this one has no dotted variant and no separator tolerance.
            Case("the s01.e01 spelling is not canonical", "My Show - S01.E01 - Title", null),
            Case("the separator must be space-hyphen-space", "My Show S01E01 Title", null),
            Case("a bare file name", "Show.s01e01", null),
            Case("an empty name", "", null),
            Case("null", null, null),
        ) { parseCanonicalName(it.baseName) shouldBe it.expected }
    }

    context("indexFromLines") {
        // harness 94b: the offset is the episode number of the FIRST LINE, not an amount added to an index.
        // A season downloaded in halves is what it exists for.
        test("the offset is the episode number of the first line") {
            indexFromLines(listOf("Eleventh", "Twelfth"), 11) shouldBe
                mapOf("11" to "Eleventh", "12" to "Twelfth")
        }

        // harness 104: the join key is the string parseSeasonEpisode returns, and titles stay raw
        test("keys are two-digit strings and names are verbatim") {
            indexFromLines(listOf("First One", "Second: With Colon?"), 1) shouldBe
                mapOf("01" to "First One", "02" to "Second: With Colon?")
        }

        test("a blank line becomes an empty title for its number") {
            indexFromLines(listOf("One", "", "Three"), 1) shouldBe
                mapOf("01" to "One", "02" to "", "03" to "Three")
        }

        test("lines are not trimmed") {
            indexFromLines(listOf("  padded  "), 1) shouldBe mapOf("01" to "  padded  ")
        }

        test("no lines, no index") {
            indexFromLines(emptyList(), 1) shouldBe emptyMap()
        }

        test("padding is a minimum width, so a hundredth episode keys as three digits") {
            indexFromLines(listOf("Ninety-nine", "Hundred"), 99) shouldBe
                mapOf("99" to "Ninety-nine", "100" to "Hundred")
        }

        test("an offset of zero is taken at face value") {
            indexFromLines(listOf("Pilot"), 0) shouldBe mapOf("00" to "Pilot")
        }
    }

    context("normalizeYaml") {
        test("a full document maps field for field") {
            val data = normalizeYaml(
                mapOf(
                    "show" to "My Show",
                    "year" to 2024,
                    "season" to 1,
                    "seasonName" to "Season One",
                    "language" to "en-US",
                    "episodes" to listOf(
                        mapOf("episode" to 1, "name" to "Pilot"),
                        mapOf("episode" to 2, "name" to "Real: Title?"),
                    ),
                ),
            )

            data shouldBe EpisodeData(
                show = "My Show",
                year = "2024",
                season = "01",
                seasonName = "Season One",
                language = "en-US",
                byEpisode = mapOf("01" to "Pilot", "02" to "Real: Title?"),
            )
        }

        test("absent metadata is null, not empty — the episodes.txt path leaves all of it unset") {
            normalizeYaml(mapOf("episodes" to listOf(mapOf("episode" to 1, "name" to "One")))) shouldBe
                EpisodeData(byEpisode = mapOf("01" to "One"))
        }

        data class Case(
            val label: String,
            val yaml: Map<String, Any?>,
            val expected: Map<String, String>,
        )

        withData(
            nameFn = { it.label },
            // The sparseness is deliberate: a gap in a season is simply a missing key, and every consumer
            // handles a miss rather than assuming a contiguous run.
            Case(
                "a gap in the season is a missing key",
                mapOf("episodes" to listOf(mapOf("episode" to 1, "name" to "One"), mapOf("episode" to 5, "name" to "Five"))),
                mapOf("01" to "One", "05" to "Five"),
            ),
            Case(
                "an entry with no episode number is skipped",
                mapOf("episodes" to listOf(mapOf("name" to "Nameless"), mapOf("episode" to 2, "name" to "Two"))),
                mapOf("02" to "Two"),
            ),
            Case(
                "a null episode number is skipped",
                mapOf("episodes" to listOf(mapOf("episode" to null, "name" to "Nameless"))),
                emptyMap(),
            ),
            Case("a null entry is skipped", mapOf("episodes" to listOf(null)), emptyMap()),
            Case(
                "a missing name is an empty title",
                mapOf("episodes" to listOf(mapOf("episode" to 3))),
                mapOf("03" to ""),
            ),
            Case(
                "a null name is an empty title",
                mapOf("episodes" to listOf(mapOf("episode" to 3, "name" to null))),
                mapOf("03" to ""),
            ),
            Case(
                "the last of two entries for one number wins",
                mapOf("episodes" to listOf(mapOf("episode" to 1, "name" to "First"), mapOf("episode" to 1, "name" to "Second"))),
                mapOf("01" to "Second"),
            ),
            // A hand-edited yaml may quote its numbers; Groovy's `as int` accepted that and so must this.
            Case(
                "a quoted number is still a number",
                mapOf("episodes" to listOf(mapOf("episode" to "12", "name" to "Twelve"))),
                mapOf("12" to "Twelve"),
            ),
            Case(
                "a fractional number truncates",
                mapOf("episodes" to listOf(mapOf("episode" to 1.9, "name" to "One"))),
                mapOf("01" to "One"),
            ),
            Case(
                "a non-string name is stringified",
                mapOf("episodes" to listOf(mapOf("episode" to 1, "name" to 2024))),
                mapOf("01" to "2024"),
            ),
            Case("no episodes key at all", mapOf("show" to "My Show"), emptyMap()),
            Case("a null episodes key", mapOf("episodes" to null), emptyMap()),
            Case("an empty episode list", mapOf("episodes" to emptyList<Any>()), emptyMap()),
        ) { normalizeYaml(it.yaml).byEpisode shouldBe it.expected }

        test("a missing season stays null rather than padding to 00") {
            normalizeYaml(mapOf("show" to "My Show")).season shouldBe null
        }

        test("a numeric show name is stringified") {
            normalizeYaml(mapOf("show" to 1984)).show shouldBe "1984"
        }

        // Throwing is the contract: callers run this as loadMapping's transform, inside the guard.
        context("rejects what it cannot read") {
            test("an episode number that is not a number") {
                shouldThrow<NumberFormatException> {
                    normalizeYaml(mapOf("episodes" to listOf(mapOf("episode" to "one", "name" to "Pilot"))))
                }
            }

            test("a season that is not a number") {
                shouldThrow<NumberFormatException> { normalizeYaml(mapOf("season" to "one")) }
            }

            test("an episode number that is neither a number nor a string") {
                shouldThrow<IllegalArgumentException> {
                    normalizeYaml(mapOf("episodes" to listOf(mapOf("episode" to true))))
                }.message shouldContain "episode number is not a number"
            }

            test("an episodes value that is not a list") {
                shouldThrow<IllegalArgumentException> { normalizeYaml(mapOf("episodes" to "Pilot")) }
                    .message shouldContain "episodes is not a list"
            }

            test("an episode entry that is not a mapping") {
                shouldThrow<IllegalArgumentException> { normalizeYaml(mapOf("episodes" to listOf("Pilot"))) }
                    .message shouldContain "an episode is not a mapping"
            }
        }

        // The 2.1 seam: the transform runs inside loadMapping's guard, which is the only reason this
        // function is allowed to throw at all. Harness 118b/119b turn exactly this into a warning and an
        // exit 2 respectively.
        context("through the loader") {
            test("a hand-edited episode number becomes a classified problem, not a stack trace") {
                val file = File(tempdir(), "episodes.yaml").apply {
                    writeText("show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n", StandardCharsets.UTF_8)
                }
                val load = loadMapping(file, StandardCharsets.UTF_8) { normalizeYaml(it) }
                load.shouldBeInstanceOf<MappingLoad.Problem>()
                load.message shouldContain "could not parse episodes.yaml"
            }

            test("a good document normalises inside the guard") {
                val file = File(tempdir(), "episodes.yaml").apply {
                    writeText(
                        "show: Волчица и пряности\nseason: 1\nepisodes:\n  - episode: 1\n    name: Пилот\n",
                        StandardCharsets.UTF_8,
                    )
                }
                val load = loadMapping(file, StandardCharsets.UTF_8) { normalizeYaml(it) }
                load.shouldBeInstanceOf<MappingLoad.Loaded<EpisodeData>>()
                load.value shouldBe EpisodeData(
                    show = "Волчица и пряности",
                    season = "01",
                    byEpisode = mapOf("01" to "Пилот"),
                )
            }
        }
    }
})
