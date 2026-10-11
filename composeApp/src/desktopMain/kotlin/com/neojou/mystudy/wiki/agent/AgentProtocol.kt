package com.neojou.mystudy.wiki.agent

import com.neojou.mystudy.wiki.ollama.stripJsonFence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class AgentAction {
    data class Search(val query: String) : AgentAction()
    data class ReadPage(val path: String) : AgentAction()
    data class Final(val answer: String) : AgentAction()
    data class Invalid(val reason: String, val raw: String) : AgentAction()
}

private val agentJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * One JSON object from the model. Field names and tool names are the parse contract.
 */
fun parseAgentAction(raw: String): AgentAction {
    val objectText = firstBalancedObject(stripJsonFence(raw))
        ?: return AgentAction.Invalid("no JSON object", raw)
    val obj = try {
        agentJson.parseToJsonElement(objectText).jsonObject
    } catch (error: Exception) {
        return AgentAction.Invalid(error.message ?: "unreadable JSON", raw)
    }
    val action = obj.text("action")?.trim()?.lowercase()
    val tool = obj.text("tool")?.trim()
    val answer = obj.text("answer")
    return when (action) {
        "final" -> finalOrInvalid(answer, raw)
        "tool" -> parseTool(tool, obj, raw)
        null -> when {
            !answer.isNullOrBlank() -> AgentAction.Final(answer)
            answer != null -> AgentAction.Invalid("empty answer", raw)
            !tool.isNullOrBlank() -> parseTool(tool, obj, raw)
            else -> AgentAction.Invalid("missing action", raw)
        }
        else -> AgentAction.Invalid("unknown action", raw)
    }
}

private fun finalOrInvalid(answer: String?, raw: String): AgentAction {
    if (answer.isNullOrBlank()) return AgentAction.Invalid("empty answer", raw)
    return AgentAction.Final(answer)
}

private fun parseTool(tool: String?, obj: JsonObject, raw: String): AgentAction {
    val name = tool?.trim()?.lowercase().orEmpty()
    return when (name) {
        "wiki.search", "wiki_search", "search" -> {
            val query = obj.text("query")?.trim().orEmpty()
            if (query.length !in 1..64) AgentAction.Invalid("query length", raw)
            else AgentAction.Search(query)
        }
        "wiki.read_page", "read_page", "read" -> {
            val path = obj.text("path")?.trim()?.replace("`", "").orEmpty()
            if (path.isEmpty()) AgentAction.Invalid("empty path", raw)
            else AgentAction.ReadPage(path)
        }
        "" -> AgentAction.Invalid("missing tool", raw)
        else -> AgentAction.Invalid("unknown tool", raw)
    }
}

private fun JsonObject.text(name: String): String? {
    val element = this[name] ?: return null
    return try {
        element.jsonPrimitive.content
    } catch (_: Exception) {
        null
    }
}

internal fun firstBalancedObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (index in start until text.length) {
        val ch = text[index]
        if (inString) {
            when {
                escape -> escape = false
                ch == '\\' -> escape = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}
