package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.Warning
import org.plukh.mkvtool.out.plural
import java.io.File
import java.util.Locale
import java.util.MissingResourceException

/**
 * The substitution-variable engine: the file- and track-scope variables a templated config value
 * expands, and the two-stage validation around them.
 *
 * Config values are templates — `"${'$'}{languageName} ${'$'}{codec}"` rather than a literal
 * `"Russian SRT"`. Variables come in two scopes: file-scope ones describe the episode being muxed and
 * are valid everywhere, track-scope ones describe the track a title belongs to and are valid only in a
 * title. An unknown name is a config error caught up front, never a literal passed through to mkvmerge.
 *
 * The split that matters is between the two validation stages. **Stage one is config-static and fatal**:
 * an unknown variable, one out of scope, a malformed body, or a language with no display name — all
 * knowable before a single file is probed, and a typo'd `${'$'}{epsiodeName}` would otherwise be stamped
 * verbatim into the track names of an entire season. **Stage two is per-file and drops**: a valid
 * variable with no data for one episode is data-shaped, so that episode is dropped and the rest still
 * mux. Stage two lives with the caller, which owns the batch; this file supplies [FileVars.missing].
 */

/** Variables describing the episode being muxed. Valid in every templated field. */
val FILE_VARS = setOf(
    "fileName", "extension", "showName", "seasonNum",
    "episodeNum", "episodeName", "seasonName", "showYear",
)

/** Variables describing the track a title belongs to. Valid only in a title. */
val TRACK_VARS = setOf("language", "languageName", "languageNative", "codec")

/**
 * Every language the JDK knows, by both its two- and three-letter codes, plus the ISO 639-2/B
 * ("bibliographic") codes that differ from the /T codes the JDK returns — Matroska files in the wild
 * carry those, so they have to resolve too.
 *
 * Deliberately exhaustive, unlike the curated list behind [guessLanguage]: this answers "what does this
 * language code the config *wrote down* mean", where a wrong answer is impossible and an obscure code is
 * simply someone's actual language. Guessing from a folder name is the opposite problem.
 */
private val LOCALE_BY_CODE: Map<String, Locale> = buildLocaleIndex()

private fun buildLocaleIndex(): Map<String, Locale> {
    val index = HashMap<String, Locale>()
    for (code in Locale.getISOLanguages()) {
        val locale = Locale.of(code)
        index[code] = locale
        try {
            index[locale.isO3Language] = locale
        } catch (_: MissingResourceException) {
            // A handful of codes have no three-letter form; the two-letter one stands.
        }
    }
    val bibliographic = mapOf(
        "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs",
        "gre" to "el", "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq",
        "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
    )
    for ((bib, code) in bibliographic) index[bib] = Locale.of(code)
    return index
}

private fun localeFor(code: String?): Locale? =
    if (code.isNullOrEmpty()) null else LOCALE_BY_CODE[code.lowercase(Locale.ROOT)]

/** The English display name for a language [code], or null when the JDK has none. */
fun languageNameOf(code: String?): String? {
    val locale = localeFor(code) ?: return null
    val name = locale.getDisplayLanguage(Locale.ENGLISH)
    // The JDK echoes the code back when it has no display name for it.
    return if (name.isEmpty() || name.equals(code, ignoreCase = true)) null else name
}

/**
 * The language's own name for itself, first letter upper-cased **in that language's own rules**. Many
 * languages spell their own name in lower case (`русский`), which reads wrong in a track title.
 */
fun languageNativeOf(code: String?): String? {
    val locale = localeFor(code) ?: return null
    val name = locale.getDisplayLanguage(locale)
    if (name.isEmpty() || name.equals(code, ignoreCase = true)) return null
    return name.substring(0, 1).uppercase(locale) + name.substring(1)
}

/**
 * Keyed on `codec_id`, a Matroska specification identifier, rather than on mkvmerge's `codec` display
 * string: the display string is not stable across mkvmerge versions (v99 reports
 * `AVC/H.264/MPEG-4p10` where older releases report the components in the opposite order), and CI runs
 * both a distro mkvtoolnix and the newest release.
 */
private val CODEC_BY_ID = mapOf(
    "V_MPEG4/ISO/AVC" to "H.264", "V_MPEGH/ISO/HEVC" to "H.265", "V_AV1" to "AV1",
    "V_MPEG2" to "MPEG-2", "V_VP9" to "VP9",
    "A_AAC" to "AAC", "A_AC3" to "AC-3", "A_EAC3" to "E-AC-3",
    "A_DTS" to "DTS", "A_TRUEHD" to "TrueHD", "A_FLAC" to "FLAC",
    "A_OPUS" to "Opus", "A_MPEG/L3" to "MP3", "A_VORBIS" to "Vorbis",
    "A_PCM/INT/LIT" to "PCM",
    "S_TEXT/UTF8" to "SRT", "S_TEXT/ASS" to "ASS", "S_TEXT/SSA" to "SSA",
    "S_TEXT/WEBVTT" to "WebVTT", "S_HDMV/PGS" to "PGS", "S_VOBSUB" to "VobSub",
)

/**
 * The second tier: a raw (non-Matroska) companion carries **no `codec_id` at all** — a bare `.ass`
 * probes with an empty one and only a display string — so the display name has to be mapped too.
 */
private val CODEC_BY_DISPLAY = mapOf(
    "SubStationAlpha" to "ASS", "SubRip/SRT" to "SRT", "HDMV PGS" to "PGS", "VobSub" to "VobSub",
)

/** A readable codec name for [track], in three tiers: the codec id, then the display string, then the
 *  display string unchanged — so an unmapped codec degrades rather than breaks. */
fun friendlyCodec(track: ProbedTrack?): String? {
    if (track == null) return null
    val mapped = track.codecId?.takeIf { it.isNotEmpty() }?.let { CODEC_BY_ID[it] }
    if (mapped != null) return mapped
    val display = track.codec
    return if (display.isNullOrEmpty()) null else (CODEC_BY_DISPLAY[display] ?: display)
}

/** A variable name in a template. */
private val VAR_PATTERN = Regex("""\$\{([A-Za-z][A-Za-z0-9]*)\}""")

/**
 * Deliberately looser than [VAR_PATTERN], so a malformed body — `${'$'}{file name}`,
 * `${'$'}{var:modifier}` — is caught by validation instead of silently surviving as a literal because it
 * failed to look like a variable at all.
 */
private val LOOSE_PATTERN = Regex("""\$\{[^}]*\}""")

/** Expand every `${'$'}{name}` in [template] from [vars]. An unresolved name expands to nothing, which
 *  is only reachable after stage one has passed the name as valid. */
fun substitute(template: String, vars: Map<String, String?>): String =
    template.replace(VAR_PATTERN) { match -> vars[match.groupValues[1]] ?: "" }

/** One episode's file-scope variables, and which of them no source could supply. */
data class FileVars(val vars: Map<String, String?>, val missing: Set<String>)

/**
 * Groovy's elvis for strings: fall through on an *empty* value as well as a null one. v1 chained its
 * variable sources with `?:`, where an empty show name defers to the next source; Kotlin's `?:` would
 * keep the empty string and report the variable as present.
 */
private infix fun String?.orIfEmpty(fallback: String?): String? =
    if (this.isNullOrEmpty()) fallback else this

/**
 * The file-scope variable resolver for one batch, holding the per-file memo.
 *
 * [episodeData] is supplied already parsed — the same seam as [normalizeYaml]: the caller owns reading
 * and parsing, this owns the semantics.
 */
class SubstitutionEngine(private val episodeData: EpisodeData? = null) {

    private val cache = HashMap<File, FileVars>()

    /**
     * File-scope variables for one episode, memoized. Sources are tried in order: `episodes.yaml` (or
     * `episodes.txt`), then the canonical `Show - SxxEyy - Title` file name. Anything still unresolved is
     * reported per file by the caller's stage-two pre-flight, so a season with one episode missing from
     * the metadata loses that episode rather than the whole batch.
     */
    fun fileVarsFor(file: File): FileVars = cache.getOrPut(file) {
        val base = file.name.substringBeforeLast('.')
        val parsed = parseSeasonEpisode(base)
        val canonical = parseCanonicalName(base)

        // Episode numbers are only meaningful within one season, so metadata for a different season must
        // not be joined against this file — it is then simply absent rather than wrong.
        val metadata = episodeData?.takeIf { meta ->
            meta.season == null || parsed?.season == null || meta.season == parsed.season
        }
        val fromData = parsed?.episode?.let { metadata?.byEpisode?.get(it) }

        // `orIfEmpty` throughout, not `?:`: v1's Groovy elvis fell through on an *empty* value too, so an
        // empty show name in the metadata still defers to the one parsed out of the file name.
        val vars = linkedMapOf<String, String?>(
            "fileName" to base,
            "extension" to file.name.substringAfterLast('.', ""),
            "seasonNum" to (parsed?.season orIfEmpty metadata?.season),
            "episodeNum" to parsed?.episode,
            "episodeName" to (fromData orIfEmpty canonical?.title),
            "showName" to (metadata?.show orIfEmpty canonical?.showName),
            "seasonName" to metadata?.seasonName,
            "showYear" to metadata?.year,
        )
        // Empty counts as missing, not just null: v1's Groovy truthiness treated them alike, and an
        // empty show name is as unusable as an absent one.
        FileVars(vars, vars.filterValues { it.isNullOrEmpty() }.keys)
    }
}

/**
 * Track-scope variables for a title. [languageCode] is the config's own spelling for the track;
 * [probed] is supplied by the caller, which owns the probe caches, and is null whenever `${'$'}{codec}`
 * is not in use — so a run without it costs no extra subprocesses.
 */
fun trackVarsFor(languageCode: String?, probed: ProbedTrack?): Map<String, String?> = mapOf(
    "language" to languageCode,
    "languageName" to languageNameOf(languageCode),
    "languageNative" to languageNativeOf(languageCode),
    "codec" to friendlyCodec(probed),
)

/** One templated config value, with the variable scope legal in it. [languageCode] is the track's own
 *  language where the field belongs to a track, for the language-name check in stage one. */
data class TemplateField(
    val path: String,
    val value: String?,
    val allowed: Set<String>,
    val languageCode: String? = null,
)

/**
 * Every templated value in [config], with its legal scope. Built once so validation, the pre-flight and
 * the command line all agree on exactly which fields are templates.
 *
 * The paths it reports are the config file's own — `mainSource.audioTracks[1].title` — because that is
 * what a diagnostic has to name if the reader is to find the offending line.
 */
fun collectTemplateFields(config: Config?): List<TemplateField> {
    if (config == null) return emptyList()
    val fields = ArrayList<TemplateField>()
    val all = FILE_VARS + TRACK_VARS

    // A declared template with no value is still a template, and validating it as an empty one is what
    // keeps the field list honest; an absent key is no field at all.
    config.general.title?.let { fields += TemplateField("general.title", it.text, FILE_VARS) }

    config.mainSource.videoTrack?.let { video ->
        video.title?.let {
            fields += TemplateField("mainSource.videoTrack.title", it.text, all, video.language)
        }
    }
    for ((key, tracks) in listOf(
        "audioTracks" to config.mainSource.audioTracks,
        "subtitleTracks" to config.mainSource.subtitleTracks,
    )) {
        tracks.forEachIndexed { i, track ->
            track.title?.let {
                fields += TemplateField("mainSource.$key[$i].title", it.text, all, track.language)
            }
        }
    }

    config.additionalSources.forEachIndexed { i, source ->
        // Truthiness here, unlike the titles above: a source with no file is nothing to resolve.
        if (!source.file.isNullOrEmpty()) {
            fields += TemplateField("additionalSources[$i].file", source.file, FILE_VARS)
        }
        source.tracks.forEachIndexed { j, track ->
            track.title?.let {
                fields += TemplateField("additionalSources[$i].tracks[$j].title", it.text, all, track.language)
            }
        }
    }

    return fields
}

/** A token in a template that is not a variable, or not one legal in that field. */
data class TemplateOffense(val path: String, val token: String, val allowed: Set<String>)

/** A field asking for a language's display name where the track's code has none. */
data class BadLanguage(val path: String, val code: String?)

/**
 * What stage one found, plus which variables the config actually uses — the latter is what lets
 * everything derived from them be gated, so a config with no templates costs nothing at all.
 */
data class TemplateValidation(
    val usedFileVars: Set<String>,
    val usesCodec: Boolean,
    val offenses: List<TemplateOffense>,
    val badLanguages: List<BadLanguage>,
) {
    val problems: Int get() = offenses.size + badLanguages.size
}

/**
 * Stage one: check every template against the scope legal in its field. Pure — it decides nothing about
 * what a problem *means*, which is the caller's question ([reportTemplateProblems]).
 */
fun validateTemplates(fields: List<TemplateField>): TemplateValidation {
    val usedFileVars = LinkedHashSet<String>()
    var usesCodec = false
    val offenses = ArrayList<TemplateOffense>()
    val badLanguages = ArrayList<BadLanguage>()

    for (field in fields) {
        val text = field.value.orEmpty()
        var usesLanguageName = false

        for (occurrence in LOOSE_PATTERN.findAll(text).map { it.value }) {
            val name = VAR_PATTERN.matchEntire(occurrence)?.groupValues?.get(1)
            if (name == null || name !in field.allowed) {
                offenses += TemplateOffense(field.path, occurrence, field.allowed)
                continue
            }
            if (name in FILE_VARS) usedFileVars += name
            if (name == "codec") usesCodec = true
            if (name == "languageName" || name == "languageNative") usesLanguageName = true
        }

        // A language code with no display name is equally config-static, so it belongs in the same
        // fail-fast pass rather than surfacing per file.
        if (usesLanguageName) {
            val code = field.languageCode
            if (languageNameOf(code) == null || languageNativeOf(code) == null) {
                badLanguages += BadLanguage(field.path, code)
            }
        }
    }

    return TemplateValidation(usedFileVars, usesCodec, offenses, badLanguages)
}

/**
 * Say what stage one found, in wording identical everywhere — but leave the verdict to the caller.
 * `mux` cannot mux against a config it did not understand and passes [fatal]; `inspect` reports on files
 * and can report on them just as well without one, so it does not.
 */
fun reportTemplateProblems(validation: TemplateValidation, renderer: Renderer, fatal: Boolean) {
    if (validation.problems == 0) return

    val details = buildString {
        for (offense in validation.offenses) {
            appendLine("  ${offense.path}: ${offense.token}")
            appendLine("      valid here: ${offense.allowed.sorted().joinToString(", ")}")
        }
        for (bad in validation.badLanguages) {
            val code = bad.code?.takeIf { it.isNotEmpty() } ?: "(none)"
            appendLine("  ${bad.path}: no language name for '$code'")
        }
    }.trimEnd('\n')

    val message = "config.yaml has ${plural(validation.problems, "substitution problem")}:"
    renderer.render(if (fatal) Error(message, details) else Warning(message, details))
}
