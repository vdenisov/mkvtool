package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        test("a track in an additional source is always track 0, whatever it says") {
            val source = parseConfig(
                mapOf(
                    "additionalSources" to listOf(
                        mapOf(
                            "file" to "\${fileName}[Grp].mka",
                            "tracks" to listOf(mapOf("language" to "ru")),
                            "additionalOptions" to listOf("--compression", "0:none"),
                        ),
                    ),
                ),
            ).additionalSources.single()

            source.file shouldBe "\${fileName}[Grp].mka"
            source.tracks.single().id shouldBe 0
            source.additionalOptions shouldBe listOf("--compression", "0:none")
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
})
