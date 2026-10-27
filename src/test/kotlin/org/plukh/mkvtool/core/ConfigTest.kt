package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * The config model: what a `config.yaml` means once read, and the two things derived from it — which
 * tracks are selected, and which values are templates.
 *
 * Reading is lenient by design. A config is optional throughout `inspect`, so a field of the wrong shape
 * has to come back absent rather than throwing; whether an unusable config is fatal is the *caller's*
 * policy, and the classification it acts on comes from the loader.
 */
class ConfigTest : FunSpec({

    fun write(text: String): File = File(tempdir(), "config.yaml").apply { writeText(text, Charsets.UTF_8) }

    context("loading") {
        test("a real config comes back as the model") {
            val file = write(
                """
                general:
                  destinationDir: mkv
                  allowedExtensions: [mkv, avi]
                mainSource:
                  videoTrack:
                    language: en
                  audioTracks:
                    - id: 2
                      language: en
                      title: English
                      default: true
                trackOrder: "0:0,0:2"
                """.trimIndent(),
            )

            val config = loadConfig(file).shouldBeInstanceOf<MappingLoad.Loaded<Config>>().value
            config.general.destinationDir shouldBe "mkv"
            config.general.allowedExtensions shouldBe setOf("mkv", "avi")
            config.mainSource.videoTrack?.language shouldBe "en"
            config.mainSource.audioTracks.single() shouldBe
                TrackConfig(id = 2, language = "en", title = Template("English"), default = true)
            config.trackOrder shouldBe "0:0,0:2"
        }

        test("the loader's four classes carry through unchanged") {
            // The classification is 2.1's; what matters here is that a config uses it rather than
            // inventing its own, so every caller can apply one policy to one shape.
            loadConfig(write("")).shouldBeInstanceOf<MappingLoad.Problem>()
                .message shouldContain "is empty"
            loadConfig(write("- one\n- two\n")).shouldBeInstanceOf<MappingLoad.Problem>()
                .message shouldContain "is not a mapping"
            loadConfig(write("general: [unclosed\n")).shouldBeInstanceOf<MappingLoad.Problem>()
                .message shouldContain "could not parse"
            loadConfig(File(tempdir(), "absent.yaml")).shouldBeInstanceOf<MappingLoad.Problem>()
        }
    }

    context("general") {
        test("everything is optional, and an empty config is a usable one") {
            val config = parseConfig(emptyMap<String, Any>())

            config.general shouldBe GeneralConfig()
            config.mainSource shouldBe MainSourceConfig()
            config.additionalSources.shouldBeEmpty()
            config.trackOrder shouldBe null
        }

        test("an empty allowedExtensions list is no list at all, so the caller's default stands") {
            parseConfig(mapOf("general" to mapOf("allowedExtensions" to emptyList<String>())))
                .general.allowedExtensions shouldBe null
        }

        test("a blank mkvmergeExe is no path, so the caller auto-detects") {
            parseConfig(mapOf("general" to mapOf("mkvmergeExe" to "")))
                .general.mkvmergeExe shouldBe null
        }

        test("an empty trackOrder is no order, so the derived one stands") {
            parseConfig(mapOf("trackOrder" to "")).trackOrder shouldBe null
        }

        test("numbers YAML 1.1 would misread arrive as written") {
            // The loader keeps 010 and 1:0 as text; a numeric field converts it, a text field keeps it.
            val config = loadConfig(
                write(
                    "sharedTracks:\n  - name: 08\n    id: 010\ntrackOrder: 1:0\n" +
                        "mainSource:\n  audioTracks:\n    - use: 08\n",
                ),
            ).shouldBeInstanceOf<MappingLoad.Loaded<Config>>().value

            config.mainSource.audioTracks.single() shouldBe TrackConfig(id = 10, ref = "08")
            config.trackOrder shouldBe "1:0"
        }
    }

    context("a declared template is not the same as an absent one") {
        test("an absent title falls back to the default; a valueless one overrides it") {
            // The distinction the whole substitution stage rests on, and v1 drew it with containsKey
            // rather than truthiness for exactly this reason.
            parseConfig(mapOf("general" to emptyMap<String, Any>())).general.title shouldBe null
            parseConfig(mapOf("general" to mapOf("title" to null))).general.title shouldBe Template(null)
            parseConfig(mapOf("general" to mapOf("title" to ""))).general.title shouldBe Template("")
            parseConfig(mapOf("general" to mapOf("title" to "Fixed"))).general.title shouldBe Template("Fixed")
        }
    }

    context("tracks") {
        fun audio(vararg tracks: Map<String, Any?>) =
            parseConfig(mapOf("mainSource" to mapOf("audioTracks" to tracks.toList())))
                .mainSource.audioTracks

        test("an id written as a string is still an id, as v1's coercion made it") {
            audio(mapOf("id" to "2")).single().id shouldBe 2
            audio(mapOf("id" to 2)).single().id shouldBe 2
        }

        test("an unreadable or absent id is absent rather than an exception") {
            audio(mapOf("id" to "two")).single().id shouldBe null
            audio(mapOf("language" to "en")).single().id shouldBe null
        }

        test("default is off unless it is truly on") {
            audio(mapOf("id" to 1)).single().default shouldBe false
            audio(mapOf("id" to 1, "default" to null)).single().default shouldBe false
            audio(mapOf("id" to 1, "default" to false)).single().default shouldBe false
            audio(mapOf("id" to 1, "default" to true)).single().default shouldBe true
        }

        test("a subtitle charset is carried through, and absent when not given") {
            val subs = parseConfig(
                mapOf(
                    "mainSource" to mapOf(
                        "subtitleTracks" to listOf(
                            mapOf("id" to 6, "charset" to "windows-1251"),
                            mapOf("id" to 7),
                        ),
                    ),
                ),
            ).mainSource.subtitleTracks

            subs.map { it.charset } shouldBe listOf("windows-1251", null)
        }

        test("an absent track list and an empty one both mean copy none of that type") {
            parseConfig(mapOf("mainSource" to emptyMap<String, Any>())).mainSource.audioTracks.shouldBeEmpty()
            parseConfig(mapOf("mainSource" to mapOf("audioTracks" to emptyList<Any>())))
                .mainSource.audioTracks.shouldBeEmpty()
        }

        test("an additional source carries its file, tracks and options, and a track has no id") {
            val source = parseConfig(
                mapOf(
                    "additionalSources" to listOf(
                        mapOf(
                            "file" to "\${fileName}[Grp].mka",
                            "tracks" to listOf(mapOf("language" to "ru", "default" to true)),
                            "additionalOptions" to listOf("--compression", "0:none"),
                        ),
                    ),
                ),
            ).additionalSources.single()

            source.file shouldBe "\${fileName}[Grp].mka"
            source.tracks.single() shouldBe SourceTrackConfig(language = "ru", default = true)
            source.additionalOptions shouldBe listOf("--compression", "0:none")
        }

        context("an id key on an additional-source track is recorded, whatever its value") {
            data class IdCase(val label: String, val idLine: String?, val declared: Boolean)

            withData(
                nameFn = { it.label },
                IdCase("no id key", null, false),
                IdCase("a real-looking id", "id: 1", true),
                IdCase("the harmless truth, 0", "id: 0", true),
                IdCase("a quoted 0", "id: \"0\"", true),
                IdCase("an empty id", "id:", true),
            ) { (_, idLine, declared) ->
                val file = write(
                    buildString {
                        append("additionalSources:\n  - file: a.mka\n    tracks:\n      - language: ru\n")
                        idLine?.let { append("        $it\n") }
                    },
                )

                val config = loadConfig(file).shouldBeInstanceOf<MappingLoad.Loaded<Config>>().value
                val track = config.additionalSources.single().tracks.single()
                track.idDeclared shouldBe declared
                track.language shouldBe "ru"
            }
        }
    }

    context("additionalSourceIdProblems") {
        test("names every track that sets an id, by config path, in order") {
            val config = Config(
                additionalSources = listOf(
                    AdditionalSource(file = "a.mka", tracks = listOf(SourceTrackConfig(idDeclared = true))),
                    AdditionalSource(
                        file = "b.ass",
                        tracks = listOf(SourceTrackConfig(), SourceTrackConfig(idDeclared = true)),
                    ),
                ),
            )

            additionalSourceIdProblems(config, "config.yaml") shouldBe listOf(
                "config.yaml sets an id on additionalSources[0].tracks[0], which should be removed: " +
                    "an additional-source track is always track 0",
                "config.yaml sets an id on additionalSources[1].tracks[1], which should be removed: " +
                    "an additional-source track is always track 0",
            )
        }

        test("a line names the file the config was read from") {
            val config = Config(
                additionalSources = listOf(
                    AdditionalSource(file = "a.mka", tracks = listOf(SourceTrackConfig(idDeclared = true))),
                ),
            )

            additionalSourceIdProblems(config, "season2.yaml").single() shouldStartWith
                "season2.yaml sets an id on additionalSources[0].tracks[0]"
        }

        test("a config with no ids on its additional sources has no problem") {
            additionalSourceIdProblems(
                Config(additionalSources = listOf(AdditionalSource(file = "a.mka", tracks = listOf(SourceTrackConfig())))),
                "config.yaml",
            ).shouldBeEmpty()
        }
    }

    context("a malformed config is read, not rejected") {
        test("a scalar where a mapping belongs simply yields nothing there") {
            val config = parseConfig(mapOf("general" to "nonsense", "mainSource" to 42))

            config.general shouldBe GeneralConfig()
            config.mainSource.videoTrack shouldBe null
        }

        test("entries that are not mappings are dropped rather than throwing") {
            parseConfig(mapOf("mainSource" to mapOf("audioTracks" to listOf("junk", mapOf("id" to 1)))))
                .mainSource.audioTracks.map { it.id } shouldBe listOf(1)
        }
    }

    context("trackSelectionOf") {
        test("no config selects nothing, so nothing can block") {
            trackSelectionOf(null) shouldBe TrackSelection.NONE
            trackSelectionOf(null).hasConfig shouldBe false
        }

        test("video is always track 0 when there is a config at all, because mux hardcodes it") {
            trackSelectionOf(parseConfig(emptyMap<String, Any>()).mainSource).videoIds shouldBe setOf(0)
        }

        test("the selected ids are the configured ones, per type") {
            val selection = trackSelectionOf(
                parseConfig(
                    mapOf(
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("id" to 1), mapOf("id" to 2)),
                            "subtitleTracks" to listOf(mapOf("id" to 4)),
                        ),
                    ),
                ).mainSource,
            )

            selection.audioIds shouldBe setOf(1, 2)
            selection.subtitleIds shouldBe setOf(4)
            selection.selectedIds shouldBe setOf(0, 1, 2, 4)
        }

        test("titles are keyed by id across both track types, for naming a finding") {
            val selection = trackSelectionOf(
                parseConfig(
                    mapOf(
                        "mainSource" to mapOf(
                            "audioTracks" to listOf(mapOf("id" to 1, "title" to "Japanese")),
                            "subtitleTracks" to listOf(mapOf("id" to 4, "title" to "Subs")),
                        ),
                    ),
                ).mainSource,
            )

            selection.titleById shouldBe mapOf(1 to "Japanese", 4 to "Subs")
        }

        test("a valueless title names no title, which is how the report reads it") {
            trackSelectionOf(
                parseConfig(
                    mapOf("mainSource" to mapOf("audioTracks" to listOf(mapOf("id" to 1, "title" to null)))),
                ).mainSource,
            ).titleById shouldBe emptyMap()
        }

        test("a track with no id selects nothing, rather than a null that matches nothing") {
            val config = parseConfig(mapOf("mainSource" to mapOf("audioTracks" to listOf(mapOf("language" to "en")))))

            val selection = trackSelectionOf(config.mainSource)

            selection.audioIds.shouldBeEmpty()
            selection.selectedIds shouldBe setOf(0)
        }
    }

    fun yaml(text: String): Config =
        loadConfig(write(text.trimIndent())).shouldBeInstanceOf<MappingLoad.Loaded<Config>>().value

    context("groups") {
        test("a config without groups is one implicit group made of its top level") {
            val config = yaml(
                """
                mainSource:
                  audioTracks:
                    - id: 1
                additionalSources:
                  - file: a.mka
                trackOrder: "0:0,0:1,1:0"
                """,
            )

            config.declaredGroups shouldBe null
            config.groups shouldBe listOf(
                GroupConfig(null, null, config.mainSource, config.additionalSources, config.trackOrder),
            )
        }

        test("declared groups carry their own membership and selection, in config order") {
            val config = yaml(
                """
                groups:
                  - name: bd
                    episodes: [1-10]
                    mainSource:
                      audioTracks:
                        - id: 1
                    trackOrder: "0:0,0:1"
                  - files: ["*OVA*"]
                    additionalSources:
                      - file: b.mka
                """,
            )

            config.groups shouldBe config.declaredGroups
            config.groups shouldBe listOf(
                GroupConfig(
                    name = "bd",
                    membership = Membership(episodes = listOf(1..10)),
                    mainSource = MainSourceConfig(audioTracks = listOf(TrackConfig(id = 1))),
                    trackOrder = "0:0,0:1",
                ),
                GroupConfig(
                    membership = Membership(files = listOf("*OVA*")),
                    additionalSources = listOf(AdditionalSource(file = "b.mka")),
                ),
            )
        }

        test("a groups key holding nothing still declares groups") {
            yaml("groups:\n").declaredGroups shouldBe emptyList()
            yaml("groups: nonsense\n").declaredGroups shouldBe emptyList()
        }

        context("episode tokens come from a list or a scalar, and a bad one is kept as written") {
            data class EpisodesCase(
                val label: String,
                val episodes: String,
                val ranges: List<IntRange>,
                val malformed: List<String>,
            )

            withData(
                nameFn = { it.label },
                EpisodesCase("a list of ranges and numbers", "[1-10, 12]", listOf(1..10, 12..12), emptyList()),
                EpisodesCase("a comma-separated scalar", "\"1-10, 12\"", listOf(1..10, 12..12), emptyList()),
                EpisodesCase("a bare number", "7", listOf(7..7), emptyList()),
                EpisodesCase("spaces around the dash", "[3 - 5]", listOf(3..5), emptyList()),
                EpisodesCase("zero-padded", "[08, 010, 008-012]", listOf(8..8, 10..10, 8..12), emptyList()),
                EpisodesCase("not ranges", "[5-3, 0, x, 2-]", emptyList(), listOf("5-3", "0", "x", "2-")),
            ) { (_, episodes, ranges, malformed) ->
                val membership = yaml("groups:\n  - episodes: $episodes\n").groups.single().membership!!

                membership.episodes shouldBe ranges
                membership.malformedEpisodes shouldBe malformed
            }
        }

        test("a scalar files is one mask") {
            yaml("groups:\n  - files: \"*OVA*\"\n").groups.single().membership!!.files shouldBe listOf("*OVA*")
        }
    }

    context("sharedTracks") {
        fun audioWith(entry: String): TrackConfig = yaml(
            """
            sharedTracks:
              - name: jpn
                id: 1
                language: ja
                title: Japanese
                default: true
            mainSource:
              audioTracks:
                - $entry
            """,
        ).mainSource.audioTracks.single()

        test("use lays the definition under the entry, and records the name") {
            audioWith("use: jpn") shouldBe
                TrackConfig(id = 1, language = "ja", title = Template("Japanese"), default = true, ref = "jpn")
        }

        context("every key on the entry overrides the definition, absent-versus-declared intact") {
            data class OverrideCase(val label: String, val entry: String, val expected: TrackConfig)

            val base = TrackConfig(id = 1, language = "ja", title = Template("Japanese"), default = true, ref = "jpn")
            withData(
                nameFn = { it.label },
                OverrideCase("default: false beats true", "{use: jpn, default: false}", base.copy(default = false)),
                OverrideCase("an empty title clears it", "{use: jpn, title: }", base.copy(title = Template(null))),
                OverrideCase("the id", "{use: jpn, id: 3}", base.copy(id = 3)),
                OverrideCase("the language", "{use: jpn, language: en}", base.copy(language = "en")),
            ) { (_, entry, expected) ->
                audioWith(entry) shouldBe expected
            }
        }

        test("an unknown use keeps the entry's own keys and records the name") {
            audioWith("{use: jnp, id: 2}") shouldBe TrackConfig(id = 2, ref = "jnp")
        }

        test("an additional-source track can use one, and inherits its id refusal") {
            val config = yaml(
                """
                sharedTracks:
                  - name: rus
                    id: 0
                    language: ru
                additionalSources:
                  - file: a.mka
                    tracks:
                      - use: rus
                """,
            )

            config.additionalSources.single().tracks.single() shouldBe
                SourceTrackConfig(language = "ru", idDeclared = true, ref = "rus")
            additionalSourceIdProblems(config, "config.yaml").single() shouldStartWith
                "config.yaml sets an id on additionalSources[0].tracks[0] (use: rus),"
        }

        test("a group's entries resolve against the same definitions") {
            yaml(
                """
                sharedTracks:
                  - name: jpn
                    id: 1
                groups:
                  - episodes: 1
                    mainSource:
                      audioTracks:
                        - use: jpn
                """,
            ).groups.single().mainSource.audioTracks.single() shouldBe TrackConfig(id = 1, ref = "jpn")
        }

        test("of two definitions with one name, the first wins, as a reader meets it") {
            yaml(
                """
                sharedTracks:
                  - name: jpn
                    id: 1
                  - name: jpn
                    id: 2
                mainSource:
                  audioTracks:
                    - use: jpn
                """,
            ).mainSource.audioTracks.single().id shouldBe 1
        }

        test("the video track can use one too") {
            yaml(
                """
                sharedTracks:
                  - name: jpnVideo
                    language: ja
                    title: Show
                mainSource:
                  videoTrack:
                    use: jpnVideo
                    title: Other
                """,
            ).mainSource.videoTrack shouldBe
                VideoTrackConfig(language = "ja", title = Template("Other"), ref = "jpnVideo")
        }

        test("a use with no value is recorded as empty, so validation can name it") {
            audioWith("{use: , id: 2}") shouldBe TrackConfig(id = 2, ref = "")
        }
    }

    context("auto") {
        // `auto: <hash>` is reserved for a future config generator to mark the entries it wrote.
        test("an auto key on every kind of entry changes nothing the loader reads") {
            val withAuto = """
                sharedTracks:
                  - name: jpn
                    auto: a1b2
                    id: 1
                groups:
                  - name: bd
                    auto: a1b2
                    episodes: [1-2]
                    mainSource:
                      videoTrack:
                        auto: a1b2
                        language: ja
                      audioTracks:
                        - use: jpn
                          auto: a1b2
                      subtitleTracks:
                        - id: 4
                          auto: a1b2
                    additionalSources:
                      - file: a.mka
                        auto: a1b2
                        tracks:
                          - language: ru
                            auto: a1b2
                """

            yaml(withAuto) shouldBe yaml(withAuto.lines().filterNot { "auto:" in it }.joinToString("\n"))
        }
    }

    context("groupProblems") {
        data class ProblemCase(val label: String, val yaml: String, val problems: List<String>)

        val grouped = "groups:\n  - name: bd\n    episodes: [1-6]\n  - name: tv\n    episodes: [7-12]\n"
        withData(
            nameFn = { it.label },
            ProblemCase("a config without groups", "mainSource: {audioTracks: [{id: 1}]}\n", emptyList()),
            ProblemCase("a valid grouped config", grouped, emptyList()),
            ProblemCase(
                "groups listing none",
                "groups: []\n",
                listOf("config.yaml declares groups but lists none"),
            ),
            ProblemCase(
                "a top level beside groups",
                grouped + "mainSource: {audioTracks: [{id: 1}]}\ntrackOrder: \"0:0\"\n",
                listOf(
                    "config.yaml sets mainSource, trackOrder at the top level beside groups, where nothing " +
                        "reads it; each group carries its own",
                ),
            ),
            ProblemCase(
                "a group claiming nothing",
                "groups:\n  - name: bd\n",
                listOf("config.yaml has no episodes or files for groups[0] (bd)"),
            ),
            ProblemCase(
                "a malformed episode token",
                "groups:\n  - episodes: [1-3, 5-4]\n",
                listOf("config.yaml has an episode range in groups[0] that is not one: '5-4'"),
            ),
            ProblemCase(
                "two groups with one name",
                "groups:\n  - name: bd\n    episodes: 1\n  - name: bd\n    episodes: 2\n",
                listOf("config.yaml has two groups named 'bd'"),
            ),
            ProblemCase(
                "overlapping ranges, named",
                "groups:\n  - name: bd\n    episodes: [1-6, 9]\n  - name: tv\n    episodes: [5-9]\n",
                listOf("config.yaml claims episodes 5-6, 9 in both groups[0] (bd) and groups[1] (tv)"),
            ),
            ProblemCase(
                "overlaps that nest or touch, merged",
                "groups:\n  - name: bd\n    episodes: [4-5, 4-6, 7]\n  - name: tv\n    episodes: [1-10]\n",
                listOf("config.yaml claims episodes 4-7 in both groups[0] (bd) and groups[1] (tv)"),
            ),
            ProblemCase(
                "only blank file masks claim nothing",
                "groups:\n  - name: bd\n    files: [\"\", \" \"]\n",
                listOf("config.yaml has no episodes or files for groups[0] (bd)"),
            ),
            ProblemCase(
                "an empty shared-track name is no name",
                "sharedTracks:\n  - name: \"\"\n    id: 1\nmainSource:\n  audioTracks:\n    - use: \"\"\n",
                listOf(
                    "config.yaml has no name for sharedTracks[0]",
                    "config.yaml has an empty use at mainSource.audioTracks[0]",
                ),
            ),
            ProblemCase(
                "a use with no value",
                "mainSource:\n  audioTracks:\n    - use:\n      id: 1\n",
                listOf("config.yaml has an empty use at mainSource.audioTracks[0]"),
            ),
            ProblemCase(
                "a shared track with no name",
                "sharedTracks:\n  - id: 1\n",
                listOf("config.yaml has no name for sharedTracks[0]"),
            ),
            ProblemCase(
                "two shared tracks, one name",
                "sharedTracks:\n  - name: jpn\n  - name: jpn\n",
                listOf("config.yaml has two sharedTracks named 'jpn'"),
            ),
            ProblemCase(
                "a shared track using another",
                "sharedTracks:\n  - name: jpn\n  - name: eng\n    use: jpn\n",
                listOf("config.yaml has a use on sharedTracks[1], but a shared track cannot build on another"),
            ),
            ProblemCase(
                "an unknown use, top level",
                "mainSource:\n  subtitleTracks:\n    - use: eng\n",
                listOf("config.yaml uses an unknown shared track 'eng' at mainSource.subtitleTracks[0]"),
            ),
            ProblemCase(
                "an unknown use on the video track",
                "mainSource:\n  videoTrack:\n    use: jpnVideo\n",
                listOf("config.yaml uses an unknown shared track 'jpnVideo' at mainSource.videoTrack"),
            ),
            ProblemCase(
                "an unknown use, in a group",
                "groups:\n  - episodes: 1\n    additionalSources:\n      - file: a.mka\n        tracks:\n" +
                    "          - use: rus\n",
                listOf("config.yaml uses an unknown shared track 'rus' at groups[0].additionalSources[0].tracks[0]"),
            ),
        ) { (_, text, problems) ->
            groupProblems(yaml(text), "config.yaml") shouldBe problems
        }

        test("a line names the file the config was read from") {
            groupProblems(yaml("groups: []\n"), "season2.yaml").single() shouldStartWith "season2.yaml "
        }
    }

    context("partitionByGroup") {
        val dir = File("media")
        fun files(vararg names: String) = names.map { File(dir, it) }
        val episodes = mapOf("E01.mkv" to 1, "E02.mkv" to 2, "E03.mkv" to 3, "OVA.mkv" to null)
        val episodeOf = { file: File -> episodes[file.name] }
        fun group(name: String, vararg ranges: IntRange, masks: List<String> = emptyList()) =
            GroupConfig(name = name, membership = Membership(episodes = ranges.toList(), files = masks))

        test("a two-group season splits by range, and a mask claims what has no episode") {
            val partition = partitionByGroup(
                listOf(group("bd", 1..2), group("tv", 3..3, masks = listOf("OVA*"))),
                files("E01.mkv", "E02.mkv", "E03.mkv", "OVA.mkv"),
                dir,
                episodeOf,
            )

            partition.members shouldBe listOf(files("E01.mkv", "E02.mkv"), files("E03.mkv", "OVA.mkv"))
            partition.doubleClaimed shouldBe emptyMap()
            partition.unclaimed.shouldBeEmpty()
        }

        test("a file claimed by two groups is in neither, by range or by mask alike") {
            val partition = partitionByGroup(
                listOf(group("bd", 1..2), group("tv", 2..3, masks = listOf("E01*"))),
                files("E01.mkv", "E02.mkv", "E03.mkv"),
                dir,
                episodeOf,
            )

            partition.members shouldBe listOf(emptyList(), files("E03.mkv"))
            partition.doubleClaimed shouldBe
                mapOf(File(dir, "E01.mkv") to listOf(0, 1), File(dir, "E02.mkv") to listOf(0, 1))
        }

        test("a file no group claims is unclaimed, and one with no episode cannot match a range") {
            val partition = partitionByGroup(
                listOf(group("bd", 1..2)),
                files("E01.mkv", "E03.mkv", "OVA.mkv"),
                dir,
                episodeOf,
            )

            partition.members shouldBe listOf(files("E01.mkv"))
            partition.unclaimed shouldBe files("E03.mkv", "OVA.mkv")
        }

        test("the implicit group claims every file") {
            val all = files("E01.mkv", "OVA.mkv")

            partitionByGroup(yaml("mainSource: {}\n").groups, all, dir, episodeOf).members shouldBe listOf(all)
        }
    }

    context("effectiveSelection") {
        data class SelectionCase(val label: String, val first: String, val second: String, val equal: Boolean)

        // Two groups' selections, each written as one group's body under the same shared definition.
        fun selections(first: String, second: String): Pair<EffectiveSelection, EffectiveSelection> {
            val groups = yaml(
                """
                |sharedTracks:
                |  - name: jpn
                |    id: 1
                |    language: ja
                |    default: true
                |groups:
                |  - episodes: 1
                |${first.prependIndent("    ")}
                |  - episodes: 2
                |${second.prependIndent("    ")}
                """.trimMargin(),
            ).groups
            return groups[0].effectiveSelection() to groups[1].effectiveSelection()
        }

        val inline = "mainSource:\n  audioTracks:\n    - {id: 1, language: ja, default: true}"
        withData(
            nameFn = { it.label },
            SelectionCase(
                "a use and the same keys inline",
                "mainSource:\n  audioTracks:\n    - use: jpn",
                inline,
                true,
            ),
            SelectionCase(
                "an override and the same keys inline",
                "mainSource:\n  audioTracks:\n    - {use: jpn, default: false}",
                "mainSource:\n  audioTracks:\n    - {id: 1, language: ja, default: false}",
                true,
            ),
            SelectionCase(
                "an override that makes a difference",
                "mainSource:\n  audioTracks:\n    - {use: jpn, language: en}",
                inline,
                false,
            ),
            SelectionCase("a written order equal to the derived one", "$inline\ntrackOrder: '0:0, 0:1'", inline, true),
            SelectionCase("a written order that differs", "$inline\ntrackOrder: '0:1,0:0'", inline, false),
            SelectionCase("a written order with an empty entry", "$inline\ntrackOrder: '0:0,,0:1'", inline, false),
            SelectionCase(
                "a source with another mask",
                "additionalSources:\n  - files: 'Rus/*.mka'",
                "additionalSources:\n  - files: 'Eng/*.mka'",
                false,
            ),
        ) { (_, first, second, equal) ->
            val (a, b) = selections(first, second)

            (a == b) shouldBe equal
        }
    }

    context("matchers") {
        test("a v1-shaped config sets no matcher and no outputName") {
            val config = yaml("general: {destinationDir: mkv}\nadditionalSources:\n  - file: \"\${fileName}.mka\"\n")

            config.general.matching shouldBe MatcherConfig()
            config.general.outputName shouldBe null
            config.additionalSources.single().files shouldBe null
        }

        test("general carries the main files' matcher keys and outputName, as written") {
            val general = yaml(
                """
                general:
                  matcher: sequence
                  episodeRegex: ' - (\d+) '
                  seasonRegex: 'S(\d+)'
                  episodeMap:
                    13-24: 1-12
                  outputName: '${'$'}{showName} - ${'$'}{episodeName}'
                """,
            ).general

            general.matching shouldBe MatcherConfig(
                matcher = "sequence",
                episodeRegex = """ - (\d+) """,
                seasonRegex = """S(\d+)""",
                episodeMap = EpisodeMap(listOf(EpisodeMapEntry("13-24", "1-12"))),
            )
            general.outputName shouldBe Template("\${showName} - \${episodeName}")
        }

        test("a source carries files and its own matcher keys") {
            val source = yaml(
                "additionalSources:\n  - files: 'Rus sound/[MC-Ent]/*.mka'\n    matcher: sequence\n",
            ).additionalSources.single()

            source.files shouldBe listOf("Rus sound/[MC-Ent]/*.mka")
            source.matching shouldBe MatcherConfig(matcher = "sequence")
        }

        test("files may be a list, and blank masks are dropped, down to an empty list") {
            val sources = yaml(
                "additionalSources:\n  - files: [a/*.mka, ' ', b/*.mka]\n  - files: ['']\n",
            ).additionalSources

            sources.map { it.files } shouldBe listOf(listOf("a/*.mka", "b/*.mka"), emptyList())
        }

        test("a matcher key with no value is declared, as empty") {
            yaml("general:\n  matcher:\n").general.matching shouldBe MatcherConfig(matcher = "")
        }

        context("an episode map entry reads both sides as ranges") {
            withData(
                nameFn = { it.first },
                "13-24: 1-12" to (13..24 to 1..12),
                "25: 13" to (25..25 to 13..13),
                // A release may number from 0, and the loader keeps a zero-padded 010 from becoming octal 8.
                "0: 13" to (0..0 to 13..13),
                "010: 10" to (10..10 to 10..10),
            ) { (line, ranges) ->
                val entry = yaml("general:\n  episodeMap:\n    $line\n").general.matching.episodeMap!!.entries.single()

                (entry.fromRange to entry.toRange) shouldBe ranges
            }
        }

    }

    context("bindingProblems") {
        data class ProblemCase(val label: String, val yaml: String, val problems: List<String>)

        val valid = """
            general:
              episodeRegex: ' - (\d+) \('
              seasonRegex: 'S(\d+)'
              episodeMap: {13-24: 1-12, 0: 13}
              outputName: '${'$'}{showName} - ${'$'}{episodeName}'
            additionalSources:
              - files: 'Rus sound/*.mka'
                matcher: sequence
              - file: '${'$'}{fileName}.srt'
        """.trimIndent() + "\n"
        withData(
            nameFn = { it.label },
            ProblemCase("a valid config with every key", valid, emptyList()),
            ProblemCase(
                "an unknown pin",
                "general:\n  matcher: positional\n",
                listOf(
                    "config.yaml has an unknown matcher 'positional' at general.matcher; the only pin is 'sequence'",
                ),
            ),
            ProblemCase(
                "a pin other than sequence: a regex is the rule when set, names and SxxEyy the default",
                "general:\n  matcher: regex\n",
                listOf("config.yaml has an unknown matcher 'regex' at general.matcher; the only pin is 'sequence'"),
            ),
            ProblemCase(
                "a regex beside sequence, which binds by order",
                "general:\n  matcher: sequence\n  episodeRegex: '(\\d+)'\n  seasonRegex: 'S(\\d+)'\n",
                listOf(
                    "config.yaml sets general.episodeRegex beside the 'sequence' pin, which reads no regex",
                    "config.yaml sets general.seasonRegex beside the 'sequence' pin, which reads no regex",
                ),
            ),
            ProblemCase(
                "a season regex alone",
                "general:\n  seasonRegex: 'S(\\d+)'\n",
                listOf("config.yaml sets general.seasonRegex without an episodeRegex"),
            ),
            ProblemCase(
                "a regex that does not compile",
                "general:\n  episodeRegex: '(\\d+'\n",
                listOf("config.yaml has a regex at general.episodeRegex that does not compile: Unclosed group"),
            ),
            ProblemCase(
                "a regex that captures nothing",
                "general:\n  episodeRegex: '\\d+'\n",
                listOf(
                    "config.yaml has a regex at general.episodeRegex that captures no group; group 1 is the number",
                ),
            ),
            ProblemCase(
                "an episode map that is not a mapping",
                "general:\n  episodeMap: [1-2]\n",
                listOf("config.yaml sets general.episodeMap to something that is not a mapping"),
            ),
            ProblemCase(
                "an episode map entry with a bad side each",
                "general:\n  episodeMap: {13-: 1-12, 1-2: 0}\n",
                listOf(
                    "config.yaml has a release range at general.episodeMap that is not one: '13-'",
                    "config.yaml has an episode range at general.episodeMap that is not one: '0'",
                ),
            ),
            ProblemCase(
                "an episode map entry whose sides differ in length",
                "general:\n  episodeMap: {13-24: 1-10}\n",
                listOf("config.yaml maps 13-24 to 1-10 at general.episodeMap, which differ in length"),
            ),
            ProblemCase(
                "an episode map claiming a release number twice, and an episode twice",
                "general:\n  episodeMap: {1-3: 11-13, 3-4: 12-13}\n",
                listOf(
                    "config.yaml maps release episodes 3 twice at general.episodeMap",
                    "config.yaml maps two release numbers onto episodes 12-13 at general.episodeMap",
                ),
            ),
            ProblemCase(
                "file and files on one source",
                "additionalSources:\n  - file: a.mka\n    files: '*.mka'\n",
                listOf(
                    "config.yaml sets both file and files on additionalSources[0]; file names one file per " +
                        "episode, files binds what its masks match",
                ),
            ),
            ProblemCase(
                "files with only blank masks",
                "additionalSources:\n  - files: ['']\n",
                listOf("config.yaml has no usable mask in additionalSources[0].files"),
            ),
            ProblemCase(
                "matcher keys on a source with only file",
                "additionalSources:\n  - file: a.mka\n    matcher: sequence\n",
                listOf("config.yaml sets matcher on additionalSources[0], which has no files to bind"),
            ),
            ProblemCase(
                "a source problem in a group names the group",
                "groups:\n  - episodes: 1\n    additionalSources:\n      - files: '*.mka'\n        matcher: fuzzy\n",
                listOf(
                    "config.yaml has an unknown matcher 'fuzzy' at groups[0].additionalSources[0].matcher; " +
                        "the only pin is 'sequence'",
                ),
            ),
            ProblemCase(
                "an empty outputName",
                "general:\n  outputName:\n",
                listOf("config.yaml has an empty general.outputName"),
            ),
            ProblemCase(
                "a blank outputName, which sanitizes to nothing",
                "general:\n  outputName: ' '\n",
                listOf("config.yaml has an empty general.outputName"),
            ),
            ProblemCase(
                "a pin with no value",
                "general:\n  matcher:\n",
                listOf("config.yaml has an empty general.matcher"),
            ),
            ProblemCase("a pin name in any case, padded", "general:\n  matcher: 'Sequence '\n", emptyList()),
            ProblemCase(
                "a blank pin",
                "general:\n  matcher: ' '\n",
                listOf("config.yaml has an empty general.matcher"),
            ),
            ProblemCase(
                "a broken regex beside a pin that reads none, reported once",
                "general:\n  matcher: sequence\n  episodeRegex: '(\\d+'\n",
                listOf("config.yaml sets general.episodeRegex beside the 'sequence' pin, which reads no regex"),
            ),
            ProblemCase(
                "an empty regex, reported once",
                "general:\n  episodeRegex:\n",
                listOf("config.yaml has an empty general.episodeRegex"),
            ),
            ProblemCase(
                "an episode map with no value",
                "general:\n  episodeMap:\n",
                listOf("config.yaml has an empty general.episodeMap"),
            ),
            ProblemCase(
                "an episode map with no entries",
                "general:\n  episodeMap: {}\n",
                listOf("config.yaml has an empty general.episodeMap"),
            ),
            ProblemCase(
                "an unknown pin in any case, quoted as written",
                "general:\n  matcher: Suffix\n",
                listOf("config.yaml has an unknown matcher 'Suffix' at general.matcher; the only pin is 'sequence'"),
            ),
            ProblemCase(
                "a season regex beside a pin, reported once",
                "general:\n  matcher: sequence\n  seasonRegex: 'S(\\d+)'\n",
                listOf("config.yaml sets general.seasonRegex beside the 'sequence' pin, which reads no regex"),
            ),
            ProblemCase(
                "an episode map on a source, where the one in general already maps every number",
                "additionalSources:\n  - files: '*.mka'\n    episodeMap: {1: 2}\n",
                listOf(
                    "config.yaml sets additionalSources[0].episodeMap; the episode map belongs in general, which " +
                        "maps every number read",
                ),
            ),
            ProblemCase(
                "a sequence on a group's source, which cannot know which of the group's episodes comes first",
                "groups:\n  - episodes: 7-12\n    additionalSources:\n      - files: '*.mka'\n" +
                    "        matcher: Sequence\n",
                listOf(
                    "config.yaml pins Sequence on groups[0].additionalSources[0], which a group's source does not " +
                        "support; pair it by name or an episodeRegex",
                ),
            ),
        ) { (_, text, problems) ->
            bindingProblems(yaml(text), "config.yaml") shouldBe problems
        }
    }
})
