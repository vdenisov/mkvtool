package org.plukh.mkvtool.out

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The registry is what makes presentation a property of the result type rather than of the command that
 * emitted it, so what it must get right is narrow: dispatch to the renderer filed under the result's own
 * class, and refuse loudly when there is none.
 */
class ResultRendererRegistryTest : FunSpec({

    fun style(buffer: ByteArrayOutputStream): TextStyle {
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        return TextStyle(colorEnabled = false, out = stream, err = stream)
    }

    test("a result goes to the renderer registered for its type") {
        val registry = ResultRendererRegistry()
            .register(Apple::class, ResultTextRenderer<Apple> { r, s -> s.out.println("apple ${r.size}") })
            .register(Pear::class, ResultTextRenderer<Pear> { r, s -> s.out.println("pear ${r.ripe}") })

        val buffer = ByteArrayOutputStream()
        registry.render(Pear(ripe = true), style(buffer))
        registry.render(Apple(size = 3), style(buffer))

        buffer.toString(Charsets.UTF_8).trimEnd().lines() shouldBe listOf("pear true", "apple 3")
    }

    test("an unregistered result fails loudly, naming the type nothing was filed under") {
        val registry = ResultRendererRegistry()
            .register(Apple::class, ResultTextRenderer<Apple> { _, _ -> })

        val thrown = shouldThrow<IllegalStateException> {
            registry.render(Pear(ripe = false), style(ByteArrayOutputStream()))
        }
        thrown.message!! shouldContain "Pear"
    }

    test("lookup is by exact class: a subtype is not covered by its supertype's renderer") {
        // Documented and deliberate — walking supertypes would need kotlin-reflect, which native-image
        // does without. Every concrete result type registers itself.
        val registry = ResultRendererRegistry()
            .register(Apple::class, ResultTextRenderer<Apple> { _, s -> s.out.println("apple") })

        shouldThrow<IllegalStateException> {
            registry.render(CrabApple(size = 1), style(ByteArrayOutputStream()))
        }
    }

    test("registeredTypes reports the table, which is how a command's coverage is checked") {
        val registry = ResultRendererRegistry()
            .register(Apple::class, ResultTextRenderer<Apple> { _, _ -> })
            .register(Pear::class, ResultTextRenderer<Pear> { _, _ -> })

        registry.registeredTypes shouldContainExactly setOf(Apple::class, Pear::class)
    }
})

private open class Apple(val size: Int) : CommandResult

private class CrabApple(size: Int) : Apple(size)

private class Pear(val ripe: Boolean) : CommandResult
