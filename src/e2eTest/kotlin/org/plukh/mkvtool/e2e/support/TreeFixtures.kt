package org.plukh.mkvtool.e2e.support

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Directory-shaped fixtures: the trees discovery, the mask rules and the batch commands run against.
 *
 * Where [MediaFixtures] builds one file, these build a *situation*. Both trees below are modelled on real
 * releases rather than invented, which is why every element of them is load-bearing - a helper that stages
 * one file fewer stops testing something, silently, in whichever case relied on it.
 */

/** The default body for a staged subtitle file: one SRT cue. */
internal const val ONE_CUE_SRT = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"

/**
 * Write a text external file (subtitles) at [relPath] under [workDir], creating directories as needed.
 *
 * The content is deliberately unimportant and the default body is an SRT cue whatever extension the caller
 * asks for, `.ass` and `.vtt` included: **nothing probes these formats**, and that is half of what the
 * discovery cases assert - a subtitle file's language and grouping come from its name and its directory,
 * never from reading it.
 */
fun stageExternalText(workDir: File, relPath: String, text: String = ONE_CUE_SRT): File =
    File(workDir, relPath).also {
        it.parentFile?.mkdirs()
        it.writeText(text, StandardCharsets.UTF_8)
    }

/**
 * Extract one track of `test.mkv` to [relPath] under [workDir] - the external files that *do* get probed.
 *
 * An `.mka` or `.mks` carries a real language and track name, which is what makes probed-field-wins
 * testable against a folder-name guess. Pass [language] to override the extracted track's own; `und` is
 * the one worth reaching for, being Matroska's spelling of "untagged".
 */
fun stageExternalTrack(
    workDir: File,
    relPath: String,
    type: TrackType,
    trackId: Int,
    language: String? = null,
): File = extractTrack(testMkv, File(workDir, relPath), type, trackId, language)

/**
 * The shared multi-directory fixture, modelled on a real anime release.
 *
 * Nine files, and each answers a different question:
 *
 * - three episodes at the top level, the main files;
 * - `Rus sound/[GroupA]` audio for all three - extracted from source track 3, so it carries a real `rus`
 *   tag and the probe finds a language without guessing;
 * - `Rus subs/[GroupA]` subtitles for episodes 1 and 2 only - **deliberately incomplete coverage**, which
 *   is what a partially released dub looks like, and what the same-named-leaf merge has to handle;
 * - `Rus sound/[GroupB]` audio for episode 1 only, forced to `und` - the untagged case, where the
 *   language can only come from the folder name;
 * - `Show - S01E01 - Title.rus.srt` beside the media itself, a suffixed sibling in the media directory;
 * - `Bonus.ass`, which belongs to no episode at all;
 * - `extras/Sample.mkv`, a stray main-type file in a subdirectory - an extra, not an episode.
 *
 * The directory names carry meaning too: `Rus sound` and `Rus subs` are the category directories the
 * language guess reads, and `[GroupA]`/`[GroupB]` are the release-group leaves that give a variant its
 * identity.
 */
fun stageTree(workDir: File) {
    listOf("01", "02", "03").forEach { ep ->
        stageInput(workDir, "Show - S01E$ep - Title.mkv")
        stageExternalTrack(workDir, "Rus sound/[GroupA]/Show - S01E$ep - Title.mka", TrackType.AUDIO, 3)
    }
    listOf("01", "02").forEach { ep ->
        stageExternalText(workDir, "Rus subs/[GroupA]/Show - S01E$ep - Title.ass")
    }
    stageExternalTrack(workDir, "Rus sound/[GroupB]/Show - S01E01 - Title.mka", TrackType.AUDIO, 2, "und")
    stageExternalText(workDir, "Show - S01E01 - Title.rus.srt")
    stageExternalText(workDir, "Rus subs/[GroupA]/Bonus.ass")
    stageInput(workDir, "extras/Sample.mkv")
}

/**
 * Three episodes plus a sample and a non-media file, with a config - the fixture for the file-mask cases.
 *
 * `Show.S01E01.sample.mkv` is what a mask has to *not* match when it selects the episodes, and `notes.txt`
 * is what `mux` reports passing over: the two mistakes a mask can make are "nothing matched at all" and
 * "nothing that matched is media", and this stages both.
 *
 * The config declares one audio track and **no `trackOrder`**, deliberately, so derivation stays exercised
 * by every case that builds on this.
 */
fun stageBatch(workDir: File) {
    listOf(
        "Show.S01E01.mkv",
        "Show.S01E02.mkv",
        "Show.S01E03.mkv",
        "Show.S01E01.sample.mkv",
    ).forEach { stageInput(workDir, it) }

    File(workDir, "notes.txt").writeText("not a video", StandardCharsets.UTF_8)
    writeConfig(
        workDir,
        cfg(audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true))),
    )
}
