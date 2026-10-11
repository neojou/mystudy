package com.neojou.mystudy.wiki.agent

data class ChatTurn(
    val question: String,
    val answer: String,
)

data class ChatReference(
    val number: Int,
    val path: String,
    val title: String,
    val type: String,
    val snippet: String,
    val kind: String,
    val read: Boolean,
    val links: String = "(none)",
)

/**
 * Last few turns, kept in memory for one Chat pane. Not written to the vault.
 */
class ChatSession {
    private val turns = ArrayDeque<ChatTurn>()

    fun recent(): List<ChatTurn> = turns.takeLast(ChatAgent.HISTORY_TURNS)

    fun add(question: String, answer: String) {
        turns.addLast(ChatTurn(question, answer))
        while (turns.size > ChatAgent.HISTORY_TURNS) turns.removeFirst()
    }

    fun clear() {
        turns.clear()
    }
}
