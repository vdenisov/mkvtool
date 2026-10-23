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
            trackSelectionOf(parseConfig(emptyMap<String, Any>())).videoIds shouldBe setOf(0)
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
                ),
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
                ),
            )

            selection.titleById shouldBe mapOf(1 to "Japanese", 4 to "Subs")
        }

        test("a valueless title names no title, which is how the report reads it") {
            trackSelectionOf(
                parseConfig(
                    mapOf("mainSource" to mapOf("audioTracks" to listOf(mapOf("id" to 1, "title" to null)))),
                ),
            ).titleById shouldBe emptyMap()
        }

        test("a track with no id selects nothing, rather than a null that matches nothing") {
            val selection = trackSelectionOf(
                parseConfig(mapOf("mainSource" to mapOf("audioTracks" to listOf(mapOf("language" to "en"))))),
            )

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
                "a file mask that is not a glob",
                "groups:\n  - files: [\"*OVA*\", \"*[OVA*\"]\n",
                listOf("config.yaml has a file mask in groups[0] that is not a valid pattern: '*[OVA*'"),
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
})
