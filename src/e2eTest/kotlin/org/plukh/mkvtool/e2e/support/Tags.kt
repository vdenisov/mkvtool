package org.plukh.mkvtool.e2e.support

import io.kotest.core.Tag

/**
 * One representative case per command this tier covers, which is what runs where the whole tier would cost
 * more than it tells: the native binaries off Linux and the fat jar, whose question is what differs per
 * packaging rather than whether the tool is correct. Selected with `-Pe2eTags=Smoke`.
 */
object Smoke : Tag()
