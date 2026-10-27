package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.out.ColorMode
import org.plukh.mkvtool.out.TextRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * The substitution engine: variable resolution, the codec and language lookups, and stage-one
 * validation.
 *
 * Established differentially first — the whole engine was run against v1 over 26 language codes, every
 * codec tier, 13 templates, 7 file names across 5 metadata configurations, and 10 configs through
 * `collectTemplateFields` + `validateTemplates` including the rendered problem report. All 473 lines
 * matched. What is kept here is the subset that says why each rule exists.
 */
class SubstitutionTest : FunSpec({

    context("language names") {
        data class Case(val label: String, val code: String?, val english: String?, val native: String?)

        withData(
            nameFn = { it.label },
            Case("a two-letter code", "ru", "Russian", "Русский"),
            // Matroska carries three-letter codes, config.yaml two-letter ones; both must resolve.
            Case("its three-letter code", "rus", "Russian", "Русский"),
            Case("case does not matter", "RU", "Russian", "Русский"),
            // ISO 639-2/B, which differs from the /T code the JDK returns, and is what files carry.
            Case("a bibliographic code", "ger", "German", "Deutsch"),
            Case("its /T counterpart", "deu", "German", "Deutsch"),
            // Many languages spell their own name in lower case, which reads wrong in a track title.
            Case("the native name is upper-cased in its own locale", "el", "Greek", "Ελληνικά"),
            Case("an unknown code has no name", "zz", null, null),
            // Matroska's "untagged" is not a language, so it must not resolve to one.
            Case("und is not a language", "und", null, null),
            Case("empty", "", null, null),
            Case("null", null, null, null),
        ) {
            languageNameOf(it.code) shouldBe it.english
            languageNativeOf(it.code) shouldBe it.native
        }
    }

    context("friendlyCodec") {
        data class Case(val label: String, val codecId: String?, val display: String?, val expected: String?)

        withData(
            nameFn = { it.label },
            // Tier 1: the codec id, because mkvmerge's display string is not stable across versions —
            // v99 reports the components of AVC in a different order than older releases do.
            Case("the codec id wins", "V_MPEG4/ISO/AVC", "AVC/H.264/MPEG-4p10", "H.264"),
            Case("even against a display string that maps too", "S_TEXT/ASS", "SubStationAlpha", "ASS"),
            // Tier 2: a raw companion carries no codec id at all — a bare .ass probes with only a
            // display string.
            Case("a missing codec id falls to the display map", null, "SubStationAlpha", "ASS"),
            Case("an empty codec id does the same", "", "SubRip/SRT", "SRT"),
            // Tier 3: unmapped degrades rather than breaking.
            Case("an unmapped codec keeps mkvmerge's own name", "UNKNOWN", "Some Codec", "Some Codec"),
            Case("nothing at all yields nothing", null, null, null),
            Case("an empty display string yields nothing", null, "", null),
        ) {
            friendlyCodec(ProbedTrack(0, "audio", it.display, it.codecId, null, null, false, false)) shouldBe
                it.expected
        }

        test("a null track yields null, so an unprobed source costs no subprocess") {
            friendlyCodec(null) shouldBe null
        }
    }

    context("substitute") {
        val vars = mapOf<String, String?>("fileName" to "Base", "empty" to "", "nothing" to null)

        data class Case(val label: String, val template: String, val expected: String)

        withData(
            nameFn = { it.label },
            Case("a variable expands", "\${fileName}", "Base"),
            Case("in the middle of text", "x \${fileName} y", "x Base y"),
            Case("twice", "\${fileName}-\${fileName}", "Base-Base"),
            Case("an empty value expands to nothing", "a\${empty}b", "ab"),
            Case("a null value expands to nothing", "a\${nothing}b", "ab"),
            Case("an unknown name expands to nothing", "a\${unknown}b", "ab"),
            // Left alone by the expander on purpose: stage one has already refused these as config
            // errors, so nothing valid ever reaches here in that shape.
            Case("a malformed body is left untouched", "\${file name}", "\${file name}"),
            Case("so is a modifier", "\${a:b}", "\${a:b}"),
            Case("a lone dollar is not a variable", "$ {fileName}", "$ {fileName}"),
            Case("text with no variables", "plain", "plain"),
            Case("empty", "", ""),
        ) { substitute(it.template, vars) shouldBe it.expected }
    }

    context("fileVarsFor") {
        test("takes the episode name from metadata, and everything else from the canonical name") {
            val engine = boundEngine(
                EpisodeData("Real Show", "2011", "01", "Book One", "en-US", mapOf("01" to "From Yaml")),
                "Show - S01E01 - Title.mkv",
            )

            val vars = engine.fileVarsFor(File("Show - S01E01 - Title.mkv")).vars

            vars["fileName"] shouldBe "Show - S01E01 - Title"
            vars["extension"] shouldBe "mkv"
            vars["seasonNum"] shouldBe "01"
            vars["episodeNum"] shouldBe "01"
            vars["episodeName"] shouldBe "From Yaml"
            vars["showName"] shouldBe "Real Show"
            vars["seasonName"] shouldBe "Book One"
            vars["showYear"] shouldBe "2011"
        }

        test("falls back to the file's own canonical name when metadata has no entry") {
            val engine = boundEngine(EpisodeData(show = "Real Show", season = "01"), "Show - S01E02 - Missing.mkv")

            val vars = engine.fileVarsFor(File("Show - S01E02 - Missing.mkv")).vars

            vars["episodeName"] shouldBe "Missing"
            vars["showName"] shouldBe "Real Show"
        }

        test("an empty metadata value defers to the file name, as Groovy's elvis did") {
            // v1 chained its sources with `?:`, which falls through on an empty string as well as null.
            val engine = boundEngine(
                EpisodeData(show = "", season = "01", byEpisode = mapOf("01" to "")),
                "Show - S01E01 - Title.mkv",
            )

            val vars = engine.fileVarsFor(File("Show - S01E01 - Title.mkv")).vars

            vars["showName"] shouldBe "Show"
            vars["episodeName"] shouldBe "Title"
        }

        test("metadata for another season is not joined against a file that names its own") {
            // Episode numbers are only meaningful within one season, so this must be absent rather
            // than wrong.
            val engine = boundEngine(
                EpisodeData(show = "S2 Show", season = "02", byEpisode = mapOf("01" to "Season Two Ep")),
                "Show - S01E01 - Title.mkv",
            )

            val vars = engine.fileVarsFor(File("Show - S01E01 - Title.mkv")).vars

            vars["episodeName"] shouldBe "Title"
            vars["showName"] shouldBe "Show"
            vars["seasonName"] shouldBe null
        }

        test("episodes.txt metadata supplies names only, leaving the rest to the file name") {
            val engine = boundEngine(EpisodeData(byEpisode = mapOf("01" to "From Txt")), "Show - S01E01 - Title.mkv")

            val vars = engine.fileVarsFor(File("Show - S01E01 - Title.mkv")).vars

            vars["episodeName"] shouldBe "From Txt"
            vars["showName"] shouldBe "Show"
            vars["showYear"] shouldBe null
        }

        test("missing names every variable nothing could supply, empty counting as missing") {
            val engine = SubstitutionEngine(null)

            val vars = engine.fileVarsFor(File("no episode here.mkv"))

            vars.missing shouldContainExactly setOf(
                "seasonNum", "seasonN", "episodeNum", "episodeN", "episodeName", "showName", "seasonName", "showYear",
            )
            // Whatever the file name itself can answer is never missing.
            vars.vars["fileName"] shouldBe "no episode here"
        }

        test("a fully canonical name with no metadata leaves only the season-level fields missing") {
            val engine = boundEngine(null, "Show - S01E01 - Title.mkv")

            engine.fileVarsFor(File("Show - S01E01 - Title.mkv")).missing shouldContainExactly
                setOf("seasonName", "showYear")
        }

        test("a file with no extension reports an empty one") {
            SubstitutionEngine(null).fileVarsFor(File("noextension")).vars["extension"] shouldBe ""
        }

        test("an engine binding gave no episode resolves none, whatever the file name says") {
            val engine = SubstitutionEngine(EpisodeData(season = "01", byEpisode = mapOf("01" to "One")))

            engine.fileVarsFor(File("Show.S01E01.mkv")).missing shouldContainExactly
                setOf("episodeNum", "episodeN", "episodeName", "showName", "seasonName", "showYear")
        }

        test("results are memoized per file") {
            val engine = SubstitutionEngine(null)
            val file = File("Show - S01E01 - Title.mkv")

            engine.fileVarsFor(file) shouldBe engine.fileVarsFor(file)
        }

        test("bound, the episode is binding's, not the name's, and the title is looked up under it") {
            // A regex read 13 and the map made it episode 1; the season is the metadata's, the run's only one.
            val main = File("[Grp] Show - 13 (BD).mkv")
            val mains = listOf(MainEpisode(main, 13, 1))
            val binding = BindingResult("01", BindingRule.REGEX, mains, emptyList(), emptyList())
            val data = EpisodeData(season = "01", byEpisode = mapOf("01" to "Pilot"))
            val engine = SubstitutionEngine(data).boundTo(binding)

            val vars = engine.fileVarsFor(main).vars
            vars["seasonNum"] shouldBe "01"
            vars["episodeNum"] shouldBe "01"
            vars["episodeName"] shouldBe "Pilot"
            vars["fileName"] shouldBe "[Grp] Show - 13 (BD)"
        }

        test("episodeNum is written at the width of the season the metadata describes") {
            val data = EpisodeData(season = "01", byEpisode = mapOf("05" to "Five", "100" to "Hundred"))
            val engine = boundEngine(data, "Show - S01E05 - Five.mkv", "Show - S01E100 - Hundred.mkv")

            engine.fileVarsFor(File("Show - S01E05 - Five.mkv")).vars["episodeNum"] shouldBe "005"
            engine.fileVarsFor(File("Show - S01E100 - Hundred.mkv")).vars["episodeNum"] shouldBe "100"
        }

        test("seasonN and episodeN are the bare numbers, whatever the width or the spelling") {
            val data = EpisodeData(season = "01", byEpisode = mapOf("05" to "Five", "100" to "Hundred"))
            val engine = boundEngine(data, "Show - S01E005 - Five.mkv")

            val vars = engine.fileVarsFor(File("Show - S01E005 - Five.mkv")).vars
            vars["episodeNum"] shouldBe "005"
            vars["episodeN"] shouldBe "5"
            vars["seasonNum"] shouldBe "01"
            vars["seasonN"] shouldBe "1"
        }

        test("metadata for another season lends episodeNum no width") {
            val data = EpisodeData(season = "02", byEpisode = mapOf("120" to "Later"))
            val engine = boundEngine(data, "Show - S01E05.mkv")

            engine.fileVarsFor(File("Show - S01E05.mkv")).vars["episodeNum"] shouldBe "05"
        }

        test("bound, episodeNum takes its width from every main, a masked-out one included") {
            val five = File("Show - S01E05.mkv")
            val mains = listOf(
                MainEpisode(five, 5, 5),
                MainEpisode(File("Show - S01E100.mkv"), 100, 100),
            )
            val binding = BindingResult("01", BindingRule.SXXEYY, mains, emptyList(), emptyList())
            val engine = SubstitutionEngine().boundTo(binding)

            engine.fileVarsFor(five).vars["episodeNum"] shouldBe "005"
        }

        test("a masked-out main of another season lends episodeNum no width") {
            val five = File("Show - S01E05.mkv")
            val mains = listOf(five, File("Show - S02E120.mkv"))
            val binding = bindFiles(File("."), Config(), mains, emptyList(), run = listOf(five))

            SubstitutionEngine().boundTo(binding).fileVarsFor(five).vars["episodeNum"] shouldBe "05"
        }

        test("in a batch of two seasons each file keeps its own, and only its season's metadata") {
            val one = File("Show - S01E01.mkv")
            val two = File("Show - S02E02.mkv")
            val data = EpisodeData(season = "01", byEpisode = mapOf("01" to "One", "02" to "Two"))
            val engine = boundEngine(data, one.name, two.name)

            engine.fileVarsFor(one).vars["seasonNum"] shouldBe "01"
            engine.fileVarsFor(one).vars["episodeName"] shouldBe "One"
            engine.fileVarsFor(two).vars["seasonNum"] shouldBe "02"
            engine.fileVarsFor(two).vars["episodeName"] shouldBe null
        }
    }

    context("trackVarsFor") {
        test("resolves the language three ways and the codec from the probe") {
            val probed = ProbedTrack(0, "audio", "AC-3", "A_AC3", null, null, false, false)

            val vars = trackVarsFor("ru", probed)

            vars["language"] shouldBe "ru"
            vars["languageName"] shouldBe "Russian"
            vars["languageNative"] shouldBe "Русский"
            vars["codec"] shouldBe "AC-3"
        }

        test("an unprobed track has no codec, which is what gates the extra subprocess") {
            trackVarsFor("ru", null)["codec"] shouldBe null
        }
    }

    context("collectTemplateFields") {
        test("finds every templated value with the scope legal in it") {
            val config = mapOf(
                "general" to mapOf("title" to "\${showName}"),
                "mainSource" to mapOf(
                    "videoTrack" to mapOf("title" to "\${fileName}"),
                    "audioTracks" to listOf(mapOf("id" to 1, "language" to "ru", "title" to "\${languageName}")),
                    "subtitleTracks" to listOf(mapOf("id" to 4, "title" to "\${codec}")),
                ),
                "additionalSources" to listOf(
                    mapOf(
                        "file" to "\${fileName}[Grp].mka",
                        "tracks" to listOf(mapOf("title" to "\${language}")),
                    )
                ),
            )

            val fields = fieldsOf(config)

            fields.map { it.path } shouldContainExactly listOf(
                "general.title",
                "mainSource.videoTrack.title",
                "mainSource.audioTracks[0].title",
                "mainSource.subtitleTracks[0].title",
                "additionalSources[0].file",
                "additionalSources[0].tracks[0].title",
            )
            // A file-scope field cannot use a track variable; a title can use either.
            fields.first { it.path == "general.title" }.allowed shouldBe FILE_VARS
            fields.first { it.path == "additionalSources[0].file" }.allowed shouldBe FILE_VARS
            fields.first { it.path == "mainSource.audioTracks[0].title" }.allowed shouldBe FILE_VARS + TRACK_VARS
            fields.first { it.path == "mainSource.audioTracks[0].title" }.languageCode shouldBe "ru"
        }

        test("a declared but valueless title is still a field, so it is validated as an empty one") {
            fieldsOf(mapOf("general" to mapOf("title" to null))).single().value shouldBe null
        }

        test("a source with no file is nothing to resolve") {
            val fields = fieldsOf(
                mapOf("additionalSources" to listOf(mapOf("file" to "")))
            )

            fields.shouldBeEmpty()
        }

        test("no config means no fields") {
            collectTemplateFields(null).shouldBeEmpty()
            fieldsOf(emptyMap<String, Any>()).shouldBeEmpty()
        }

        test("a grouped config's fields are named under their group, and a shared track is named too") {
            val config = mapOf(
                "general" to mapOf("title" to "\${showName}"),
                "sharedTracks" to listOf(mapOf("name" to "jpn", "id" to 1, "title" to "\${languageName}")),
                "groups" to listOf(
                    mapOf("episodes" to "1-6", "mainSource" to mapOf("audioTracks" to listOf(mapOf("use" to "jpn")))),
                    mapOf(
                        "episodes" to "7-12",
                        "additionalSources" to listOf(
                            mapOf("file" to "\${fileName}.mka", "tracks" to listOf(mapOf("title" to "Dub"))),
                        ),
                    ),
                ),
            )

            fieldsOf(config).map { it.path } shouldContainExactly listOf(
                "general.title",
                "groups[0].mainSource.audioTracks[0].title (use: jpn)",
                "groups[1].additionalSources[0].file",
                "groups[1].additionalSources[0].tracks[0].title",
            )
            // One pass resolves general's templates and its own group's, never another group's.
            collectTemplateFields(parseConfig(config), onlyGroup = 1).map { it.path } shouldContainExactly listOf(
                "general.title",
                "groups[1].additionalSources[0].file",
                "groups[1].additionalSources[0].tracks[0].title",
            )
        }
    }

    context("validateTemplates") {
        test("reports a misspelled variable with the names legal in that field") {
            val fields = fieldsOf(mapOf("general" to mapOf("title" to "\${epsiodeName}")))

            val validation = validateTemplates(fields)

            validation.problems shouldBe 1
            validation.offenses.single().path shouldBe "general.title"
            validation.offenses.single().token shouldBe "\${epsiodeName}"
            validation.offenses.single().allowed shouldBe FILE_VARS
        }

        test("outputName is a file-scope template: an unknown variable and a track variable are both offenses") {
            val validation = validateTemplates(
                fieldsOf(mapOf("general" to mapOf("outputName" to "\${showName} - \${epsiodeName} \${codec}"))),
            )

            validation.offenses.map { it.path to it.token } shouldBe listOf(
                "general.outputName" to "\${epsiodeName}",
                "general.outputName" to "\${codec}",
            )
            validation.offenses.first().allowed shouldBe FILE_VARS
        }

        test("a track variable in a file-scope field is out of scope") {
            val validation = validateTemplates(
                fieldsOf(mapOf("general" to mapOf("title" to "\${codec}")))
            )

            validation.offenses.single().token shouldBe "\${codec}"
        }

        test("a malformed body is caught rather than surviving as a literal") {
            // The scan is deliberately looser than the variable pattern, so a body that fails to look
            // like a variable at all is still seen.
            val validation = validateTemplates(
                fieldsOf(mapOf("general" to mapOf("title" to "\${file name} \${a:b} \${}")))
            )

            validation.offenses.map { it.token } shouldContainExactly
                listOf("\${file name}", "\${a:b}", "\${}")
        }

        test("a language with no display name is a config-static problem, not a per-file one") {
            val validation = validateTemplates(
                fieldsOf(
                    mapOf(
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("language" to "zz", "title" to "\${languageName}"))
                        )
                    )
                )
            )

            validation.badLanguages.single().code shouldBe "zz"
            validation.problems shouldBe 1
        }

        test("a title asking for a language name on a track with no language at all") {
            val validation = validateTemplates(
                fieldsOf(
                    mapOf(
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("title" to "\${languageNative}"))
                        )
                    )
                )
            )

            validation.badLanguages.single().code shouldBe null
        }

        test("reports which variables the config uses, so everything derived from them stays gated") {
            val validation = validateTemplates(
                fieldsOf(
                    mapOf(
                        "general" to mapOf("title" to "\${showName} \${episodeName}"),
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("language" to "ru", "title" to "\${codec}"))
                        ),
                    )
                )
            )

            validation.usedFileVars shouldContainExactly setOf("showName", "episodeName")
            validation.usesCodec shouldBe true
            validation.problems shouldBe 0
        }

        test("a config with no codec variable costs no probe") {
            val validation = validateTemplates(
                fieldsOf(mapOf("general" to mapOf("title" to "\${showName}")))
            )

            validation.usesCodec shouldBe false
        }
    }

    context("reportTemplateProblems") {
        test("is fatal for mux and a warning for inspect, wording identical either way") {
            // mux cannot mux against a config it did not understand; inspect reports on files and can
            // report on them just as well without one.
            val fields = fieldsOf(mapOf("general" to mapOf("title" to "\${epsiodeName}")))
            val validation = validateTemplates(fields)

            val fatal = report(validation, fatal = true)
            fatal shouldContain "*** Error: config.yaml has 1 substitution problem:"
            fatal shouldContain "  general.title: \${epsiodeName}"
            fatal shouldContain "      valid here: episodeN, episodeName, episodeNum, extension, fileName, " +
                "seasonN, seasonName, seasonNum, showName, showYear"

            val warned = report(validation, fatal = false)
            warned shouldContain "*** Warning: config.yaml has 1 substitution problem:"
            warned shouldContain "  general.title: \${epsiodeName}"
        }

        test("counts every problem and names an absent language explicitly") {
            val validation = validateTemplates(
                fieldsOf(
                    mapOf(
                        "general" to mapOf("title" to "\${nope}"),
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("title" to "\${languageName}"))
                        ),
                    )
                )
            )

            val output = report(validation, fatal = true)

            output shouldContain "config.yaml has 2 substitution problems:"
            output shouldContain "  mainSource.audioTracks[0].title: no language name for '(none)'"
        }

        test("says nothing when there is nothing to say") {
            report(validateTemplates(emptyList()), fatal = true) shouldBe ""
        }

        test("names the file the templates were read from") {
            val validation = validateTemplates(fieldsOf(mapOf("general" to mapOf("title" to "\${nope}"))))

            report(validation, fatal = false, configName = "season2.yaml") shouldContain
                "*** Warning: season2.yaml has 1 substitution problem:"
        }
    }
})

private fun report(validation: TemplateValidation, fatal: Boolean, configName: String = "config.yaml"): String {
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, true, Charsets.UTF_8)
    reportTemplateProblems(validation, configName, TextRenderer(ColorMode.NEVER, stream, stream), fatal)
    return buffer.toString(Charsets.UTF_8)
}

/** The fields of a config written as a raw mapping, which is how these cases spell one: for a caller,
 *  parsing and collecting are one step, and the reported paths have to survive both. */
private fun fieldsOf(config: Map<*, *>): List<TemplateField> = collectTemplateFields(parseConfig(config))

/** [data]'s engine bound to the files [names], by the rule a config with no matcher binds with. */
private fun boundEngine(data: EpisodeData?, vararg names: String): SubstitutionEngine =
    SubstitutionEngine(data).boundTo(bindFiles(File("."), Config(), names.map(::File), emptyList(), data))
