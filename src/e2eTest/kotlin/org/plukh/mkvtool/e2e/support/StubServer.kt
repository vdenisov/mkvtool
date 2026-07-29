package org.plukh.mkvtool.e2e.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A canned TheMovieDB, so `fetch-episodes` can be tested offline and deterministically.
 *
 * The command carries a hidden `--base-url` for exactly this: point it here and the whole network half of
 * the command runs against known bytes. The JDK's own `HttpServer` keeps it dependency-free, which is what
 * lets the tier stay runnable in the pinned build environment with no network at all.
 */

/**
 * Serve [routes] on an ephemeral loopback port for the duration of [body], which receives the base URL.
 *
 * Three details are decisions rather than defaults:
 *
 * - **Routing is an exact match on the path, and the query string is ignored.** Every real request carries
 *   `?api_key=...&language=...`, and none of that should decide which canned body comes back - a case
 *   pins the *response*, not the credentials.
 * - **An unknown path answers 404 with `{"status_message":"stub: no route"}`** - TheMovieDB's own error
 *   shape rather than an empty body, so the failure path a case exercises is the one the production
 *   fetcher would meet. Passing no routes at all is therefore a complete test of "every request fails".
 * - **Port 0**, read back after `create`, so cases can run concurrently without agreeing on a port.
 *
 * Returns whatever [body] returns; the server is stopped in a `finally`.
 */
fun <T> withStubServer(routes: Map<String, String>, body: (baseUrl: String) -> T): T {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val json = routes[exchange.requestURI.path]
        val bytes = (json ?: """{"status_message":"stub: no route"}""").toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(if (json != null) 200 else 404, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return try {
        body("http://127.0.0.1:${server.address.port}")
    } finally {
        server.stop(0)
    }
}
