package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.cli.MuxCommand
import java.io.File

/**
 * The two user-facing descriptions of `config.yaml` — the reference document and the example config —
 * name the same set of keys, and both agree with what the loader actually parses.
 *
 * This exists because that drift has already happened: `general.title` shipped and stayed undocumented
 * for a release, and nothing forces either file open during feature work. Note what the two directions
 * catch. Comparing the documents to each other catches a key documented in one place only; comparing both
 * to [CONFIG_KEYS] catches the case where a key ships and *neither* document mentions it, which the
 * document-to-document check alone would call consistent.
 *
 * The failure is deliberately not silent-by-omission: adding a key to the loader means adding it here,
 * and this test then names the document that is missing it.
 *
 * Only the key *vocabulary* is mechanized. Whether the prose around a key describes it correctly cannot
 * be, and stays with a human reading.
 */
class ConfigDocsParityTest : FunSpec({

    /** Where the template lives, relative to the repository root. Spelled once — three things read it. */
    val EXAMPLE_CONFIG_PATH = "docs/config.example.yaml"

    /**
     * Every key `parseConfig` reads, as leaf names. Leaf rather than dotted paths because the reference
     * shows the config in fragments — the `mainSource` block is documented without its parent — so a path
     * would be present or absent depending on where an example happens to start.
     */
    val configKeys = setOf(
        "general", "destinationDir", "allowedExtensions", "mkvmergeExe", "title",
        "mainSource", "videoTrack", "audioTracks", "subtitleTracks",
        "id", "language", "default", "charset",
        "additionalSources", "file", "tracks",
        "additionalOptions", "trackOrder",
    )

    /**
     * A key line is `name:` followed by end of line or by something that looks like a YAML value — a
     * quote, a digit, a bracket, a boolean. That is what separates a key from prose inside a comment
     * (`# Optional: character set override` is not a `charset` sibling), and both files write every scalar
     * value quoted or numeric, so nothing real is missed. A leading `#` counts: the example config
     * documents its optional keys by commenting them out, which is exactly the part that must stay in
     * step with the reference.
     */
    val keyLine = Regex("""^\s*(?:#\s*)?(?:-\s*)?([A-Za-z][A-Za-z0-9]*)\s*:\s*(?:$|["'\[\d]|true|false|yes|no)""")

    fun keysIn(lines: List<String>): Set<String> =
        lines.mapNotNull { keyLine.find(it)?.groupValues?.get(1) }.toSet()

    fun repoFile(path: String): File = File(path).also {
        check(it.isFile) { "$path not found — this test reads the repository, so it runs from the project directory" }
    }

    /** The `yaml` fenced blocks only: the reference's other fences are sample console output. */
    fun yamlFences(markdown: File): List<String> {
        val fenced = mutableListOf<String>()
        var inside = false
        markdown.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("```yaml") -> inside = true
                line.startsWith("```") -> inside = false
                inside -> fenced += line
            }
        }
        return fenced
    }

    val referenceKeys = keysIn(yamlFences(repoFile("docs/reference.md")))
    val exampleKeys = keysIn(repoFile(EXAMPLE_CONFIG_PATH).readLines(Charsets.UTF_8))

    test("the reference documents every config key") {
        configKeys - referenceKeys shouldBe emptySet()
    }

    test("the example config uses every config key") {
        configKeys - exampleKeys shouldBe emptySet()
    }

    test("the reference names no key the loader does not read") {
        referenceKeys - configKeys shouldBe emptySet()
    }

    test("the example config names no key the loader does not read") {
        exampleKeys - configKeys shouldBe emptySet()
    }

    test("both documents were actually read") {
        // Without this, a fence marker that changed shape would empty both sets and pass every check
        // above by describing nothing.
        referenceKeys.shouldNotBeEmpty()
        exampleKeys.shouldNotBeEmpty()
        (configKeys - referenceKeys - exampleKeys).shouldBeEmpty()
    }

    /**
     * The no-config error names the template by URL, because a binary on `PATH` has no directory to
     * point at. Nothing else can notice that URL going stale: it is only ever printed, and the harness
     * case covering the message asserts on the bare file name rather than on a path. So the one thing
     * that can rot — where in the repository the file sits — is checked here against the file itself.
     */
    test("the URL mux prints names the template's actual path") {
        repoFile(EXAMPLE_CONFIG_PATH)
        MuxCommand.EXAMPLE_CONFIG_URL shouldBe
            "https://github.com/vdenisov/mkvtool/blob/main/$EXAMPLE_CONFIG_PATH"
    }

    /**
     * Parity above is about the key *vocabulary* and reads both documents as text, so it would pass on
     * a template that is not loadable YAML at all — and the template's whole job is to be copied and
     * run. This loads it the way `mux` does.
     */
    test("the template loads as a config") {
        when (val load = loadConfig(repoFile(EXAMPLE_CONFIG_PATH))) {
            is MappingLoad.Loaded -> {
                // The live half of the file is a working single-source config; everything optional is
                // commented out, so this is also what a reader gets by copying it unedited.
                load.value.general.destinationDir shouldBe "mkv"
                load.value.mainSource.audioTracks.map { it.id } shouldBe listOf(1, 2)
                load.value.mainSource.subtitleTracks.map { it.id } shouldBe listOf(4)
                load.value.additionalSources.shouldBeEmpty()
                // Omitted on purpose: mux derives it, and the file says so.
                load.value.trackOrder shouldBe null
            }
            else -> error("the template did not load: $load")
        }
    }
})
