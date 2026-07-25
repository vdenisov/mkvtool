package org.plukh.mkvtool.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.SSLContext

/**
 * Probe logic for the native-image failure modes that fail *silently*: a native binary
 * built without `-H:+AddAllCharsets` cannot decode single-byte charsets like
 * Windows-1251, one built without locale-data inclusion returns empty CLDR display
 * names, and a dependency that needs reachability config it did not get fails only when
 * its code is first reached. All work on any ordinary JVM, so only an end-to-end run of
 * the actual native binary catches a broken build - which is what the hidden
 * `native-smoke` command does.
 *
 * Every probe but the first now runs a real utility, which is the shape they were meant to
 * reach. [decodeWindows1251Strict] stays self-contained because `to-utf8`'s decoder takes a
 * charset and a file, and a probe wants neither — do not grow a shared abstraction for it.
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
 * The native CLDR display name for a language [code], e.g. "ru" -> "Русский", through the
 * real [languageNativeOf] that resolves `${'$'}{languageNative}`. Null when the JDK has no
 * display name and echoes the code back instead — exactly the empty-locale-data symptom on a
 * misconfigured native binary.
 *
 * Distinct from [languageGuessProbeValue] on purpose: that one proves the token table built
 * from every curated locale survived the build, this one proves a display name and its
 * in-locale upper-casing did. A build can lose either without the other.
 */
fun nativeLanguageName(code: String): String? = languageNativeOf(code)

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

/**
 * Writes a one-episode `episodes.yaml` through the real [writeEpisodesYaml] and reads the name back out
 * of the text.
 *
 * The load side is not the whole of snakeyaml: dumping walks a different half of the library, and it is
 * the half `fetch-episodes` depends on. A build where it fails writes an unusable `episodes.yaml` — or,
 * with the `allowUnicode` path broken, one where every Cyrillic title has become an escape sequence,
 * which is why this asserts on the raw text rather than on a re-parse.
 */
fun yamlDumpProbeValue(): String? {
    val file = File.createTempFile("mkvtool-smoke-dump", ".yaml")
    return try {
        writeEpisodesYaml(file, "Русский", 2006, "1", "", null, listOf(FetchedEpisode(1, "Русский")))
        file.readText(StandardCharsets.UTF_8)
            .lineSequence()
            .firstOrNull { it.startsWith("show:") }
            ?.substringAfter("show:")
            ?.trim()
    } catch (_: Exception) {
        null
    } finally {
        file.delete()
    }
}

/**
 * Runs a real request through the real [HttpTmdbFetcher], at a loopback port nothing listens on.
 *
 * What it proves: the TLS stack is in the image (without the security services `SSLContext.getDefault()`
 * throws), the HTTP client builds, and a request over `https` gets as far as the network before failing.
 * What it cannot prove offline is a completed handshake — that needs a server with a certificate, which
 * is more machinery than a build probe should carry. The remaining risk is covered by the live contract
 * test in the Groovy suite.
 *
 * Returns the scheme it got through on, so the expected value reads like the other probes.
 */
fun httpsProbeValue(): String? =
    try {
        SSLContext.getDefault().socketFactory
        // Nothing can answer on port 1, so a *transport* failure is the success case: it means the
        // request was built and run rather than refused by a missing protocol or provider.
        HttpTmdbFetcher("https://127.0.0.1:1", "smoke").get("/3/tv/0", "en-US")
        null
    } catch (e: TmdbException) {
        if (e.message?.contains("failed") == true) "https" else null
    } catch (_: Exception) {
        null
    }
