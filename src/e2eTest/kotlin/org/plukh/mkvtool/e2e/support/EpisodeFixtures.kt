package org.plukh.mkvtool.e2e.support

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Writing the two episode-metadata files `rename` and `mux` read.
 *
 * The two are written differently on purpose, and the difference is the point of this file.
 * `episodes.txt` is the format a human can produce - one title per line, no structure - so it is built as
 * text. `episodes.yaml` is machine-written, and the titles under test carry colons, question marks and
 * quotes *deliberately* (the tool's whole reason for sanitizing at rename time rather than at fetch time),
 * so it is **serialised** by snakeyaml rather than string-built. A builder hand-quoting those would be
 * writing the very bug the cases exist to catch.
 *
 * Both are written as explicit UTF-8, matching what `fetch-episodes` writes and what the readers expect.
 */

/**
 * Write `episodes.txt` into [workDir], one title per line with a trailing newline.
 *
 * There are no numbers in this format: a reader matches by line order, which is what `episodeOffset`
 * shifts for `rename` and what `mux` deliberately does not shift.
 */
fun writeEpisodes(workDir: File, titles: List<String>): File =
    File(workDir, "episodes.txt").also {
        it.writeText(titles.joinToString("\n") + "\n", StandardCharsets.UTF_8)
    }

/**
 * Write `episodes.yaml` into [workDir]. [episodes] maps a **real** episode number to its raw, unsanitized
 * name; everything else is show-level metadata.
 *
 * [show] and [season] have defaults rather than being nullable because an empty show name and season 0 are
 * both real inputs under test - the Groovy original drew that line with `containsKey`, and a Kotlin
 * default expresses it directly. [year], [seasonName] and [language] are genuinely optional and their keys
 * are omitted when null.
 *
 * `allowUnicode` is not decoration: without it snakeyaml escapes every Cyrillic character and produces
 * valid YAML that is unreadable for exactly the titles this feature exists to preserve.
 */
fun writeEpisodesYaml(
    workDir: File,
    show: String = "Stub Show",
    year: Int? = null,
    season: Int = 1,
    seasonName: String? = null,
    language: String? = null,
    episodes: Map<Int, String> = emptyMap(),
): File {
    // LinkedHashMap throughout: the key order below is the order the file is written in, and a case
    // reading the file by eye should find it in the shape fetch-episodes produces.
    val data = LinkedHashMap<String, Any?>()
    data["show"] = show
    year?.let { data["year"] = it }
    data["season"] = season
    seasonName?.let { data["seasonName"] = it }
    language?.let { data["language"] = it }
    data["episodes"] = episodes.map { (number, name) ->
        LinkedHashMap<String, Any?>().apply {
            put("episode", number)
            put("name", name)
        }
    }

    val dumper = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isAllowUnicode = true
    }
    return File(workDir, "episodes.yaml").also {
        it.writeText(Yaml(dumper).dump(data), StandardCharsets.UTF_8)
    }
}
