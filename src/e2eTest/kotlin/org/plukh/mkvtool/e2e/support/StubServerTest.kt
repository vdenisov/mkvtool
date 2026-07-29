package org.plukh.mkvtool.e2e.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * The canned TheMovieDB.
 *
 * Asserted through a real HTTP client rather than by inspecting the handler, because what a
 * `fetch-episodes` case depends on is the *response* - a status the production fetcher classifies and a
 * body it parses. Two of the three behaviours below are what a case relies on without ever saying so: the
 * query string being ignored (every real request carries one) and the 404 body being TheMovieDB-shaped
 * (the failure path a case exercises has to look like the real failure).
 */
class StubServerTest : FunSpec({

    val client: HttpClient = HttpClient.newHttpClient()

    fun get(url: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

    test("a known path returns its canned body") {
        val body = """{"name":"Stub Show"}"""

        withStubServer(mapOf("/3/tv/2260" to body)) { baseUrl ->
            val response = get("$baseUrl/3/tv/2260")

            response.statusCode() shouldBe 200
            response.body() shouldBe body
            response.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
        }
    }

    test("the query string does not decide the route") {
        // Every real request carries ?api_key=...&language=..., and none of it should pick the body. A
        // case pins the response, not the credentials.
        withStubServer(mapOf("/3/tv/2260" to """{"name":"Stub Show"}""")) { baseUrl ->
            get("$baseUrl/3/tv/2260?api_key=whatever&language=ru-RU").statusCode() shouldBe 200
        }
    }

    test("an unknown path 404s with TheMovieDB's own error shape") {
        withStubServer(emptyMap()) { baseUrl ->
            val response = get("$baseUrl/3/tv/9999")

            response.statusCode() shouldBe 404
            // Not an empty body: the fetcher reports the API's own message, so a case asserting on a
            // failure needs a message to be reported.
            response.body() shouldContain "status_message"
        }
    }

    test("the body's value is returned and the server is stopped afterwards") {
        val port = withStubServer(mapOf("/x" to "{}")) { baseUrl ->
            get("$baseUrl/x").statusCode() shouldBe 200
            URI.create(baseUrl).port
        }

        // Stopped in a finally, so cases running concurrently do not accumulate listeners. Connecting to
        // the now-free port must fail rather than reach a server that outlived its body.
        val stopped = runCatching { get("http://127.0.0.1:$port/x") }
        stopped.isFailure shouldBe true
    }
})
