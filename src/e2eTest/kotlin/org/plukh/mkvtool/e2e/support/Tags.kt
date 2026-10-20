package org.plukh.mkvtool.e2e.support

import io.kotest.core.Tag

/**
 * One representative case per command this tier covers, plus the Cyrillic fetch-to-rename round trip, which is
 * what runs where the whole tier would cost more than it tells: the native binaries off Linux and the fat jar,
 * whose question is what differs per packaging rather than whether the tool is correct. Selected with
 * `-Pe2eTags=Smoke`.
 */
object Smoke : Tag()

/**
 * The case that calls the real TheMovieDB API. Excluded unless the tag expression names it - the
 * `live-contract` job does, on its schedule and on dispatch, with `-Pe2eTags=Live` - and skipped without
 * `TMDB_API_KEY`, which fails a run that selected nothing else.
 */
object Live : Tag()
