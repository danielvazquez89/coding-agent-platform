package dev.agentplatform.domain.port

import com.fasterxml.jackson.databind.JsonNode

/** Model-provider boundary; orchestration and repository tools must not depend on a vendor API. */
interface CodingModelProvider {
    /** Identifier of the model the active provider calls, reported in execution results. */
    val modelName: String

    fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn
}

data class ModelMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ModelToolCall> = emptyList(),
    val toolName: String? = null,
    /** Correlates a `tool` result with the assistant tool call it answers, required by OpenAI chat. */
    val toolCallId: String? = null
)

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonNode
)

data class ModelToolCall(
    val id: String,
    val name: String,
    val arguments: JsonNode
)

data class ModelTurn(
    val content: String?,
    val toolCalls: List<ModelToolCall>,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?
)

class ModelProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
