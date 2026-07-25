package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.decodeWindows1251Strict
import org.plukh.mkvtool.core.httpsProbeValue
import org.plukh.mkvtool.core.jsonProbeValue
import org.plukh.mkvtool.core.languageGuessProbeValue
import org.plukh.mkvtool.core.nativeLanguageName
import org.plukh.mkvtool.core.yamlDumpProbeValue
import org.plukh.mkvtool.core.yamlProbeValue
import picocli.CommandLine.Command
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

/**
 * Hidden diagnostic that verifies a native-image build kept its charsets and locale
 * data, and that the YAML parser runs there at all. It self-asserts and returns a
 * non-zero exit code on any mismatch, so CI needs only to run this against the native
 * binary and check the exit code - encoding-independent String equality inside the
 * runtime, not fragile console-output grepping. The printed values are for humans.
 *
 * Hidden because it is a build probe, not a user feature. Two of its four probes already
 * run the real utilities; the other two stay self-contained until `to-utf8`'s decoder and
 * the `${'$'}{languageNative}` substitution can carry them (see core/NativeSmoke.kt).
 */
@Command(
    name = "native-smoke",
    hidden = true,
    description = ["Diagnostic: verify native-image charset, locale-data and YAML support."],
)
class NativeSmokeCommand : Callable<Int> {

    override fun call(): Int {
        // Windows-1251 bytes for "Русский" (Р у с с к и й). The native locale name for
        // "ru" and the YAML probe's value are the same string, so those three probes
        // converge on one expected value; the language guess answers with the code that
        // string resolves to, which is the whole point of running it.
        val cp1251Sample = byteArrayOf(
            0xD0.toByte(), 0xF3.toByte(), 0xF1.toByte(), 0xF1.toByte(),
            0xEA.toByte(), 0xE8.toByte(), 0xE9.toByte(),
        )
        val expected = "Русский"
        val expectedGuess = "rus"
        val expectedScheme = "https"

        // Print diagnostics as explicit UTF-8: a native binary on a legacy Windows
        // console codepage would otherwise mangle the Cyrillic and hide the real result.
        val utf8Out = PrintStream(System.out, true, StandardCharsets.UTF_8.name())

        val decoded = decodeWindows1251Strict(cp1251Sample)
        val nativeName = nativeLanguageName("ru")
        val languageGuess = languageGuessProbeValue()
        val jsonValue = jsonProbeValue()
        val yamlValue = yamlProbeValue()
        val yamlDumpValue = yamlDumpProbeValue()
        val httpsValue = httpsProbeValue()

        utf8Out.println("charset (windows-1251 decode): $decoded")
        utf8Out.println("locale (ru native name):       $nativeName")
        utf8Out.println("language (guess for Русский):  $languageGuess")
        utf8Out.println("json (parsed track name):      $jsonValue")
        utf8Out.println("yaml (round-tripped value):    $yamlValue")
        utf8Out.println("yaml (dumped show name):       $yamlDumpValue")
        utf8Out.println("https (scheme reached):        $httpsValue")

        val charsetOk = decoded == expected
        val localeOk = nativeName == expected
        val languageOk = languageGuess == expectedGuess
        val jsonOk = jsonValue == expected
        val yamlOk = yamlValue == expected
        val yamlDumpOk = yamlDumpValue == expected
        val httpsOk = httpsValue == expectedScheme
        if (charsetOk && localeOk && languageOk && jsonOk && yamlOk && yamlDumpOk && httpsOk) {
            utf8Out.println("native-smoke: OK")
            return 0
        }

        if (!charsetOk) utf8Out.println("native-smoke: FAIL charset - expected '$expected', got '$decoded'")
        if (!localeOk) utf8Out.println("native-smoke: FAIL locale - expected '$expected', got '$nativeName'")
        if (!languageOk) {
            utf8Out.println("native-smoke: FAIL language - expected '$expectedGuess', got '$languageGuess'")
        }
        if (!jsonOk) utf8Out.println("native-smoke: FAIL json - expected '$expected', got '$jsonValue'")
        if (!yamlOk) utf8Out.println("native-smoke: FAIL yaml - expected '$expected', got '$yamlValue'")
        if (!yamlDumpOk) {
            utf8Out.println("native-smoke: FAIL yaml dump - expected '$expected', got '$yamlDumpValue'")
        }
        if (!httpsOk) {
            utf8Out.println("native-smoke: FAIL https - expected '$expectedScheme', got '$httpsValue'")
        }
        return 1
    }
}
