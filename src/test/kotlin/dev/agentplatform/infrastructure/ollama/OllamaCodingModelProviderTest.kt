package dev.agentplatform.infrastructure.ollama

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.port.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OllamaCodingModelProviderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `sends chat and tool schemas and maps tool response`() {
        val receivedBody = AtomicReference<String>()
        withServer(
            200,
            """{"message":{"content":"Inspecting files","tool_calls":[{"id":"tool-1","function":{"name":"list_files","arguments":{"path":"."}}}]},"done_reason":"stop","prompt_eval_count":18,"eval_count":7}""",
            onRequest = { receivedBody.set(it) }
        ) { provider, _ ->
        val schema = mapper.readTree("""{"type":"object","properties":{"path":{"type":"string"}}}""")
        val turn = provider.chat(
            listOf(ModelMessage("user", "Inspect the repository")),
            listOf(ModelToolDefinition("list_files", "List files", schema))
        )

        val request = mapper.readTree(receivedBody.get())
        assertEquals("qwen2.5-coder:7b", request.path("model").asText())
        assertFalse(request.path("stream").asBoolean())
        assertEquals("user", request.path("messages")[0].path("role").asText())
        assertEquals("Inspect the repository", request.path("messages")[0].path("content").asText())
        assertEquals(schema, request.path("tools")[0].path("function").path("parameters"))
        assertEquals("Inspecting files", turn.content)
        assertEquals("stop", turn.finishReason)
        assertEquals(18, turn.promptTokens)
        assertEquals(7, turn.completionTokens)
        assertEquals(1, turn.toolCalls.size)
        assertEquals("tool-1", turn.toolCalls.single().id)
        assertEquals("list_files", turn.toolCalls.single().name)
        assertEquals(".", turn.toolCalls.single().arguments.path("path").asText())
        }
    }

    @Test
    fun `handles text-only response and omitted optional response fields`() = withServer(
        200,
        """{"message":{"content":"Ready"}}"""
    ) { provider, _ ->
        val turn = provider.chat(listOf(ModelMessage("user", "Begin")), emptyList())
        assertEquals("Ready", turn.content)
        assertEquals(emptyList<ModelToolCall>(), turn.toolCalls)
        assertNull(turn.finishReason)
        assertNull(turn.promptTokens)
        assertNull(turn.completionTokens)
    }

    @Test
    fun `handles empty content and explicit null tool calls`() = withServer(
        200,
        """{"message":{"content":null,"tool_calls":null}}"""
    ) { provider, _ ->
        val turn = provider.chat(listOf(ModelMessage("user", "Begin")), emptyList())
        assertNull(turn.content)
        assertTrue(turn.toolCalls.isEmpty())
    }

    @Test
    fun `serializes prior assistant tool calls and tool results in Ollama format`() {
        val receivedBody = AtomicReference<String>()
        withServer(200, """{"message":{"content":"done"}}""", onRequest = { receivedBody.set(it) }) { provider, _ ->
            val arguments = mapper.readTree("""{"path":"README.md"}""")
            provider.chat(
                listOf(
                    ModelMessage("user", "Inspect"),
                    ModelMessage("assistant", "", listOf(ModelToolCall("call-1", "read_file", arguments))),
                    ModelMessage("tool", "README contents", toolName = "read_file")
                ),
                listOf(readFileTool())
            )
            val messages = mapper.readTree(receivedBody.get()).path("messages")
            assertEquals("assistant", messages[1].path("role").asText())
            assertEquals("read_file", messages[1].path("tool_calls")[0].path("function").path("name").asText())
            assertEquals(arguments, messages[1].path("tool_calls")[0].path("function").path("arguments"))
            assertEquals("tool", messages[2].path("role").asText())
            assertEquals("read_file", messages[2].path("tool_name").asText())
            assertEquals("README contents", messages[2].path("content").asText())
        }
    }

    @Test
    fun `accepts tool call without provider supplied id`() = withServer(
        200,
        """{"message":{"tool_calls":[{"function":{"name":"inspect","arguments":{}}}]}}"""
    ) { provider, _ ->
        val call = provider.chat(listOf(ModelMessage("user", "Inspect")), emptyList()).toolCalls.single()
        assertTrue(call.id.isNotBlank())
        assertEquals("inspect", call.name)
        assertTrue(call.arguments.isObject)
    }

    @Test
    fun `parses tool arguments when Ollama returns them as a JSON string`() = withServer(
        200,
        """{"message":{"tool_calls":[{"function":{"name":"apply_patch","arguments":"{\"patch\":\"diff --git a/A.java b/A.java\\n--- a/A.java\\n+++ b/A.java\\n@@ -1 +1 @@\\n-old\\n+new\\n\"}"}}]}}"""
    ) { provider, _ ->
        val call = provider.chat(
            listOf(ModelMessage("user", "Patch")),
            listOf(ModelToolDefinition("apply_patch", "Apply", mapper.createObjectNode()))
        ).toolCalls.single()
        assertEquals("apply_patch", call.name)
        assertTrue(call.arguments.path("patch").asText().contains("diff --git"))
    }

    @Test
    fun `normalizes JSON content as a tool call only when the tool was offered`() = withServer(
        200,
        """{"message":{"content":"{\"name\":\"read_file\",\"arguments\":{\"path\":\"README.md\"}}"}}"""
    ) { provider, _ ->
        val turn = provider.chat(listOf(ModelMessage("user", "Read the README")), listOf(readFileTool()))
        assertNull(turn.content)
        assertEquals("read_file", turn.toolCalls.single().name)
        assertEquals("README.md", turn.toolCalls.single().arguments.path("path").asText())
    }

    @Test
    fun `extracts an offered tool request embedded in model prose and a code fence`() {
        val patch = """diff --git a/A.java b/A.java
--- a/A.java
+++ b/A.java
@@ -1 +1 @@
-return "old";
+return "new";"""
        val embeddedRequest = mapper.writeValueAsString(mapOf("name" to "apply_patch", "arguments" to mapOf("patch" to patch)))
        val response = mapper.writeValueAsString("I will apply this patch:\n```json\n$embeddedRequest\n```")
        withServer(200, """{"message":{"content":$response}}""") { provider, _ ->
            val turn = provider.chat(
                listOf(ModelMessage("user", "Make a change")),
                listOf(ModelToolDefinition("apply_patch", "Apply a diff", mapper.createObjectNode()))
            )
            assertNull(turn.content)
            assertEquals("apply_patch", turn.toolCalls.single().name)
            assertEquals(patch, turn.toolCalls.single().arguments.path("patch").asText())
        }
    }

    @Test
    fun `does not extract embedded calls for tools that were not offered`() {
        val content = """Plan:
{"name":"apply_patch","arguments":{"patch":"diff"}}"""
        val response = mapper.writeValueAsString(content)
        withServer(200, """{"message":{"content":$response}}""") { provider, _ ->
            val turn = provider.chat(listOf(ModelMessage("user", "Make a change")), listOf(readFileTool()))
            assertEquals(content, turn.content)
            assertTrue(turn.toolCalls.isEmpty())
        }
    }

    @Test
    fun `converts a fenced complete git diff into the offered patch tool call`() {
        val diff = """diff --git a/Greeting.java b/Greeting.java
--- a/Greeting.java
+++ b/Greeting.java
@@ -1 +1 @@
-Hello
+Hello from Qwen"""
        val content = "The requested update is:\n```diff\n$diff\n```\nI will now validate it."
        val response = mapper.writeValueAsString(content)
        withServer(200, """{"message":{"content":$response}}""") { provider, _ ->
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
    fun `does not promote incomplete fenced code to a patch tool call`() = withServer(
        200,
        """{"message":{"content":"```diff\n@@ -1 +1 @@\n-Hello\n+Hi\n```"}}"""
    ) { provider, _ ->
        val turn = provider.chat(
            listOf(ModelMessage("user", "Update greeting")),
            listOf(ModelToolDefinition("apply_patch", "Apply diff", mapper.createObjectNode()))
        )
        assertTrue(turn.toolCalls.isEmpty())
        assertTrue(turn.content!!.contains("@@ -1 +1 @@"))
    }

    @Test
    fun `does not promote fenced diffs missing either file header`() {
        val invalidDiffs = listOf(
            "diff --git a/A.java b/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new",
            "diff --git a/A.java b/A.java\n--- a/A.java\n@@ -1 +1 @@\n-old\n+new"
        )
        invalidDiffs.forEach { diff ->
            val response = mapper.writeValueAsString("```diff\n$diff\n```")
            withServer(200, """{"message":{"content":$response}}""") { provider, _ ->
                val turn = provider.chat(
                    listOf(ModelMessage("user", "Patch")),
                    listOf(ModelToolDefinition("apply_patch", "Apply patch", mapper.createObjectNode()))
                )
                assertTrue(turn.toolCalls.isEmpty())
            }
        }
    }

    @Test
    fun `keeps unmatched or malformed content as ordinary model output`() {
        val offeredTools = listOf(readFileTool())
        val responses = listOf(
            "",
            " ",
            "not json",
            "[]",
            "{\"name\":\"unknown_tool\",\"arguments\":{}}",
            "{\"arguments\":{}}",
            "{\"name\":\"read_file\"}",
            "{\"name\":\"read_file\",\"arguments\":[]}",
            "{\"name\":\"read_file\",\"arguments\":null}",
            "{\"name\":bad}",
            "{\"arguments\":{}} trailing"
        )
        responses.forEach { content ->
            val escapedContent = mapper.writeValueAsString(content)
            withServer(200, """{"message":{"content":$escapedContent}}""") { provider, _ ->
                val turn = provider.chat(listOf(ModelMessage("user", "Read the README")), offeredTools)
                assertEquals(content, turn.content)
                assertTrue(turn.toolCalls.isEmpty())
            }
        }
    }

    @Test
    fun `keeps content as ordinary output when JSON parser returns no tree`() {
        val nullForEmptyInputMapper = object : ObjectMapper() {
            override fun readTree(content: String): JsonNode? =
                if (content.isEmpty()) null else super.readTree(content)
        }
        val server = startServer(200, """{"message":{"content":""}}""")
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val turn = OllamaCodingModelProvider(endpoint, "qwen2.5-coder:7b", 5, nullForEmptyInputMapper)
                .chat(listOf(ModelMessage("user", "Read the README")), listOf(readFileTool()))
            assertEquals("", turn.content)
            assertTrue(turn.toolCalls.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects empty message input and invalid endpoint settings`() {
        val provider = provider("http://localhost:11434")
        assertThrows(IllegalArgumentException::class.java) { provider.chat(emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { provider(" ") }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", model = " ") }
        assertThrows(IllegalArgumentException::class.java) { provider("http://localhost", timeoutSeconds = 0) }
        assertThrows(ModelProviderException::class.java) {
            provider("not a url").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        assertThrows(ModelProviderException::class.java) {
            provider("file:///tmp/ollama").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("http:///ollama").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("absolute HTTP or HTTPS URL"))
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("/ollama").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("absolute HTTP or HTTPS URL"))
        assertTrue(assertThrows(ModelProviderException::class.java) {
            provider("https://127.0.0.1:1").chat(listOf(ModelMessage("user", "hello")), emptyList())
        }.message!!.contains("Could not reach the Ollama server"))
    }

    @Test
    fun `reports bounded error detail for unsuccessful HTTP responses`() = withServer(503, "x".repeat(1500)) { provider, _ ->
        val exception = assertThrows(ModelProviderException::class.java) {
            provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
        }
        assertTrue(exception.message!!.startsWith("Ollama returned HTTP 503:"))
        assertTrue(exception.message!!.length < 1100)
    }

    @Test
    fun `classifies only successful HTTP response status codes as accepted`() {
        val provider = provider("http://localhost:11434")
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
            }.message!!.contains("did not contain a message"))
        }
        withServer(200, """{"message":{"tool_calls":"invalid"}}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("tool_calls in an invalid format"))
        }
    }

    @Test
    fun `rejects malformed tool calls`() {
        withServer(200, """{"message":{"tool_calls":[{"function":{"arguments":{}}}]}}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without a function name"))
        }
        withServer(200, """{"message":{"tool_calls":[{"function":{"name":"read_file"}}]}}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without arguments"))
        }
        withServer(200, """{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":null}}]}}""") { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without arguments"))
        }
        withServer(
            200,
            """{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":"not-json"}}]}}"""
        ) { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("invalid JSON arguments"))
        }
        withServer(
            200,
            """{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":"[]"}}]}}"""
        ) { provider, _ ->
            assertTrue(assertThrows(ModelProviderException::class.java) {
                provider.chat(listOf(ModelMessage("user", "hello")), emptyList())
            }.message!!.contains("without object arguments"))
        }
    }

    @Test
    fun `preserves thread interrupt status when request is interrupted`() = withServer(200, """{"message":{"content":"ok"}}""") { provider, _ ->
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
        assertTrue(exception.message!!.contains("Could not reach the Ollama server at $endpoint"))
        assertNotNull(exception.cause)
    }

    @Test
    fun `model protocol data classes retain value semantics`() {
        val message = ModelMessage("user", "hello")
        assertEquals(message, ModelMessage("user", "hello"))
        assertFalse(message == ModelMessage("assistant", "hello"))
        val schema = mapper.createObjectNode().put("type", "object")
        val definition = ModelToolDefinition("read_file", "Read", schema)
        assertEquals(definition, ModelToolDefinition("read_file", "Read", schema))
        assertFalse(definition == ModelToolDefinition("write_file", "Read", schema))
        val call = ModelToolCall("id", "read_file", schema)
        assertEquals(call, ModelToolCall("id", "read_file", schema))
        assertFalse(call == ModelToolCall("other", "read_file", schema))
        val turn = ModelTurn("ok", listOf(call), "stop", 2, 3)
        assertEquals(turn, ModelTurn("ok", listOf(call), "stop", 2, 3))
        assertFalse(turn == ModelTurn("different", listOf(call), "stop", 2, 3))
        assertTrue(turn.toString().contains("completionTokens=3"))
        assertTrue(ModelProviderException("failed").message!!.contains("failed"))
        assertNotNull(ModelProviderException("failed", IllegalStateException("cause")).cause)
    }

    private fun provider(
        baseUrl: String,
        model: String = "qwen2.5-coder:7b",
        timeoutSeconds: Long = 5
    ) = OllamaCodingModelProvider(baseUrl, model, timeoutSeconds, mapper)

    private fun readFileTool() = ModelToolDefinition(
        "read_file",
        "Read a file within the repository.",
        mapper.readTree("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
    )

    private fun withServer(
        status: Int,
        body: String,
        onRequest: ((String) -> Unit)? = null,
        test: (OllamaCodingModelProvider, HttpServer) -> Unit
    ) {
        val server = startServer(status, body, onRequest)
        try {
            test(provider("http://127.0.0.1:${server.address.port}"), server)
        } finally {
            server.stop(0)
        }
    }

    private fun startServer(status: Int, body: String, onRequest: ((String) -> Unit)? = null): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            onRequest?.invoke(requestBody)
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
