package org.plukh.mkvtool.core

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * "Read this file, get a mapping or a named reason why not" — the loader behind `config.yaml` and
 * `episodes.yaml` in every command that reads either.
 *
 * It **classifies and nothing more**: [MappingLoad.Problem] carries a bare, lowercase, unpunctuated
 * fragment that the caller finishes in its own words and prints through its own channel, because what to
 * do about an unusable file is exactly what differs between muxing and reporting — `mux` appends
 * "; there is nothing to mux with." and exits 2, `inspect` appends "; continuing without it." and carries
 * on. Deciding here would take that choice away from both.
 */

/** The outcome of one load: either the mapping (transformed, if a transform was given) or the reason. */
sealed interface MappingLoad<out T> {
    data class Loaded<T>(val value: T) : MappingLoad<T>

    /** [message] is a sentence fragment for the caller to finish — never capitalised, never punctuated. */
    data class Problem(val message: String) : MappingLoad<Nothing>
}

/** Load [file] as a YAML mapping. See the generic overload for [charset] and the failure classes. */
fun loadMapping(file: File, charset: Charset? = null): MappingLoad<Map<*, *>> =
    loadMapping(file, charset) { it }

/**
 * Load [file] as a YAML mapping and hand it to [transform], which runs **inside** the guard — so a
 * transform that rejects its input (`episode: "one"`) is reported as a problem rather than escaping as a
 * stack trace, and callers can normalise into a typed model without writing their own try.
 *
 * [charset] null means Groovy's auto-detection ([readTextDetected]), which is `config.yaml`'s contract as
 * a hand-written file; a non-null charset is `episodes.yaml`'s fixed UTF-8-both-ways contract. **Do not
 * unify the two** — each would break the other (CLAUDE.md, "episodes.yaml is hand-editable").
 *
 * Four outcomes, three of them problems: unreadable or unparseable, empty, parsed-but-not-a-mapping, and
 * the mapping itself. Parsing is a safe load, so a document naming a Java type (`!!java.lang.Object`)
 * becomes a parse problem instead of instantiating anything; v1's snakeyaml 1.30 default would have
 * constructed it. A zero-padded number (`010`) or one with colons (`1:30`) stays the text it was written as,
 * not YAML 1.1's octal or base 60; a reader wanting a number converts the text.
 */
fun <T> loadMapping(file: File, charset: Charset? = null, transform: (Map<*, *>) -> T): MappingLoad<T> =
    try {
        val text = if (charset != null) file.readText(charset) else readTextDetected(file)
        val loaded: Any? = textPreservingYaml().load(text)
        when {
            loaded == null -> MappingLoad.Problem("${file.name} is empty")
            loaded !is Map<*, *> ->
                MappingLoad.Problem("${file.name} is not a mapping (found ${loaded.javaClass.simpleName})")
            else -> MappingLoad.Loaded(transform(loaded))
        }
    } catch (e: Exception) {
        MappingLoad.Problem("could not parse ${file.name}: ${firstLine(e)}")
    }

/** The first non-empty line of [e]'s message, falling back to the exception itself. Clamped to one line
 *  because snakeyaml appends a multi-line context dump that turns one warning into a screenful. */
private fun firstLine(e: Exception): String =
    e.message?.lineSequence()?.firstOrNull { it.isNotEmpty() } ?: e.toString()

/** A safe-load [Yaml] whose resolver keeps the numbers YAML 1.1 would misread as text. */
private fun textPreservingYaml(): Yaml {
    val loaderOptions = LoaderOptions()
    val dumperOptions = DumperOptions()
    return Yaml(
        SafeConstructor(loaderOptions),
        Representer(dumperOptions),
        dumperOptions,
        loaderOptions,
        TextPreservingResolver(),
    )
}

// YAML 1.1 reads `010` as octal 8 and `1:30` as 90 (base 60); readers coerce text, so text loses nothing.
private val MISREAD_NUMBER: Pattern =
    Pattern.compile("""^[-+]?(?:0[0-9_]+|[0-9][0-9_]*(?::[0-5]?[0-9])+(?:\.[0-9_]*)?)$""")

private class TextPreservingResolver : Resolver() {
    override fun addImplicitResolvers() {
        // First registered wins, so this has to precede the stock INT and FLOAT resolvers.
        addImplicitResolver(Tag.STR, MISREAD_NUMBER, "-+0123456789")
        super.addImplicitResolvers()
    }
}
