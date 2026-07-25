package org.plukh.mkvtool.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Probe logic for the native-image failure modes that fail *silently*: a native binary
 * built without `-H:+AddAllCharsets` cannot decode single-byte charsets like
 * Windows-1251, one built without locale-data inclusion returns empty CLDR display
 * names, and a dependency that needs reachability config it did not get fails only when
 * its code is first reached. All work on any ordinary JVM, so only an end-to-end run of
 * the actual native binary catches a broken build - which is what the hidden
 * `native-smoke` command does.
 *
 * The first two functions are deliberately minimal and self-contained. They mirror the v1
 * idioms (`to_utf8.groovy`'s strict decoder and `subst.groovy`'s native-name lookup) and
 * exist only until the real utilities land: the `to-utf8` command will own strict
 * decoding and the `${'$'}{languageNative}` substitution will own in-locale upper-casing of a
 * language's own name. Once those exist the probe can point at them, or retire. Do not
 * grow shared abstractions here in the meantime. The last two already point at real
 * utilities, which is the shape the others should reach.
 */

/**
 * Decodes [bytes] as Windows-1251, rejecting any byte that is not valid in that
 * charset rather than substituting U+FFFD. Strictness (REPORT, not the default
 * REPLACE) is what makes this a real charset probe: on a native binary missing the
 * charset, `Charset.forName` throws outright.
 */
fun decodeWindows1251Strict(bytes: ByteArray): String =
    Charset.forName("windows-1251")
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

/**
 * The native (self-referential) CLDR display name for a language [code], e.g. "ru"
 * -> "Русский". First letter upper-cased with the language's own rules, since many
 * languages spell their own name in lower case. Returns null when the JDK has no
 * display name and just echoes the code back - which is exactly the empty-locale-data
 * symptom on a misconfigured native binary.
 */
fun nativeLanguageName(code: String): String? {
    val locale = Locale.of(code)
    val name = locale.getDisplayLanguage(locale)
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
    return name.substring(0, 1).uppercase(locale) + name.substring(1)
}

/**
 * Runs a non-ASCII language guess through the real [guessLanguage], which answers "rus" for "Русский"
 * only if the CLDR display names it builds its token table from survived the native build.
 *
 * This is the locale probe with teeth: [nativeLanguageName] asks whether one display name comes back
 * non-empty, while this exercises the production path — a table built at load time from every curated
 * locale, then matched against a Cyrillic folder name. Empty locale data leaves that table with no
 * native spellings in it and the guess comes back null.
 */
fun languageGuessProbeValue(): String? = guessLanguage(listOf("Русский"))

/**
 * Parses a minimal `mkvmerge -J` document through the real [parseProbe] and returns the track name it
 * read back, or null if anything about the parse went wrong.
 *
 * kotlinx.serialization generates its serializers at compile time, so this should need no reachability
 * config — but "should" is what a native probe is for, and the cost of being wrong is `inspect` and `mux`
 * failing on the first file they touch. The Cyrillic track name converges on the same expected string as
 * the charset, locale and YAML probes.
 */
fun jsonProbeValue(): String? {
    val json = """{"container":{"recognized":true,"supported":true},
                  "tracks":[{"id":0,"type":"audio","codec":"AAC",
                             "properties":{"track_name":"Русский"}}]}"""
    return (parseProbe(File("smoke.mkv"), json) as? ProbeResult.Probed)
        ?.allTracks?.singleOrNull()?.trackName
}

/**
 * Round-trips a one-key UTF-8 YAML document through the real [loadMapping] and returns
 * the value it read back, or null if anything about the load went wrong. snakeyaml is a
 * third-party dependency and the GraalVM reachability-metadata repository is disabled for
 * this build (see build.gradle.kts), so nothing but running the parser proves it works in
 * the native image. Passing no charset puts [detectCharset] on the same path, and a
 * Cyrillic value converges on the same expected string as the other two probes.
 */
fun yamlProbeValue(): String? {
    val file = File.createTempFile("mkvtool-smoke", ".yaml")
    return try {
        file.writeText("show: Русский\n", StandardCharsets.UTF_8)
        (loadMapping(file) as? MappingLoad.Loaded)?.value?.get("show") as? String
    } catch (_: Exception) {
        // The loader classifies rather than throws; this guards the temp-file I/O only, so a probe run
        // on a locked-down filesystem reports a FAIL line like the other two rather than a stack trace.
        null
    } finally {
        file.delete()
    }
}
