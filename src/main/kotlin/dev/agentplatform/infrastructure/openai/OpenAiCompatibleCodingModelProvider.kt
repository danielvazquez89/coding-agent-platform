package dev.agentplatform.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.port.CodingModelProvider
import dev.agentplatform.domain.port.ExecutionMetrics
import dev.agentplatform.domain.port.ModelMessage
import dev.agentplatform.domain.port.ModelProviderException
import dev.agentplatform.domain.port.ModelToolCall
import dev.agentplatform.domain.port.ModelToolDefinition
import dev.agentplatform.domain.port.ModelTurn
import dev.agentplatform.infrastructure.model.ContentToolCallExtractor
import dev.agentplatform.infrastructure.model.ModelRateLimiter
import dev.agentplatform.infrastructure.model.OpenAiCompatibleModelProvider
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Talks to any server that implements the OpenAI `/chat/completions` contract: OpenAI itself,
 * OpenRouter, Groq, Azure-style gateways, and local runtimes such as vLLM or llama.cpp. The base URL
 * is expected to include the version segment (for example `https://api.openai.com/v1`).
 *
 * The provider is inert until `agent.model.provider=openai-compatible`, so an unconfigured or
 * half-configured endpoint cannot fail a run that is actually using the local Ollama adapter.
 */
@OpenAiCompatibleModelProvider
@ApplicationScoped
class OpenAiCompatibleCodingModelProvider(
    @ConfigProperty(name = "agent.model.openai.base-url", defaultValue = "disabled")
    private val baseUrl: String,
    @ConfigProperty(name = "agent.model.openai.model", defaultValue = "disabled")
    private val model: String,
    @ConfigProperty(name = "agent.model.openai.api-key", defaultValue = "disabled")
    private val apiKey: String,
    @ConfigProperty(name = "agent.model.openai.timeout-seconds", defaultValue = "1200")
    private val timeoutSeconds: Long,
    @ConfigProperty(name = "agent.model.openai.retry.max-attempts", defaultValue = "5")
    private val retryMaxAttempts: Int,
    @ConfigProperty(name = "agent.model.openai.retry.initial-backoff-millis", defaultValue = "1000")
    private val retryInitialBackoffMillis: Long,
    @ConfigProperty(name = "agent.model.openai.retry.max-backoff-millis", defaultValue = "60000")
    private val retryMaxBackoffMillis: Long,
    private val objectMapper: ObjectMapper,
    private val rateLimiter: ModelRateLimiter,
    private val metrics: ExecutionMetrics
) : CodingModelProvider {
    private val logger = Logger.getLogger(OpenAiCompatibleCodingModelProvider::class.java)
    private val httpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()
    }

    override val modelName: String get() = model

    init {
        require(baseUrl.isNotBlank()) { "OpenAI-compatible base URL must not be blank" }
        require(model.isNotBlank()) { "OpenAI-compatible model name must not be blank" }
        require(timeoutSeconds > 0) { "OpenAI-compatible timeout must be greater than zero" }
        require(retryMaxAttempts >= 1) { "OpenAI-compatible retry attempts must be at least one" }
        require(retryInitialBackoffMillis > 0) { "OpenAI-compatible retry backoff must be greater than zero" }
        require(retryMaxBackoffMillis >= retryInitialBackoffMillis) {
            "OpenAI-compatible maximum retry backoff must not be smaller than the initial backoff"
        }
    }

    override fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn = try {
        chatOnce(messages, tools).also { metrics.modelRequest(PROVIDER_NAME, "success") }
    } catch (failure: ModelProviderException) {
        metrics.modelRequest(PROVIDER_NAME, "failure")
        throw failure
    }

    private fun chatOnce(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn {
        require(messages.isNotEmpty()) { "At least one model message is required" }
        if (!isConfigured(baseUrl)) {
            throw ModelProviderException(
                "The OpenAI-compatible provider is selected but agent.model.openai.base-url is not configured"
            )
        }
        if (!isConfigured(model)) {
            throw ModelProviderException(
                "The OpenAI-compatible provider is selected but agent.model.openai.model is not configured"
            )
        }
        val endpoint = try {
            URI.create("${baseUrl.trimEnd('/')}/chat/completions")
        } catch (exception: IllegalArgumentException) {
            throw ModelProviderException("OpenAI-compatible base URL is invalid", exception)
        }
        val scheme = endpoint.scheme
        val hasSupportedScheme = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        if (!hasSupportedScheme || endpoint.host == null) {
            throw ModelProviderException("OpenAI-compatible base URL must be an absolute HTTP or HTTPS URL")
        }

        val payload = serializeRequest(messages, tools)
        val waited = rateLimiter.acquire(ModelRateLimiter.estimateTokens(payload.length))
        if (waited > 0) metrics.rateLimitWait(PROVIDER_NAME, waited)
        val response = sendWithRetries(endpoint, payload)

        if (!isSuccessfulStatusCode(response.statusCode())) {
            val detail = response.body().take(MAX_ERROR_LENGTH)
            throw ModelProviderException("OpenAI-compatible provider returned HTTP ${response.statusCode()}: $detail")
        }
        if (response.body().isBlank()) throw ModelProviderException("OpenAI-compatible provider returned an empty response")
        val root = try {
            objectMapper.readTree(response.body())
        } catch (exception: IOException) {
            throw ModelProviderException("OpenAI-compatible provider returned invalid JSON", exception)
        }
        if (root.path("error").isObject) {
            val detail = root.path("error").path("message").asText("").take(MAX_ERROR_LENGTH)
            throw ModelProviderException("OpenAI-compatible provider returned an error: $detail")
        }

        val choice = root.path("choices").firstOrNull()
            ?: throw ModelProviderException("OpenAI-compatible response did not contain a choice")
        val message = choice.path("message")
        if (!message.isObject) throw ModelProviderException("OpenAI-compatible response did not contain a message")
        val content = message.path("content").takeIf(JsonNode::isTextual)?.asText()
        val toolCallsNode = message.path("tool_calls")
        if (!toolCallsNode.isMissingNode && !toolCallsNode.isNull && !toolCallsNode.isArray) {
            throw ModelProviderException("OpenAI-compatible provider returned tool_calls in an invalid format")
        }
        val toolCalls = toolCallsNode.takeIf(JsonNode::isArray)?.map(::parseToolCall).orEmpty()
        val contentToolCall = if (toolCalls.isEmpty()) {
            ContentToolCallExtractor.extract(content, tools, objectMapper)
        } else {
            null
        }
        val usage = root.path("usage")

        return ModelTurn(
            content = content.takeIf { contentToolCall == null },
            toolCalls = if (contentToolCall == null) toolCalls else listOf(contentToolCall),
            finishReason = choice.path("finish_reason").takeIf(JsonNode::isTextual)?.asText(),
            promptTokens = usage.path("prompt_tokens").takeIf(JsonNode::canConvertToInt)?.asInt(),
            completionTokens = usage.path("completion_tokens").takeIf(JsonNode::canConvertToInt)?.asInt()
        )
    }

    /**
     * Sends the request, retrying transient provider responses (HTTP 429 and 5xx) with exponential
     * backoff and jitter. Hosted free tiers throttle per-minute token budgets, and a bounded wait is
     * preferable to failing an attempt outright. Non-transient responses and the final attempt are
     * returned as-is so the caller keeps reporting the provider's own error detail.
     */
    private fun sendWithRetries(endpoint: URI, payload: String): HttpResponse<String> {
        var attempt = 1
        while (true) {
            val response = try {
                httpClient.send(buildRequest(endpoint, payload), HttpResponse.BodyHandlers.ofString())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ModelProviderException("OpenAI-compatible request was interrupted", exception)
            } catch (exception: IOException) {
                throw ModelProviderException("Could not reach the OpenAI-compatible server at $baseUrl", exception)
            }
            if (!isRetryableStatusCode(response.statusCode()) || attempt >= retryMaxAttempts) {
                return response
            }
            metrics.modelRetry(
                PROVIDER_NAME,
                if (response.statusCode() == TOO_MANY_REQUESTS) "rate_limited" else "server_error"
            )
            val delayMillis = retryDelayMillis(response, attempt)
            logger.warn(
                "OpenAI-compatible provider returned HTTP ${response.statusCode()}; " +
                    "retrying in ${delayMillis}ms (attempt ${attempt + 1} of $retryMaxAttempts)"
            )
            sleepBeforeRetry(delayMillis)
            attempt++
        }
    }

    private fun buildRequest(endpoint: URI, payload: String): HttpRequest {
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
        if (isConfigured(apiKey)) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        return builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build()
    }

    /**
     * Honors a numeric `Retry-After` header when the provider sends one, otherwise backs off
     * exponentially with jitter so several attempts do not retry in lock-step.
     */
    private fun retryDelayMillis(response: HttpResponse<String>, attempt: Int): Long {
        val retryAfterSeconds = response.headers().firstValue("Retry-After").orElse(null)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
        if (retryAfterSeconds != null) {
            val bounded = retryAfterSeconds.coerceAtMost(Long.MAX_VALUE / 1000L)
            return (bounded * 1000L).coerceAtMost(retryMaxBackoffMillis)
        }
        val exponent = (attempt - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val ceiling = (retryInitialBackoffMillis shl exponent).coerceAtMost(retryMaxBackoffMillis)
        val floor = ceiling / 2
        return floor + ThreadLocalRandom.current().nextLong(ceiling - floor + 1)
    }

    private fun sleepBeforeRetry(delayMillis: Long) {
        try {
            Thread.sleep(delayMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ModelProviderException("OpenAI-compatible retry wait was interrupted", exception)
        }
    }

    internal fun isSuccessfulStatusCode(statusCode: Int): Boolean = statusCode in 200..299

    internal fun isRetryableStatusCode(statusCode: Int): Boolean =
        statusCode == TOO_MANY_REQUESTS || statusCode in 500..599

    private fun serializeRequest(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): String {
        val request = objectMapper.createObjectNode()
        request.put("model", model)
        request.put("stream", false)
        val messagesNode = request.putArray("messages")
        messages.forEach { message ->
            val messageNode = messagesNode.addObject()
            messageNode.put("role", message.role)
            when (message.role) {
                "tool" -> {
                    messageNode.put("content", message.content)
                    message.toolCallId?.let { messageNode.put("tool_call_id", it) }
                }
                "assistant" -> {
                    if (message.content.isEmpty()) {
                        messageNode.putNull("content")
                    } else {
                        messageNode.put("content", message.content)
                    }
                    if (message.toolCalls.isNotEmpty()) {
                        val callsNode = messageNode.putArray("tool_calls")
                        message.toolCalls.forEach { call ->
                            val callNode = callsNode.addObject()
                            callNode.put("id", call.id)
                            callNode.put("type", "function")
                            val function = callNode.putObject("function")
                            function.put("name", call.name)
                            function.put("arguments", objectMapper.writeValueAsString(call.arguments))
                        }
                    }
                }
                else -> messageNode.put("content", message.content)
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
            ?: throw ModelProviderException("OpenAI-compatible provider returned a tool call without a function name")
        val arguments = normalizeToolArguments(function.path("arguments"), name)
        return ModelToolCall(
            id = call.path("id").takeIf(JsonNode::isTextual)?.asText() ?: UUID.randomUUID().toString(),
            name = name,
            arguments = arguments
        )
    }

    private fun normalizeToolArguments(arguments: JsonNode, toolName: String): JsonNode {
        if (arguments.isMissingNode || arguments.isNull) {
            throw ModelProviderException("OpenAI-compatible provider returned tool '$toolName' without arguments")
        }
        if (arguments.isObject) return arguments
        if (arguments.isTextual) {
            val raw = arguments.asText()
            // OpenAI sends "" for a tool that takes no arguments; treat it as an empty object.
            if (raw.isBlank()) return objectMapper.createObjectNode()
            val parsed = try {
                objectMapper.readTree(raw)
            } catch (_: IOException) {
                throw ModelProviderException("OpenAI-compatible provider returned tool '$toolName' with invalid JSON arguments")
            }
            if (parsed != null && parsed.isObject) return parsed
        }
        throw ModelProviderException("OpenAI-compatible provider returned tool '$toolName' without object arguments")
    }

    private fun isConfigured(value: String): Boolean =
        value.isNotBlank() && !value.trim().equals(DISABLED_SENTINEL, ignoreCase = true)

    private companion object {
        const val PROVIDER_NAME = "openai-compatible"
        const val MAX_ERROR_LENGTH = 1000
        const val DISABLED_SENTINEL = "disabled"
        const val TOO_MANY_REQUESTS = 429
        const val MAX_BACKOFF_EXPONENT = 6
    }
}
