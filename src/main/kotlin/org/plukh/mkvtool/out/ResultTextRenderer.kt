package org.plukh.mkvtool.out

/**
 * How one result type is composed as text.
 *
 * Presentation is a property of the *data*, not of the command that produced it: a renderer is bound to
 * a result type once, in one table ([ResultRendererRegistry]), so the same result reads identically
 * whoever emitted it — the check report from `inspect` and from `mux`'s pre-flight are the same report
 * and must look like it. A command chooses what to *emit*; it does not get to choose how it looks.
 *
 * The type parameter is what keeps a leaf free of dispatch: it receives its own result, so there is no
 * `is X ->` test to write and no fall-through branch to get wrong. A result nothing is registered for
 * fails loudly in the registry instead.
 *
 * [RenderHints] is the one channel by which a command may tune what a renderer does — never replace it.
 */
fun interface ResultTextRenderer<T : CommandResult> {
    fun render(result: T, style: TextStyle)
}
