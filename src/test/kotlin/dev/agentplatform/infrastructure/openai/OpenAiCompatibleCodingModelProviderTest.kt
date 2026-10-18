package dev.agentplatform.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.port.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenAiCompatibleCodingModelProviderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `sends chat messages and tool schemas and maps a tool response`() {
        val receivedBody = AtomicReference<String>()
        withServer(
            200,
            """{"choices":[{"message":{"role":"assistant","content":"Inspecting files","tool_calls":[{"id":"call-1","type":"function","function":{"name":"list_files","arguments":"{\"path\":\".\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":18,"completion_tokens":7}}""",
            onRequest = { body, _ -> receivedBody.set(body) }
        ) { provider, _ ->
            val schema = mapper.readTree("""{"type":"object","properties":{"path":{"type":"string"}}}""")
            val turn = provider.chat(
                listOf(ModelMessage("user", "Inspect the repository")),
                listOf(ModelToolDefinition("list_files", "List files", schema))
            )

            val request = mapper.readTree(receivedBody.get())
            assertEquals("gpt-4o-mini", request.path("model").asText())
            assertFalse(request.path("stream").asBoolean())
            assertEquals("user", request.path("messages")[0].path("role").asText())
            assertEquals("Inspect the repository", request.path("messages")[0].path("content").asText())
            assertEquals(schema, request.path("tools")[0].path("function").path("parameters"))
            assertEquals("tool_calls", turn.finishReason)
            assertEquals(18, turn.promptTokens)
            assertEquals(7, turn.completionTokens)
            assertEquals("call-1", turn.toolCalls.single().id)
            assertEquals("list_files", turn.toolCalls.single().name)
            assertEquals(".", turn.toolCalls.single().arguments.path("path").asText())
        }
    }

    @Test
    fun `handles a text-only response and omitted optional fields`() = withServer(
        200,
        """{"choices":[{"message":{"role":"assistant","content":"Ready"}}]}"""
    ) { provider, _ ->
        val turn = provider.chat(listOf(ModelMessage("user", "Begin")), emptyList())
        assertEquals("Ready", turn.content)
        assertEquals(emptyList<ModelToolCall>(), turn.toolCalls)
        assertNull(turn.finishReason)
        assertNull(turn.promptTokens)
        assertNull(turn.completionTokens)
    }

    @Test
    fun `serializes prior assistant tool calls and tool results with their call ids`() {
        val receivedBody = AtomicReference<String>()
        withServer(200, """{"choices":[{"message":{"content":"done"}}]}""", onRequest = { body, _ -> receivedBody.set(body) }) { provider, _ ->
            val arguments = mapper.readTree("""{"path":"README.md"}""")
            provider.chat(
                listOf(
                    ModelMessage("user", "Inspect"),
                    ModelMessage("assistant", "", listOf(ModelToolCall("call-1", "read_file", arguments))),
                    ModelMessage("tool", "README contents", toolName = "read_file", toolCallId = "call-1")
                ),
                listOf(readFileTool())
            )
            val messages = mapper.readTree(receivedBody.get()).path("messages")
            assertEquals("assistant", messages[1].path("role").asText())
            assertTrue(messages[1].path("content").isNull)
            assertEquals("call-1", messages[1].path("tool_calls")[0].path("id").asText())
            assertEquals("function", messages[1].path("tool_calls")[0].path("type").asText())
            assertEquals("read_file", messages[1].path("tool_calls")[0].path("function").path("name").asText())
            assertEquals(
                """{"path":"README.md"}""",
                messages[1].path("tool_calls")[0].path("function").path("arguments").asText()
            )
            assertEquals("tool", messages[2].path("role").asText())
            assertEquals("call-1", messages[2].path("tool_call_id").asText())
            assertEquals("README contents", messages[2].path("content").asText())
        }
    }

    @Test
    fun `accepts a tool call without a provider supplied id`() = withServer(
        200,
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"inspect","arguments":"{}"}}]}}]}"""
    ) { provider, _ ->
        val call = provider.chat(listOf(ModelMessage("user", "Inspect")), emptyList()).toolCalls.single()
        assertTrue(call.id.isNotBlank())
        assertEquals("inspect", call.name)
        assertTrue(call.arguments.isObject)
    }

    @Test
    fun `treats a blank argument string and an object argument body as objects`() = withServer(
        200,
        """{"choices":[{"message":{"tool_calls":[{"id":"empty","function":{"name":"run_validation","arguments":""}},{"id":"object","function":{"name":"read_file","arguments":{"path":"README.md"}}}]}}]}"""
    ) { provider, _ ->
        val calls = provider.chat(listOf(ModelMessage("user", "Validate")), emptyList()).toolCalls
        assertTrue(calls[0].arguments.isObject)
        assertTrue(calls[0].arguments.isEmpty)
        assertEquals("README.md", calls[1].arguments.path("path").asText())
    }

    @Test
    fun `normalizes JSON content as a tool call only when the tool was offered`() = withServer(
        200,
        """{"choices":[{"message":{"content":"{\"name\":\"read_file\",\"arguments\":{\"path\":\"README.md\"}}"}}]}"""
    ) { provider, _ ->
        val turn = provider.chat(listOf(ModelMessage("user", "Read the README")), listOf(readFileTool()))
        assertNull(turn.content)
        assertEquals("read_file", turn.toolCalls.single().name)
        assertEquals("README.md", turn.toolCalls.single().arguments.path("path").asText())
    }

    @Test
    fun `converts a fenced complete git diff into the offered patch tool call`() {
        val diff = """diff --git a/Greeting.java b/Greeting.java
--- a/Greeting.java
+++ b/Greeting.java
@@ -1 +1 @@
-Hello
+Hello from a hosted model"""
        val content = "The requested update is:\n```diff\n$diff\n```\nI will now validate it."
        val response = """{"choices":[{"message":{"content":${mapper.writeValueAsString(content)}}}]}"""
        withServer(200, response) { provider, _ ->
            val turn = provider.chat(
                listOf(ModelMessage("user", "Update greeting")),
                listOf(ModelToolDefinition("apply_patch", "Apply diff", mapper.createObjectNode()))
            )
            assertNull(turn.content)
            assertEquals("apply_patch", turn.toolCalls.single().name)
            assertEquals(diff, turn.toolCalls.single().arguments.path("patch").asText())
        }
    }

    @Test
    fun `keeps unmatched or malformed content as ordinary model output`() {
        val offeredTools = listOf(readFileTool())
        val responses = listOf("", "not json", "{\"name\":\"unknown_tool\",\"arguments\":{}}", "{\"name\":\"read_file\"}")
        responses.forEach { content ->
            val escapedContent = mapper.writeValueAsString(content)
            withServer(200, """{"choices":[{"message":{"content":$escapedContent}}]}""") { provider, _ ->
                val turn = provider.chat(listOf(ModelMessage("user", "Read the README")), offeredTools)
                assertEquals(content, turn.content)
                assertTrue(turn.toolCalls.isEmpty())
            }
        }
    }

    @Test
    fun `requires a configured base url and model before any request`() {
        val missingUrl = provider("disabled")
        assertTrue(assertThrows(ModelProviderException::class.java) {
            missingUrl.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("agent.model.openai.base-url is not configured"))

        val missingModel = provider("http://localhost:1", model = "disabled")
        assertTrue(assertThrows(ModelProviderException::class.java) {
            missingModel.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("agent.model.openai.model is not configured"))
    }

    @Test
    fun `rejects empty message input and invalid endpoint settings`() {
        val provider = provider("http://localhost:8000/v1")
        assertThrows(IllegalArgumentException::class.java) { provider.chat(emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { provider(" ") }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", model = " ") }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", timeoutSeconds = 0) }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", retryMaxAttempts = 0) }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", retryInitialBackoffMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            provider("http://localhost", retryInitialBackoffMillis = 100, retryMaxBackoffMillis = 50)
        }
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("not a url").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("base URL is invalid"))
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("file:///tmp/openai").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("absolute HTTP or HTTPS URL"))
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("https://127.0.0.1:1").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("Could not reach the OpenAI-compatible server"))
    }

    @Test
    fun `sends the bearer token only when an api key is configured`() {
        val response = """{"choices":[{"message":{"content":"ok"}}]}"""
        withServer(200, response, apiKey = "disabled", onRequest = { _, headers -> assertNull(headers.getFirst("Authorization")) }) { provider, _ ->
            provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        withServer(200, response, apiKey = "sk-secret", onRequest = { _, headers ->
            assertEquals("Bearer sk-secret", headers.getFirst("Authorization"))
        }) { provider, _ ->
            provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
    }

    @Test
    fun `reports bounded error detail for unsuccessful HTTP responses`() = withServer(503, "x".repeat(1500)) { provider, _ ->
        val exception = assertThrows(ModelProviderException::class.java) {
            provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        assertTrue(exception.message!!.startsWith("OpenAI-compatible provider returned HTTP 503:"))
        assertTrue(exception.message!!.length < 1100)
    }

    @Test
    fun `retries a transient rate limit and returns the following successful response`() =
        withSequenceServer(
            listOf(
                429 to """{"error":{"message":"rate limit reached"}}""",
                200 to """{"choices":[{"message":{"content":"ok"}}]}"""
            )
        ) { provider, requests ->
            val turn = provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            assertEquals("ok", turn.content)
            assertEquals(2, requests.get())
        }

    @Test
    fun `stops retrying once attempts are exhausted and reports the provider error`() =
        withSequenceServer(listOf(429 to """{"error":{"message":"slow down"}}""")) { provider, requests ->
            val exception = assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }
            assertTrue(exception.message!!.contains("HTTP 429"))
            assertTrue(exception.message!!.contains("slow down"))
            assertEquals(3, requests.get())
        }

    @Test
    fun `recovers from a transient server error`() = withSequenceServer(
        listOf(
            503 to "service unavailable",
            502 to "bad gateway",
            200 to """{"choices":[{"message":{"content":"recovered"}}]}"""
        )
    ) { provider, requests ->
        assertEquals("recovered", provider.chat(listOf(ModelMessage("user", "hello")), emptyList()).content)
        assertEquals(3, requests.get())
    }

    @Test
    fun `does not retry a non-transient client error`() =
        withSequenceServer(listOf(400 to """{"error":{"message":"bad request"}}""")) { provider, requests ->
            val exception = assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }
            assertTrue(exception.message!!.contains("HTTP 400"))
            assertEquals(1, requests.get())
        }

    @Test
    fun `waits for the Retry-After delay before retrying`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val attempt = requests.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "1")
            val status = if (attempt == 1) 429 else 200
            val body = if (attempt == 1) {
                """{"error":{"message":"slow down"}}"""
            } else {
                """{"choices":[{"message":{"content":"ok"}}]}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val patient = provider("http://127.0.0.1:${server.address.port}", retryMaxBackoffMillis = 5000)
            val startedAt = System.nanoTime()
            val turn = patient.chat(listOf(ModelMessage("user", "hello")), emptyList())
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals("ok", turn.content)
            assertEquals(2, requests.get())
            assertTrue(elapsedMillis >= 900, "expected a Retry-After wait, took ${elapsedMillis}ms")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `classifies only rate limits and server errors as retryable`() {
        val provider = provider("http://localhost:8000/v1")
        assertTrue(provider.isRetryableStatusCode(429))
        assertTrue(provider.isRetryableStatusCode(500))
        assertTrue(provider.isRetryableStatusCode(503))
        assertFalse(provider.isRetryableStatusCode(400))
        assertFalse(provider.isRetryableStatusCode(401))
        assertFalse(provider.isRetryableStatusCode(404))
        assertFalse(provider.isRetryableStatusCode(200))
    }

    @Test
    fun `classifies only successful HTTP response status codes as accepted`() {
        val provider = provider("http://localhost:8000/v1")
        assertTrue(provider.isSuccessfulStatusCode(200))
        assertTrue(provider.isSuccessfulStatusCode(299))
        assertFalse(provider.isSuccessfulStatusCode(199))
        assertFalse(provider.isSuccessfulStatusCode(300))
    }

    @Test
    fun `rejects malformed empty and structurally invalid responses`() {
        withServer(200, "not json") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("invalid JSON"))
        }
        withServer(204, "") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("empty response"))
        }
        withServer(200, "{}") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("did not contain a choice"))
        }
        withServer(200, """{"choices":[{"finish_reason":"stop"}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("did not contain a message"))
        }
        withServer(200, """{"choices":[{"message":{"tool_calls":"invalid"}}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("tool_calls in an invalid format"))
        }
    }

    @Test
    fun `surfaces a 200 response that carries an error object`() = withServer(
        200,
        """{"error":{"message":"invalid api key"}}"""
    ) { provider, _ ->
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("returned an error: invalid api key"))
    }

    @Test
    fun `rejects malformed tool calls`() {
        withServer(200, """{"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{}"}}]}}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without a function name"))
        }
        withServer(200, """{"choices":[{"message":{"tool_calls":[{"function":{"name":"read_file"}}]}}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without arguments"))
        }
        withServer(200, """{"choices":[{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":"not-json"}}]}}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("invalid JSON arguments"))
        }
        withServer(200, """{"choices":[{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":"[]"}}]}}]}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without object arguments"))
        }
    }

    @Test
    fun `preserves thread interrupt status when the request is interrupted`() = withServer(200, """{"choices":[{"message":{"content":"ok"}}]}""") { provider, _ ->
        val failure = AtomicReference<Throwable?>()
        val interruptedAfterFailure = AtomicBoolean(false)
        val thread = Thread {
            Thread.currentThread().interrupt()
            try {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            } catch (exception: Throwable) {
                failure.set(exception)
                interruptedAfterFailure.set(Thread.currentThread().isInterrupted)
            }
        }
        thread.start()
        thread.join(5000)

        assertFalse(thread.isAlive)
        assertTrue(failure.get() is ModelProviderException)
        assertTrue(interruptedAfterFailure.get())
    }

    @Test
    fun `reports connection failures with the configured endpoint`() {
        val server = startServer(200, "")
        val endpoint = "http://127.0.0.1:${server.address.port}"
        server.stop(0)

        val exception = assertThrows(ModelProviderException::class.java) {
            provider(endpoint).chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        assertTrue(exception.message!!.contains("Could not reach the OpenAI-compatible server at $endpoint"))
        assertNotNull(exception.cause)
    }

    @Test
    fun `exposes the configured model name`() {
        assertEquals("gpt-4o-mini", provider("http://localhost:8000/v1").modelName)
    }

    private fun provider(
        baseUrl: String,
        model: String = "gpt-4o-mini",
        apiKey: String = "disabled",
        timeoutSeconds: Long = 5,
        retryMaxAttempts: Int = 3,
        retryInitialBackoffMillis: Long = 1,
        retryMaxBackoffMillis: Long = 5
    ) = OpenAiCompatibleCodingModelProvider(
        baseUrl,
        model,
        apiKey,
        timeoutSeconds,
        retryMaxAttempts,
        retryInitialBackoffMillis,
        retryMaxBackoffMillis,
        mapper
    )

    private fun readFileTool() = ModelToolDefinition(
        "read_file",
        "Read a file within the repository.",
        mapper.readTree("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
    )

    private fun withServer(
        status: Int,
        body: String,
        apiKey: String = "disabled",
        onRequest: ((String, Headers) -> Unit)? = null,
        test: (OpenAiCompatibleCodingModelProvider, HttpServer) -> Unit
    ) {
        val server = startServer(status, body, onRequest)
        try {
            test(provider("http://127.0.0.1:${server.address.port}", apiKey = apiKey), server)
        } finally {
            server.stop(0)
        }
    }

    private fun withSequenceServer(
        responses: List<Pair<Int, String>>,
        test: (OpenAiCompatibleCodingModelProvider, AtomicInteger) -> Unit
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val index = requests.getAndIncrement()
            val (status, body) = responses[index.coerceAtMost(responses.size - 1)]
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test(provider("http://127.0.0.1:${server.address.port}"), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun startServer(status: Int, body: String, onRequest: ((String, Headers) -> Unit)? = null): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            onRequest?.invoke(requestBody, exchange.requestHeaders)
            val bytes = body.toByteArray()
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server
    }
}
