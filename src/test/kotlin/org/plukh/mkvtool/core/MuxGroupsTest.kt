package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.Header
import java.io.File

/**
 * A config declaring groups: one muxing pass per group, each over the files it claims and with its own
 * selection, and what happens to a file no group claims or two groups do.
 *
 * mkvmerge is injected and the check is off unless a case is about it, so what is pinned is the orchestration.
 */
class MuxGroupsTest : FunSpec({

    test("each group is a pass over its own files, in config order, with its own selection and track order") {
        val dir = season(tempdir(), 4)

        val run = mux(dir, twoGroups())

        run.passes.map { it.group } shouldContainExactly listOf(GroupRef(0, "bd"), GroupRef(1, "tv"))
        run.passes.map { it.trackOrder } shouldContainExactly listOf("0:0,0:1", "0:0,0:2")
        run.passes.map { pass -> pass.files.map { it.fileName } } shouldContainExactly listOf(
            listOf("Show - S01E01.mkv", "Show - S01E02.mkv"),
            listOf("Show - S01E03.mkv", "Show - S01E04.mkv"),
        )
        run.passes[0].files.first().command!! shouldContainInOrder listOf("--audio-tracks", "1")
        run.passes[1].files.first().command!! shouldContainInOrder listOf("--audio-tracks", "2")
        run.muxed shouldBe 4
    }

    test("each pass is labelled before its gates and again before its run") {
        val renderer = RecordingRenderer()

        mux(season(tempdir(), 4), twoGroups(), renderer = renderer)

        renderer.headers() shouldContainExactly listOf(
            "*** Pass 1 of 2: group 1 (bd), 2 files",
            "*** Pass 2 of 2: group 2 (tv), 2 files",
            "*** Pass 1 of 2: group 1 (bd)",
            "*** Processing Show - S01E01.mkv",
            "*** Processing Show - S01E02.mkv",
            "*** Pass 2 of 2: group 2 (tv)",
            "*** Processing Show - S01E03.mkv",
            "*** Processing Show - S01E04.mkv",
        )
    }

    test("the implicit group is one unlabelled pass") {
        val renderer = RecordingRenderer()
        val config = twoGroups().let { it.copy(declaredGroups = null, mainSource = it.groups[0].mainSource) }

        val run = mux(season(tempdir(), 2), config, renderer = renderer)

        run.passes.single().group.shouldBeNull()
        renderer.headers().none { it.startsWith("*** Pass") } shouldBe true
    }

    test("a file no group claims is in no pass, and the rest of the batch muxes") {
        val run = mux(season(tempdir(), 5), twoGroups())

        run.binding!!.partition.unclaimed.map { it.name } shouldContainExactly listOf("Show - S01E05.mkv")
        run.files.map { it.fileName } shouldContainExactly (1..4).map { "Show - S01E0$it.mkv" }
        run.aborted.shouldBeNull()
    }

    test("a file two groups claim stops the batch before anything is written; masked out, it does not") {
        val dir = season(tempdir(), 4)
        val config = twoGroups(tvMasks = listOf("*E01*"))
        var ran = false

        val run = mux(dir, config, record = { ran = true })

        run.aborted shouldBe MuxAbort.Unbound(1)
        run.binding!!.problems.single().shouldBeInstanceOf<DoubleClaim>()
        ran shouldBe false
        File(dir, "mkv").exists() shouldBe false

        mux(dir, config, fileMasks = listOf("*E02*")).aborted.shouldBeNull()
    }

    test("a group with no file in the run gets no pass") {
        val renderer = RecordingRenderer()

        val run = mux(season(tempdir(), 4), twoGroups(), renderer = renderer, fileMasks = listOf("*E03*"))

        run.passes.map { it.group } shouldContainExactly listOf(GroupRef(1, "tv"))
        renderer.headers().first() shouldBe "*** Pass 1 of 1: group 2 (tv), 1 file"
    }

    test("two groups writing one output stop the batch before the later pass probes anything") {
        val dir = tempdir()
        listOf("a.mkv", "a.mp4").forEach { File(dir, it).writeText("x") }
        val config = Config(
            general = GENERAL,
            declaredGroups = listOf(
                GroupConfig(name = "mkv", membership = Membership(files = listOf("a.mkv"))),
                GroupConfig(name = "mp4", membership = Membership(files = listOf("a.mp4"))),
            ),
        )
        val probed = mutableListOf<String>()

        val run = mux(dir, config, check = true, probe = { probed += it.name; ProbeResult.Failed(it, "fake") })

        run.aborted shouldBe MuxAbort.OutputCollisions(mapOf("mkv/a.mkv" to listOf("a.mkv", "a.mp4")))
        probed shouldContainExactly listOf("a.mkv")
        File(dir, "mkv").exists() shouldBe false
    }

    test("stage two checks the variables a pass's own templates use, not another group's") {
        val dir = season(tempdir(), 4)
        val bd = twoGroups().groups[0].let { group ->
            group.copy(
                mainSource = group.mainSource.copy(
                    audioTracks = listOf(TrackConfig(id = 1, language = "ja", title = Template("\${episodeName}"))),
                ),
            )
        }
        val config = twoGroups().let { it.copy(declaredGroups = listOf(bd, it.groups[1])) }
        val episodes = EpisodeData(season = "01", byEpisode = mapOf("01" to "One"))

        val run = mux(dir, config, episodes = episodes)

        run.passes[0].substitutionDrops!!.fileNames shouldContainExactly listOf("Show - S01E02.mkv")
        run.passes[1].substitutionDrops.shouldBeNull()
        run.files.map { it.fileName } shouldContainExactly
            listOf("Show - S01E01.mkv", "Show - S01E03.mkv", "Show - S01E04.mkv")
    }

    test("only a group whose own templates ask for a codec probes its files for one") {
        val dir = season(tempdir(), 4)
        val bd = twoGroups().groups[0].let { group ->
            group.copy(
                mainSource = group.mainSource.copy(
                    audioTracks = listOf(TrackConfig(id = 1, language = "ja", title = Template("\${codec}"))),
                ),
            )
        }
        val config = twoGroups().let { it.copy(declaredGroups = listOf(bd, it.groups[1])) }
        val probed = mutableListOf<String>()

        mux(dir, config, probe = { probed += it.name; ProbeResult.Failed(it, "fake") })

        probed shouldContainExactly listOf("Show - S01E01.mkv", "Show - S01E02.mkv")
    }

    test("a non-media file belongs to no pass and is named once, before the passes run") {
        val dir = season(tempdir(), 2)
        File(dir, "notes.txt").writeText("x")

        val run = mux(dir, twoGroups())

        run.nonMedia.map { it.fileName to it.outcome } shouldContainExactly listOf("notes.txt" to MuxOutcome.Skipped)
        run.passes.flatMap { it.files }.none { it.fileName == "notes.txt" } shouldBe true
        run.files.first().fileName shouldBe "notes.txt"
    }

    test("no group claiming a file in the run says so, and leaves nothing behind") {
        val dir = season(tempdir(), 2)
        val renderer = RecordingRenderer()

        val run = mux(dir, twoGroups(bdEpisodes = 5..6), renderer = renderer)

        run.passes.shouldBeEmpty()
        run.files.shouldBeEmpty()
        renderer.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
            listOf("*** Nothing left to mux")
        File(dir, "mkv").exists() shouldBe false
    }
})

private val GENERAL = GeneralConfig(destinationDir = "mkv", allowedExtensions = setOf("mkv", "mp4"))

/** `bd` claims [bdEpisodes] and copies audio 1; `tv` claims 3-4 plus [tvMasks] and copies audio 2. */
private fun twoGroups(bdEpisodes: IntRange = 1..2, tvMasks: List<String> = emptyList()) = Config(
    general = GENERAL,
    declaredGroups = listOf(
        GroupConfig(
            name = "bd",
            membership = Membership(episodes = listOf(bdEpisodes)),
            mainSource = MainSourceConfig(audioTracks = listOf(TrackConfig(id = 1, language = "ja"))),
        ),
        GroupConfig(
            name = "tv",
            membership = Membership(episodes = listOf(3..4), files = tvMasks),
            mainSource = MainSourceConfig(audioTracks = listOf(TrackConfig(id = 2, language = "en"))),
        ),
    ),
)

/** [dir] holding [count] episodes named by `SxxEyy`. */
private fun season(dir: File, count: Int): File {
    (1..count).forEach { File(dir, "Show - S01E0$it.mkv").writeText("x") }
    return dir
}

private fun RecordingRenderer.headers(): List<String> = events.filterIsInstance<Header>().map { it.text }

private fun mux(
    dir: File,
    config: Config,
    renderer: RecordingRenderer = RecordingRenderer(),
    episodes: EpisodeData? = null,
    check: Boolean = false,
    fileMasks: List<String> = emptyList(),
    probe: (File) -> ProbeResult = { error("no probe expected: the check is off") },
    record: (List<String>) -> Unit = {},
): MuxRun = muxDirectory(
    dir = dir,
    options = MuxOptions(
        config = config,
        mkvmergeExe = "mkvmerge",
        uiLanguage = null,
        substitution = SubstitutionEngine(episodes),
        check = check,
        fileMasks = fileMasks,
    ),
    renderer = renderer,
    probe = probe,
    runCommand = { command, _ -> record(command); 0 },
)
