package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.findOutput
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeConfig

/**
 * `mux` end to end: a real config, a real mkvmerge invocation, and the output read back through the
 * production probe parser.
 *
 * The input fixture `test.mkv` carries track 0 video (und), 1-3 audio (jpn/eng/rus) and 4-6 subtitles
 * (eng/rus-forced/jpn); every id in these configs indexes into that.
 */
class MuxTest : FunSpec({

    test("one audio and one subtitle are selected out of the source").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 4, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,0:4",
            ),
        )

        mkvtool("mux", workDir = workDir).exitCode shouldBe 0

        val output = findOutput(workDir).shouldNotBeNull()
        val tracks = probe(output).allTracks

        tracks.count { it.type == "video" } shouldBe 1
        tracks.count { it.type == "audio" } shouldBe 1
        tracks.count { it.type == "subtitles" } shouldBe 1
        tracks.single { it.type == "audio" }.language shouldBe "eng"
        tracks.single { it.type == "subtitles" }.language shouldBe "eng"
    }
})
