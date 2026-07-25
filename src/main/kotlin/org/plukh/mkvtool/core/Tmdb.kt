package org.plukh.mkvtool.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The TheMovieDB half of `fetch-episodes`: one GET returning a body, and the documents that come back.
 *
 * The seam is [TmdbFetcher] rather than an HTTP client, so everything above it — the fallback rules, the
 * shape of what gets written — is tested with canned bodies and no server at all.
 */

/**
 * A fetch that could not produce a usable answer: the network refused it, the API refused it, or the body
 * was not what it claims to be. Its message is written for the user, since it is what `fetch-episodes`
 * prints before exiting 3.
 */
class TmdbException(message: String) : RuntimeException(message)

/**
 * One GET against the API, returning the raw body. Throws [TmdbException] on anything but a usable 200.
 *
 * The locale is per request rather than per fetcher because the en-US fill-in re-requests the very same
 * paths in a different one.
 */
fun interface TmdbFetcher {
    fun get(path: String, locale: String): String
}

/** The en-US fetch, which is both the default locale and what an untranslated name falls back to. */
fun TmdbFetcher.get(path: String): String = get(path, "en-US")

/**
 * The real fetcher.
 *
 * TheMovieDB reports failures twice — as an HTTP status and as a `status_message` in the body — so the
 * body's message wins where there is one: "Invalid API key" tells the user what to do about it and
 * "HTTP 401" does not.
 */
class HttpTmdbFetcher(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient = defaultHttpClient(),
) : TmdbFetcher {

    override fun get(path: String, locale: String): String {
        val url = "$baseUrl$path?api_key=${encode(apiKey)}&language=${encode(locale)}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response: HttpResponse<String> = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw TmdbException("Request to $baseUrl$path failed: ${e.message}")
        }

        if (response.statusCode() != 200) {
            throw TmdbException(
                "TheMovieDB returned HTTP ${response.statusCode()} for $path: ${statusDetail(response.body())}"
            )
        }
        return response.body()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** The API's own explanation, or the raw body when it did not give one. */
    private fun statusDetail(body: String): String =
        try {
            TMDB_JSON.decodeFromString<StatusDto>(body).statusMessage.orEmpty().ifEmpty { body }
        } catch (_: SerializationException) {
            body
        }
}

private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

/** Lenient by necessity: TheMovieDB returns dozens of fields per document and this reads four of them. */
internal val TMDB_JSON = Json { ignoreUnknownKeys = true }

/** A show, as far as this reads it. [firstAirDate] is absent or empty for a show that has not aired. */
@Serializable
internal data class ShowDto(
    val name: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
)

/** One season and its episodes. */
@Serializable
internal data class SeasonDto(
    val name: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
internal data class EpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String? = null,
)

@Serializable
private data class StatusDto(
    @SerialName("status_message") val statusMessage: String? = null,
)

/** Parse one fetched body, naming [path] when it is not the document it should be. */
internal inline fun <reified T> parseTmdb(body: String, path: String): T =
    try {
        TMDB_JSON.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        throw TmdbException("TheMovieDB returned a non-JSON response for $path")
    }
