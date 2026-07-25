package org.plukh.mkvtool.out

import kotlin.reflect.KClass

/**
 * The text medium's result-type → renderer table: one binding per result type, assembled once for the
 * whole application rather than per command. That is what makes "the same data renders the same way"
 * structural instead of a convention — a command cannot bind its own rendering to a type another command
 * also emits, because it never touches this table.
 *
 * [register] pairs the key with the renderer's own type parameter at compile time, which is what makes
 * the single unchecked cast in [render] safe: nothing can be filed under a class it cannot render.
 *
 * Lookup is by **exact** class — no walk up the supertypes, which would need `kotlin-reflect` and with it
 * a reachability problem under native-image. Every concrete result type therefore registers itself; one
 * that does not is a loud failure here rather than silence at the point of use.
 */
class ResultRendererRegistry {

    private val byType = LinkedHashMap<KClass<out CommandResult>, ResultTextRenderer<*>>()

    /** Binds [renderer] to [type]. Returns this registry, so a table reads as one chained expression. */
    fun <T : CommandResult> register(
        type: KClass<T>,
        renderer: ResultTextRenderer<T>,
    ): ResultRendererRegistry {
        byType[type] = renderer
        return this
    }

    /** Every result type this table can render, in registration order — what a completeness test asserts on. */
    val registeredTypes: Set<KClass<out CommandResult>> get() = byType.keys.toSet()

    /** Renders [result] through its registered renderer, or fails: an unrenderable result is a wiring bug. */
    fun render(result: CommandResult, style: TextStyle) {
        val renderer = byType[result::class]
            ?: error("no result renderer registered for ${result::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        (renderer as ResultTextRenderer<CommandResult>).render(result, style)
    }
}
