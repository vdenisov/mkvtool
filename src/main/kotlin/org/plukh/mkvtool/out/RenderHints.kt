package org.plukh.mkvtool.out

/**
 * What a command may tell the renderers about presentation — and the only thing it may tell them. Hints
 * tune how much of a rendering is shown; they never change what it says, and they never substitute one
 * rendering for another. Medium-agnostic by design: a machine-readable renderer is free to honour a hint
 * or ignore it.
 *
 * [verboseFileLists] is `--check-verbose`: the check report's file-evidence lists print in full instead
 * of truncating past a limit. It is the whole hint vocabulary today.
 */
data class RenderHints(
    val verboseFileLists: Boolean = false,
)
