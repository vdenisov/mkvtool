package org.plukh.mkvtool.e2e.support

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Deriving MKV fixtures from the one input, `test.mkv`.
 *
 * Everything a media case runs against is built here by real mkvmerge invocations rather than committed
 * as binary fixtures: one input file in the repository, and every variant a case needs derived from it at
 * run time. `test.mkv` carries track 0 video (und), 1-3 audio (jpn/eng/rus) and 4-6 subtitles
 * (eng/rus-forced/jpn), and every track id in this file and in the cases indexes into that.
 *
 * **The trap that governs all of it: a variant has two id spaces, and they are not the same one.**
 * mkvmerge renumbers the surviving tracks from 0 in source order, so a variant keeping only audio 2 and 3
 * reports them at ids 1 and 2 - but `--track-name` and its siblings are *source-file* options, so an
 * override still names the id the **source** carries. A variant therefore has to be asserted in output ids
 * and configured in source ids, and getting it backwards names a track that was never muxed, which
 * mkvmerge accepts in silence: the fixture builds, the case runs, and it pins nothing.
 *
 * That is why [fullCopy] exists and why most check-report fixtures use it - keeping all seven tracks
 * collapses the two spaces into one, so an id means the same thing on both sides of the helper. (Worth
 * knowing: the Groovy suite's own comment on this helper claimed the overrides were keyed by the *output*
 * id. It was wrong, and nothing ever caught it, because every case that passes an override keeps all seven
 * tracks.)
 *
 * These helpers need mkvmerge but not the binary under test - they build what a case runs *against*. Gate
 * a spec that calls them on [needsMkvmerge].
 */

/**
 * Which of the two selectable track types a helper is extracting.
 *
 * An enum rather than the Groovy suite's `'audio'`/anything-else string switch, which silently read every
 * value that was not exactly `audio` as a subtitle request.
 */
enum class TrackType(internal val selectFlag: String, internal val dropOtherFlag: String) {
    AUDIO("--audio-tracks", "--no-subtitles"),
    SUBTITLES("--subtitle-tracks", "--no-audio"),
}

/**
 * Extract a single track of [src] into [dest] as a standalone file, creating parent directories.
 *
 * The result carries one track and no video, which is what an `.mka` or `.mks` companion is. Pass
 * [language] to override the tag the extracted track carries; `und` is the interesting value, since it is
 * how Matroska spells "untagged" and therefore what forces discovery to fall back on its folder-name
 * language guess.
 *
 * Returns [dest]. (The Groovy original returned nothing, its last statement being an assertion, while its
 * sibling returned the file - normalised here.)
 */
fun extractTrack(src: File, dest: File, type: TrackType, trackId: Int, language: String? = null): File {
    dest.parentFile?.mkdirs()

    val command = buildList {
        addAll(listOf("--output", dest.absolutePath, "--no-video", type.dropOtherFlag))
        addAll(listOf(type.selectFlag, trackId.toString()))
        // The selector addresses the *source* track, so the language override is keyed on the source id
        // too - this runs against the input file, before any renumbering has happened.
        language?.let { addAll(listOf("--language", "$trackId:$it")) }
        add(src.absolutePath)
    }
    runMkvmerge("extractTrack", command)
    return dest
}

/**
 * Build a derivative of `test.mkv` at [dest]: choose which tracks survive, and override what they carry.
 *
 * Every id here is a **source** id: [audio] and [subs] name the tracks to keep (null drops the whole
 * type), and [names], [langs] and [defaults] name the tracks to override. What the *result* reports is
 * something else - see the two-id-spaces note on this file, which is the one thing to get right here.
 *
 * Two knobs the Groovy original carried are deliberately not here. Its track lists had a **third state** -
 * a key present with a null value emitted no flag at all, keeping every track of that type - which no case
 * ever used; if one needs it, it wants its own spelling rather than an overloaded null. And `--no-chapters`
 * cannot be observed through these fixtures at all: `test.mkv` carries no chapters, so suppressing them is
 * a no-op, which is presumably why no case ever passed it either. An empty list is refused rather than
 * dropped, because the original emitted an empty `--audio-tracks` there and produced a file nobody meant.
 *
 * Returns [dest].
 */
fun buildVariant(
    dest: File,
    audio: List<Int>? = null,
    subs: List<Int>? = null,
    names: Map<Int, String> = emptyMap(),
    langs: Map<Int, String> = emptyMap(),
    defaults: Map<Int, Boolean> = emptyMap(),
    chaptersFile: File? = null,
): File {
    require(audio == null || audio.isNotEmpty()) { "audio = emptyList() selects nothing; pass null to drop audio" }
    require(subs == null || subs.isNotEmpty()) { "subs = emptyList() selects nothing; pass null to drop subtitles" }
    dest.parentFile?.mkdirs()

    val command = buildList {
        addAll(listOf("--output", dest.absolutePath))
        if (audio == null) add("--no-audio") else addAll(listOf("--audio-tracks", audio.joinToString(",")))
        if (subs == null) add("--no-subtitles") else addAll(listOf("--subtitle-tracks", subs.joinToString(",")))
        names.forEach { (id, name) -> addAll(listOf("--track-name", "$id:$name")) }
        langs.forEach { (id, language) -> addAll(listOf("--language", "$id:$language")) }
        defaults.forEach { (id, on) -> addAll(listOf("--default-track-flag", "$id:${if (on) "yes" else "no"}")) }
        chaptersFile?.let { addAll(listOf("--chapters", it.absolutePath)) }
        add(testMkv.absolutePath)
    }
    runMkvmerge("buildVariant", command)
    return dest
}

/**
 * A complete seven-track copy of `test.mkv` at [dest], with optional per-id overrides.
 *
 * The fixture most consistency-check cases are built from, and it exists for one reason: keeping every
 * track collapses the two id spaces into one, so `names = mapOf(2 to "Other Studio")` both addresses audio
 * track 2 and is *observable* at id 2 in the result. Dropping a track instead produces a genuine absence
 * at that id, which is the other half of what those cases need.
 */
fun fullCopy(
    dest: File,
    names: Map<Int, String> = emptyMap(),
    langs: Map<Int, String> = emptyMap(),
    defaults: Map<Int, Boolean> = emptyMap(),
    chaptersFile: File? = null,
): File = buildVariant(
    dest,
    audio = listOf(1, 2, 3),
    subs = listOf(4, 5, 6),
    names = names,
    langs = langs,
    defaults = defaults,
    chaptersFile = chaptersFile,
)

/**
 * Write a minimal OGM-simple chapter file into [workDir], for [buildVariant]'s `chaptersFile`.
 *
 * mkvmerge reads chapters as text, so the chapter cases need no binary fixture of their own.
 */
fun writeChapters(workDir: File): File =
    File(workDir, "chapters.txt").also {
        it.writeText(
            "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\n" +
                "CHAPTER02=00:00:02.000\nCHAPTER02NAME=Main\n",
            StandardCharsets.UTF_8,
        )
    }

/**
 * Run mkvmerge for a fixture, failing with its own output when it could not build one.
 *
 * **Exit 1 is success here.** mkvmerge reports warnings that way - a source track it had to adjust, a
 * timestamp it did not like - and none of that stops the file being the fixture a case asked for. Only 2
 * and above are failures. Note this is the opposite of [probe], which requires 0: a warning while reading
 * a file is a different thing from a warning while writing one.
 */
private fun runMkvmerge(what: String, command: List<String>) {
    val exe = mkvmergeExe
        ?: error("mkvmerge is required to build fixtures - this spec should have been gated on needsMkvmerge")

    val run = exec(listOf(exe) + command)
    check(run.exitCode == 0 || run.exitCode == 1) {
        "$what failed (exit ${run.exitCode}):\n${run.output}"
    }
}
