package org.plukh.mkvtool.core

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.net.URI

/**
 * The HTTP half, against a stub server on a loopback port — in-process, offline and deterministic, the
 * same trick the Groovy harness uses. What is under test is how a failure is *reported*: the API answers
 * "invalid key" as a status message in the body, and that sentence is what the user needs to see.
 */
class TmdbTest : FunSpec({

    /** Serves one canned response and records what was asked for. */
    fun withServer(status: Int, body: String, test: (String, () -> URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var asked: URI? = null
        server.createContext("/") { exchange ->
            asked = exchange.requestURI
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}") { asked!! }
        } finally {
            server.stop(0)
        }
    }

    test("a 200 hands back the body, keyed and localized in the query") {
        withServer(200, """{"name":"Twin Peaks"}""") { baseUrl, asked ->
            HttpTmdbFetcher(baseUrl, "the-key").get("/3/tv/1920", "ru-RU") shouldBe """{"name":"Twin Peaks"}"""

            asked().path shouldBe "/3/tv/1920"
            asked().query shouldContain "api_key=the-key"
            asked().query shouldContain "language=ru-RU"
        }
    }

    test("a key needing escaping is escaped rather than pasted into the URL") {
        withServer(200, "{}") { baseUrl, asked ->
            HttpTmdbFetcher(baseUrl, "a b&c").get("/3/tv/1", "en-US")
            asked().rawQuery shouldContain "api_key=a+b%26c"
        }
    }

    test("the API's own explanation wins over the status code") {
        withServer(401, """{"status_message":"Invalid API key: You must be granted a valid key."}""") { baseUrl, _ ->
            val thrown = shouldThrow<TmdbException> { HttpTmdbFetcher(baseUrl, "bad").get("/3/tv/1", "en-US") }
            thrown.message shouldContain "HTTP 401"
            thrown.message shouldContain "Invalid API key"
        }
    }

    test("a failure with no explanation falls back on the raw body") {
        withServer(404, "not found, sorry") { baseUrl, _ ->
            val thrown = shouldThrow<TmdbException> { HttpTmdbFetcher(baseUrl, "k").get("/3/tv/1", "en-US") }
            thrown.message shouldContain "HTTP 404"
            thrown.message shouldContain "not found, sorry"
        }
    }

    test("an empty status_message is no explanation either") {
        withServer(500, """{"status_message":""}""") { baseUrl, _ ->
            shouldThrow<TmdbException> { HttpTmdbFetcher(baseUrl, "k").get("/3/tv/1", "en-US") }
                .message shouldContain """{"status_message":""}"""
        }
    }

    test("a refused connection is reported, not stack-traced") {
        // Port 1 on loopback: nothing listens there, so this is the offline case in miniature.
        val thrown = shouldThrow<TmdbException> {
            HttpTmdbFetcher("http://127.0.0.1:1", "k").get("/3/tv/1", "en-US")
        }
        thrown.message shouldContain "Request to http://127.0.0.1:1/3/tv/1 failed"
        thrown.message shouldNotContain "java.net.http"
    }
})
