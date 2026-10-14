package dev.agentplatform.infrastructure.ollama

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.port.*
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

@ApplicationScoped
class OllamaCodingModelProvider(
    @ConfigProperty(name = "agent.model.ollama.base-url", defaultValue = "http://localhost:11434")
    private val baseUrl: String,
    @ConfigProperty(name = "agent.model.ollama.model", defaultValue = "qwen3:8b")
    private val model: String,
    @ConfigProperty(name = "agent.model.ollama.timeout-seconds", defaultValue = "1200")
    private val timeoutSeconds: Long,
    private val objectMapper: ObjectMapper
) : CodingModelProvider {
    private val httpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()
    }

    init {
        require(baseUrl.isNotBlank()) { "Ollama base URL must not be blank" }
        require(model.isNotBlank()) { "Ollama model name must not be blank" }
        require(timeoutSeconds > 0) { "Ollama timeout must be greater than zero" }
    }

    override fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn {
        require(messages.isNotEmpty()) { "At least one model message is required" }
        val endpoint = try {
            URI.create("${baseUrl.trimEnd('/')}/api/chat")
        } catch (exception: IllegalArgumentException) {
            throw ModelProviderException("Ollama base URL is invalid", exception)
        }
        val scheme = endpoint.scheme
        val hasSupportedScheme = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        if (!hasSupportedScheme || endpoint.host == null) {
            throw ModelProviderException("Ollama base URL must be an absolute HTTP or HTTPS URL")
        }

        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(serializeRequest(messages, tools)))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ModelProviderException("Ollama request was interrupted", exception)
        } catch (exception: IOException) {
            throw ModelProviderException("Could not reach the Ollama server at $baseUrl", exception)
        }

        if (!isSuccessfulStatusCode(response.statusCode())) {
            val detail = response.body().take(MAX_ERROR_LENGTH)
            throw ModelProviderException("Ollama returned HTTP ${response.statusCode()}: $detail")
        }

        if (response.body().isBlank()) throw ModelProviderException("Ollama returned an empty response")
        val root = try {
            objectMapper.readTree(response.body())
        } catch (exception: IOException) {
            throw ModelProviderException("Ollama returned invalid JSON", exception)
        }

        val message = root.path("message")
        if (!message.isObject) throw ModelProviderException("Ollama response did not contain a message")
        val content = message.path("content").takeIf(JsonNode::isTextual)?.asText()
        val toolCallsNode = message.path("tool_calls")
        if (!toolCallsNode.isMissingNode && !toolCallsNode.isNull && !toolCallsNode.isArray) {
            throw ModelProviderException("Ollama returned tool_calls in an invalid format")
        }
        val toolCalls = toolCallsNode.takeIf(JsonNode::isArray)?.map(::parseToolCall).orEmpty()
        val contentToolCall = if (toolCalls.isEmpty()) parseContentToolCall(content, tools) else null

        return ModelTurn(
            content = content.takeIf { contentToolCall == null },
            toolCalls = if (contentToolCall == null) toolCalls else listOf(contentToolCall),
            finishReason = root.path("done_reason").takeIf(JsonNode::isTextual)?.asText(),
            promptTokens = root.path("prompt_eval_count").takeIf(JsonNode::canConvertToInt)?.asInt(),
            completionTokens = root.path("eval_count").takeIf(JsonNode::canConvertToInt)?.asInt()
        )
    }

    internal fun isSuccessfulStatusCode(statusCode: Int): Boolean = statusCode in 200..299

    private fun serializeRequest(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): String {
        val request = objectMapper.createObjectNode()
        request.put("model", model)
        request.put("stream", false)
        val messagesNode = request.putArray("messages")
        messages.forEach { message ->
            val messageNode = messagesNode.addObject()
            messageNode.put("role", message.role)
            messageNode.put("content", message.content)
            message.toolName?.let { messageNode.put("tool_name", it) }
            if (message.toolCalls.isNotEmpty()) {
                val callsNode = messageNode.putArray("tool_calls")
                message.toolCalls.forEachIndexed { index, call ->
                    val function = callsNode.addObject().put("type", "function").putObject("function")
                    function.put("index", index)
                    function.put("name", call.name)
                    function.set<JsonNode>("arguments", call.arguments)
                }
            }
        }
        if (tools.isNotEmpty()) {
            val toolsNode = request.putArray("tools")
            tools.forEach { tool ->
                val functionNode = toolsNode.addObject().put("type", "function").putObject("function")
                functionNode.put("name", tool.name)
                functionNode.put("description", tool.description)
                functionNode.set<JsonNode>("parameters", tool.parameters)
            }
        }
        return objectMapper.writeValueAsString(request)
    }

    private fun parseToolCall(call: JsonNode): ModelToolCall {
        val function = call.path("function")
        val name = function.path("name").takeIf(JsonNode::isTextual)?.asText()
            ?: throw ModelProviderException("Ollama returned a tool call without a function name")
        val arguments = normalizeToolArguments(function.path("arguments"), name)
        return ModelToolCall(
            id = call.path("id").takeIf(JsonNode::isTextual)?.asText() ?: UUID.randomUUID().toString(),
            name = name,
            arguments = arguments
        )
    }

    private fun normalizeToolArguments(arguments: JsonNode, toolName: String): JsonNode {
        if (arguments.isMissingNode || arguments.isNull) {
            throw ModelProviderException("Ollama returned tool '$toolName' without arguments")
        }
        if (arguments.isObject) return arguments
        if (arguments.isTextual) {
            val parsed = try {
                objectMapper.readTree(arguments.asText())
            } catch (_: IOException) {
                throw ModelProviderException("Ollama returned tool '$toolName' with invalid JSON arguments")
            }
            if (parsed != null && parsed.isObject) return parsed
        }
        throw ModelProviderException("Ollama returned tool '$toolName' without object arguments")
    }

    private fun parseContentToolCall(content: String?, tools: List<ModelToolDefinition>): ModelToolCall? {
        if (content == null || tools.isEmpty()) return null
        if (tools.any { it.name == "apply_patch" }) {
            val diff = extractFencedGitDiff(content)
            if (diff != null) {
                val arguments = objectMapper.createObjectNode().put("patch", diff)
                return ModelToolCall(UUID.randomUUID().toString(), "apply_patch", arguments)
            }
        }
        val directCandidate = try { objectMapper.readTree(content) } catch (_: IOException) { null }
        val candidates = sequenceOf(directCandidate).plus(embeddedJsonCandidates(content))
        for (candidate in candidates) {
            if (candidate == null || !candidate.isObject) continue
            val name = candidate.path("name").takeIf(JsonNode::isTextual)?.asText() ?: continue
            if (tools.none { it.name == name }) continue
            val arguments = candidate.path("arguments")
            if (!arguments.isObject) continue
            return ModelToolCall(UUID.randomUUID().toString(), name, arguments)
        }
        return null
    }

    private fun extractFencedGitDiff(content: String): String? {
        val match = FENCED_DIFF.find(content) ?: return null
        val diff = match.groupValues[1].trimEnd()
        if (!diff.startsWith("diff --git ")) return null
        if (diff.lineSequence().none { it.startsWith("--- ") } || diff.lineSequence().none { it.startsWith("+++ ") }) return null
        return diff
    }

    private fun embeddedJsonCandidates(content: String): Sequence<JsonNode?> = sequence {
        for (start in content.indices) {
            if (content[start] != '{') continue
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start..content.lastIndex) {
                val character = content[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                    continue
                }
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = try {
                                objectMapper.readTree(content.substring(start, index + 1))
                            } catch (_: IOException) {
                                null
                            }
                            yield(candidate)
                            break
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 1000
        val FENCED_DIFF = Regex("```diff\\s*\\r?\\n(.*?)\\r?\\n```", RegexOption.DOT_MATCHES_ALL)
    }
}
