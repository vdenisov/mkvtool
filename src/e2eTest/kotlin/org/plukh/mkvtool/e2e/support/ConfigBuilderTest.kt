package org.plukh.mkvtool.e2e.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.Config
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.loadConfig
import java.io.File

/**
 * The config builder every `mux` case rests on.
 *
 * These assert through the **production loader** rather than on the emitted string wherever they can: a
 * builder that renders plausible YAML the parser reads differently would pass a text comparison and fail
 * every case built on it, which is the failure mode worth spending a spec on. String assertions are kept
 * only for the two things the model cannot show — whether a key was written at all, and how a Windows
 * path was escaped.
 *
 * Needs no mkvmerge and no binary: it is pure text in, model out. It lives in this tier rather than the
 * unit one because what it tests is this tier's own machinery.
 */
class ConfigBuilderTest : FunSpec({

    val fakeExe = "/usr/bin/mkvmerge"

    fun load(yaml: String): Config {
        val file = File(tempdir(), "config.yaml").also { it.writeText(yaml) }
        val result = loadConfig(file)
        result.shouldBeLoaded()
        return (result as MappingLoad.Loaded).value
    }

    test("the defaults are a config mux can run") {
        val config = load(cfg(mkvmergeExe = fakeExe))

        config.general.destinationDir shouldBe "mkv"
        config.general.allowedExtensions shouldBe listOf("mkv", "avi", "mp4")
        config.general.mkvmergeExe shouldBe fakeExe
        config.mainSource.videoTrack.shouldNotBeNull().language shouldBe "en"
    }

    // The distinction the whole builder is shaped around. Absent and empty are different instructions,
    // and four acceptance cases exist to pin exactly this.
    context("an absent track list is not an empty one") {
        test("absent omits the key entirely") {
            val yaml = cfg(mkvmergeExe = fakeExe)

            yaml shouldNotContain "audioTracks"
            yaml shouldNotContain "subtitleTracks"
            load(yaml).mainSource.audioTracks shouldBe emptyList()
        }

        test("empty writes the key as an empty list") {
            val yaml = cfg(mkvmergeExe = fakeExe, audioTracks = emptyList(), subtitleTracks = emptyList())

            yaml shouldContain "audioTracks: []"
            yaml shouldContain "subtitleTracks: []"
            load(yaml).mainSource.audioTracks shouldBe emptyList()
        }
    }

    test("tracks keep the order they were given, with their ids and flags") {
        val config = load(
            cfg(
                mkvmergeExe = fakeExe,
                audioTracks = listOf(
                    TrackSpec(id = 2, language = "en", title = "English", default = true),
                    TrackSpec(id = 1, language = "ru", title = "Russian"),
                ),
                subtitleTracks = listOf(TrackSpec(id = 4, language = "en", title = "Subs", charset = "UTF-8")),
            ),
        )

        config.mainSource.audioTracks.map { it.id } shouldBe listOf(2, 1)
        config.mainSource.audioTracks.map { it.default } shouldBe listOf(true, false)
        config.mainSource.subtitleTracks.single().charset shouldBe "UTF-8"
    }

    test("charset is omitted rather than written empty when a track does not set one") {
        cfg(mkvmergeExe = fakeExe, subtitleTracks = listOf(TrackSpec(4, "en", "Subs")))
            .shouldNotContain("charset")
    }

    // Omission is what lets a case exercise mux's derivation, so it has to be genuinely absent.
    context("trackOrder") {
        test("is omitted when not asked for") {
            val yaml = cfg(mkvmergeExe = fakeExe)

            yaml shouldNotContain "trackOrder"
            load(yaml).trackOrder shouldBe null
        }

        test("is written when set") {
            load(cfg(mkvmergeExe = fakeExe, trackOrder = "0:0,0:2")).trackOrder shouldBe "0:0,0:2"
        }
    }

    test("additional sources carry their file pattern and tracks") {
        val config = load(
            cfg(
                mkvmergeExe = fakeExe,
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}[Studio].mka",
                        tracks = listOf(SourceTrackSpec(language = "ru", title = "Dub", default = true)),
                        additionalOptions = listOf("--compression", "0:none"),
                    ),
                ),
            ),
        )

        val source = config.additionalSources.single()
        source.file shouldBe "\${fileName}[Studio].mka"
        source.tracks.single().language shouldBe "ru"
        source.additionalOptions shouldBe listOf("--compression", "0:none")
    }

    // A backslash is an escape inside a YAML double-quoted scalar, so a Windows path that is not doubled
    // comes back as something else entirely — and every generated config carries one.
    test("a Windows path survives the round trip through the parser") {
        val windowsExe = """C:\Program Files\MKVToolNix\mkvmerge.exe"""
        val yaml = cfg(mkvmergeExe = windowsExe)

        yaml shouldContain """C:\\Program Files\\MKVToolNix\\mkvmerge.exe"""
        load(yaml).general.mkvmergeExe shouldBe windowsExe
    }

    test("the general title is omitted unless asked for, so the file-name default stays under test") {
        cfg(mkvmergeExe = fakeExe) shouldNotContain "title:"
        load(cfg(mkvmergeExe = fakeExe, generalTitle = "\${showName}")).general.title?.text shouldBe "\${showName}"
    }
})

private fun MappingLoad<Config>.shouldBeLoaded() {
    check(this is MappingLoad.Loaded) { "the builder emitted YAML the production loader rejected: $this" }
}
