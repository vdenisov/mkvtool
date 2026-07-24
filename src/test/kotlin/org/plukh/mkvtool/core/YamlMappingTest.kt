package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The loader classifies, and only classifies — the Kotlin twin of the Groovy harness's
 * `126_yaml_mapping_loader`, which asserts the same six things in-process against `src/lib/yaml.groovy`.
 * Every problem here is a bare fragment the caller finishes, so the text is pinned by `contains`, not by
 * equality: `mux` appends "; there is nothing to mux with." and exits, `inspect` appends "; continuing
 * without it." and does not.
 */
class YamlMappingTest : FunSpec({

    data class Case(
        val label: String,
        val content: String,
        val expected: String,
    )

    context("problem classification") {
        withData(
            nameFn = { it.label },
            Case("an empty file has no value", "", "is empty"),
            Case("a comment-only file is empty too", "# nothing here\n", "is empty"),
            Case("a sequence is not a mapping", "- one\n- two\n", "is not a mapping (found ArrayList)"),
            Case("a bare scalar is not a mapping", "just a string\n", "is not a mapping (found String)"),
            Case("a syntax error cannot be parsed", "a: 1\n  b: [\n", "could not parse"),
            // Safe-load: v1's snakeyaml 1.30 default would have instantiated this.
            Case("a global tag is refused rather than instantiated", "a: !!java.io.File [x]\n", "could not parse"),
        ) {
            val load = loadMapping(write(tempdir(), "t.yaml", it.content))
            load.shouldBeInstanceOf<MappingLoad.Problem>()
            load.message shouldContain it.expected
        }
    }

    test("a mapping loads") {
        val load = loadMapping(write(tempdir(), "good.yaml", "a: 1\nb: two\n"))
        load.shouldBeInstanceOf<MappingLoad.Loaded<Map<*, *>>>()
        load.value shouldBe mapOf("a" to 1, "b" to "two")
    }

    test("the problem names the file and stays a lowercase fragment") {
        val load = loadMapping(write(tempdir(), "config.yaml", ""))
        load.shouldBeInstanceOf<MappingLoad.Problem>()
        load.message shouldBe "config.yaml is empty"
    }

    test("a parse problem is clamped to one line") {
        val load = loadMapping(write(tempdir(), "broken.yaml", "a: 1\n  b: [\n"))
        load.shouldBeInstanceOf<MappingLoad.Problem>()
        load.message shouldStartWith "could not parse broken.yaml: "
        // snakeyaml dumps a screenful of context after the first line; one warning must stay one line.
        load.message.lines() shouldBe listOf(load.message)
    }

    test("a missing file is a problem, not an exception") {
        val load = loadMapping(File(tempdir(), "absent.yaml"))
        load.shouldBeInstanceOf<MappingLoad.Problem>()
        load.message shouldStartWith "could not parse absent.yaml: "
    }

    context("transform") {
        test("runs inside the guard, so throwing is a problem rather than a stack trace") {
            val load = loadMapping<Map<*, *>>(write(tempdir(), "t.yaml", "a: 1\n")) {
                throw NumberFormatException("boom")
            }
            load.shouldBeInstanceOf<MappingLoad.Problem>()
            load.message shouldBe "could not parse t.yaml: boom"
        }

        test("maps into the caller's own type") {
            val load = loadMapping(write(tempdir(), "t.yaml", "show: Hellsing\n")) { it["show"] as String }
            load.shouldBeInstanceOf<MappingLoad.Loaded<String>>()
            load.value shouldBe "Hellsing"
        }

        test("does not run when the file is unusable") {
            var ran = false
            loadMapping<Map<*, *>>(write(tempdir(), "empty.yaml", "")) { ran = true; it }
            ran shouldBe false
        }
    }

    context("charset") {
        test("an explicit charset is used verbatim — episodes.yaml's fixed UTF-8 contract") {
            val file = write(tempdir(), "episodes.yaml", "show: Тест\n", StandardCharsets.UTF_8)
            val load = loadMapping(file, StandardCharsets.UTF_8)
            load.shouldBeInstanceOf<MappingLoad.Loaded<Map<*, *>>>()
            load.value["show"] shouldBe "Тест"
        }

        test("no charset auto-detects — config.yaml's hand-written contract") {
            val file = write(tempdir(), "config.yaml", "title: Тестовый заголовок сериала\n", StandardCharsets.UTF_8)
            val load = loadMapping(file)
            load.shouldBeInstanceOf<MappingLoad.Loaded<Map<*, *>>>()
            load.value["title"] shouldBe "Тестовый заголовок сериала"
        }

        test("a UTF-8 BOM does not become part of the first key") {
            val dir = tempdir()
            val file = File(dir, "bom.yaml")
            file.writeBytes(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "title: Show\n".toByteArray(StandardCharsets.UTF_8),
            )
            val load = loadMapping(file)
            load.shouldBeInstanceOf<MappingLoad.Loaded<Map<*, *>>>()
            load.value["title"] shouldBe "Show"
        }
    }
})

private fun write(dir: File, name: String, text: String, charset: Charset = StandardCharsets.UTF_8): File =
    File(dir, name).apply { writeText(text, charset) }
