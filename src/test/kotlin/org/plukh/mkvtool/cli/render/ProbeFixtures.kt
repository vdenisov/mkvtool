package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.ExternalSlot
import org.plukh.mkvtool.core.ProbeResult
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.core.TrackSignature
import org.plukh.mkvtool.core.TrackSlot
import org.plukh.mkvtool.core.VariantIdentity
import org.plukh.mkvtool.core.signatureOf
import java.io.File

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

internal fun ex(label: String, language: String = "rus", guessed: Boolean = false): ExternalSlot = ExternalSlot(
    key = "$label/audio/mka",
    signature = TrackSignature("audio", "Matroska", language, "", default = false, forced = false),
    guessed = guessed,
    variant = VariantIdentity(label, leaf = "[Group$label]", suffix = null, dirRel = "Rus sound/[Group$label]", collision = false),
)
