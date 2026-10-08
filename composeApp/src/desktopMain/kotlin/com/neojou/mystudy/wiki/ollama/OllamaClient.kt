package com.neojou.mystudy.wiki.ollama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ChatRequest(
    val system: String,
    val user: String,
    val json: Boolean,
)

fun interface ChatClient {
    fun complete(request: ChatRequest): String
}

class ChatException(message: String) : RuntimeException(message)

/**
 * Loopback Ollama. Generation uses native `/api/chat` so `num_ctx` is applied.
 * The configurable base URL stays `http://127.0.0.1:11434/v1`; the `/v1` suffix is stripped.
 * `/v1/chat/completions` cannot set context length.
 */
class OllamaClient(
    baseUrl: String,
    private val model: String,
    private val numCtx: Int,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : ChatClient {
    private val origin: String = endpointOrigin(baseUrl)
    private val lock = ReentrantLock()

    override fun complete(request: ChatRequest): String = lock.withLock {
        try {
            post(request, includeThink = true)
        } catch (error: ChatException) {
            if (!error.message.orEmpty().contains("think", ignoreCase = true)) throw error
            post(request, includeThink = false)
        }
    }

    fun ping(): String {
        val request = HttpRequest.newBuilder(URI.create("$origin/api/tags"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ChatException("Ollama HTTP ${response.statusCode()}")
        }
        return "Ollama is reachable."
    }

    private fun post(request: ChatRequest, includeThink: Boolean): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            if (includeThink) put("think", false)
            put("options", buildJsonObject { put("num_ctx", numCtx) })
            if (request.json) put("format", "json")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.user)
                })
            })
        }.toString()
        val httpRequest = HttpRequest.newBuilder(URI.create("$origin/api/chat"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw ChatException(error.message ?: "Ollama request failed")
        }
        if (response.statusCode() !in 200..299) {
            val snippet = response.body().take(500)
            throw ChatException("Ollama HTTP ${response.statusCode()}: $snippet")
        }
        val content = try {
            Json.parseToJsonElement(response.body()).jsonObject["message"]?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
        } catch (error: Exception) {
            throw ChatException("Ollama returned unreadable JSON: ${error.message}")
        }
        if (content.isNullOrBlank()) throw ChatException("Ollama returned an empty answer.")
        return content
    }
}

fun endpointOrigin(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val uri = try {
        URI(trimmed)
    } catch (error: Exception) {
        throw ChatException("Ollama URL is invalid.")
    }
    val host = uri.host ?: throw ChatException("Ollama URL is invalid.")
    if (host != "127.0.0.1" && !host.equals("localhost", ignoreCase = true)) {
        throw ChatException("Ollama host must be loopback.")
    }
    if (uri.scheme != "http" && uri.scheme != "https") throw ChatException("Ollama URL is invalid.")
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://$host$port"
}

object PromptBudget {
    const val MAX_INPUT_CHARS: Int = 40_000
    const val RESERVE_TOKENS: Int = 4096
    const val SCHEMA_CAP: Int = 8_000
    const val PAGE_EXCERPT: Int = 2_000
    const val HOP_EXCERPT: Int = 800
    const val EXISTING_EXCERPT: Int = 1_500

    fun inputCharBudget(numCtx: Int): Int = minOf(MAX_INPUT_CHARS, numCtx - RESERVE_TOKENS).coerceAtLeast(0)

    fun tooSmall(numCtx: Int): Boolean = numCtx <= RESERVE_TOKENS
}

fun stripJsonFence(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
}
