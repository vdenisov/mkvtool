/**
 * Output seam, in two layers. What a command *computes* is a typed result model - a [CommandResult],
 * dumb data with no self-presentation - and what it *says while working* is a reified [OutputEvent]:
 * errors, warnings, narration, progress. Both travel through a [Renderer]; command logic never writes to
 * a stream and never colors anything itself.
 *
 * The layering exists so text and machine-readable output are two projections of one model rather than
 * one being parsed out of the other, which is why **no result data may travel over an event**: a finding
 * composed into a message string cannot be serialized as JSON later.
 *
 * [TextRenderer] is the text projection. It renders events itself and delegates each result to the
 * renderer bound to that result's type in a [ResultRendererRegistry], so presentation is a property of
 * the data rather than of the command that emitted it - the same result looks the same wherever it comes
 * from. [RenderHints] is the only channel by which a command may tune that presentation.
 */
package org.plukh.mkvtool.out
