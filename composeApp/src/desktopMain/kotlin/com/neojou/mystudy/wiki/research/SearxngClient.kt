package com.neojou.mystudy.wiki.research

import com.neojou.mystudy.AppVersion
import com.neojou.mystudy.wiki.settings.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

data class SearxngHit(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String,
)

sealed class SearxngStatus {
    data object Unknown : SearxngStatus()
    data object Testing : SearxngStatus()
    data class Ok(val count: Int, val millis: Long) : SearxngStatus()
    data class Failed(val reason: String) : SearxngStatus()
}

class SearxngException(message: String) : RuntimeException(message)

/**
 * Loopback SearXNG. The request sends the query string and nothing from the vault.
 */
class SearxngClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(10),
    private val userAgent: String = "mystudy/${AppVersion.NAME}",
) {
    private val origin: String = try {
        AppSettings.normalizeSearxngUrl(baseUrl)
    } catch (error: IllegalArgumentException) {
        throw SearxngException(error.message ?: "SearXNG URL is invalid.")
    }

    fun testConnection(): SearxngStatus.Ok {
        val started = System.nanoTime()
        val results = fetch("test")
        val millis = (System.nanoTime() - started) / 1_000_000
        return SearxngStatus.Ok(results.size, millis)
    }

    fun search(query: String): List<SearxngHit> =
        fetch(query).mapNotNull { hit ->
            if (normalizeHttpUrl(hit.url) == null) null else hit
        }.take(PER_QUERY_CAP)

    fun searchAll(queries: List<String>): List<SearxngHit> {
        var merged = emptyList<SearxngHit>()
        for (query in queries) {
            if (merged.size >= TOTAL_CAP) break
            merged = mergeSearxngHits(merged, search(query), TOTAL_CAP)
        }
        return merged
    }

    private fun fetch(query: String): List<SearxngHit> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create(
            "$origin/search?q=$encoded&format=json&categories=general&language=auto",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw explain(error)
        }
        val code = response.statusCode()
        if (code == 403) {
            throw SearxngException("SearXNG HTTP 403. JSON format is not enabled. Enable json in search.formats.")
        }
        if (code != 200) throw SearxngException("SearXNG HTTP $code.")
        return parseResults(response.body())
    }
}

const val PER_QUERY_CAP: Int = 5
const val TOTAL_CAP: Int = 15

fun mergeSearxngHits(existing: List<SearxngHit>, incoming: List<SearxngHit>, cap: Int = TOTAL_CAP): List<SearxngHit> {
    val seen = existing.mapNotNull { normalizeHttpUrl(it.url) }.toMutableSet()
    val merged = existing.toMutableList()
    for (hit in incoming) {
        if (merged.size >= cap) break
        val key = normalizeHttpUrl(hit.url) ?: continue
        if (!seen.add(key)) continue
        merged += hit
    }
    return merged
}

/** Trim, drop the fragment, lowercase the host, keep the query. Non-http(s) returns null. */
fun normalizeHttpUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.rawPath ?: ""
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    return "$scheme://$host$port$path$query"
}

fun formatSearxngStatus(status: SearxngStatus): String = when (status) {
    SearxngStatus.Unknown -> "尚未測試"
    SearxngStatus.Testing -> "測試中…"
    is SearxngStatus.Ok -> "Ok (${status.count} results, ${status.millis} ms)"
    is SearxngStatus.Failed -> status.reason
}

private fun parseResults(body: String): List<SearxngHit> {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: Exception) {
        throw SearxngException("SearXNG did not return JSON.")
    }
    val array = (root as? JsonObject)?.get("results") as? JsonArray
        ?: throw SearxngException("SearXNG did not return JSON.")
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        SearxngHit(
            title = obj.string("title"),
            url = obj.string("url"),
            snippet = obj.string("content"),
            engine = obj.string("engine"),
        )
    }
}

private fun JsonObject.string(key: String): String {
    val value = this[key] as? JsonPrimitive ?: return ""
    return value.contentOrNull.orEmpty()
}

private fun explain(error: Exception): SearxngException {
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    if (chain.any { it is HttpTimeoutException } || text.contains("timed out", ignoreCase = true)) {
        return SearxngException("SearXNG timed out.")
    }
    if (chain.any { it is java.net.ConnectException } || text.contains("Connection refused", ignoreCase = true)) {
        return SearxngException("SearXNG is not reachable.")
    }
    return SearxngException(error.message?.takeIf { it.isNotBlank() } ?: "SearXNG is not reachable.")
}
