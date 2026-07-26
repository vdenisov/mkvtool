package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.ExternalSlot
import org.plukh.mkvtool.core.ProbeResult
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.core.TrackSelection
import org.plukh.mkvtool.core.TrackSignature
import org.plukh.mkvtool.core.TrackSlot
import org.plukh.mkvtool.core.VariantIdentity
import org.plukh.mkvtool.core.buildCheckReport
import org.plukh.mkvtool.core.signatureOf
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Probe records built by hand, for the specs that render a check report without going near mkvmerge.
 * Shared by the report's own spec and by the binding table's, which needs a report to prove a render hint
 * reaches the renderer it is meant for.
 */
internal fun t(
    id: Int,
    type: String,
    codec: String = "AAC",
    language: String = "eng",
    name: String = "",
    default: Boolean = false,
    forced: Boolean = false,
): ProbedTrack = ProbedTrack(id, type, codec, "ID", language, name, default, forced)

internal fun probed(fileName: String, vararg tracks: ProbedTrack, chapters: Int = 0): ProbeResult.Probed =
    ProbeResult.Probed(
        file = File(fileName),
        allTracks = tracks.toList(),
        tracks = tracks.associate {
            it.id to TrackSlot(it.id, signatureOf(it), if (it.type == "video") (it.trackName ?: "") else null)
        },
        chapters = chapters,
    )

/**
 * Build a check report from probe records and render it, returning the text. Shared rather than copied: the
 * report's own spec, the binding table's and the reference document's all need the same three lines, and a
 * third copy is what put the first two here.
 */
internal fun render(
    infos: List<ProbeResult>,
    externals: Map<String, Map<String, ExternalSlot>> = emptyMap(),
    selection: TrackSelection = TrackSelection.NONE,
    label: String = "Consistency check",
    verbose: Boolean = false,
    colour: Boolean = false,
): String {
    val report = buildCheckReport(
        infos,
        externalsOf = { externals[it.file.name] ?: emptyMap() },
        selection = selection,
        headerLabel = label,
    )
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, true, Charsets.UTF_8)
    CheckReportRenderer(verbose).render(report, TextStyle(colour, stream, stream))
    return buffer.toString(Charsets.UTF_8)
}

internal fun ex(label: String, language: String = "rus", guessed: Boolean = false): ExternalSlot = ExternalSlot(
    key = "$label/audio/mka",
    signature = TrackSignature("audio", "Matroska", language, "", default = false, forced = false),
    guessed = guessed,
    variant = VariantIdentity(label, leaf = "[Group$label]", suffix = null, dirRel = "Rus sound/[Group$label]", collision = false),
)
