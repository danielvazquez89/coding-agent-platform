package dev.agentplatform.infrastructure.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.port.ModelMessage
import dev.agentplatform.infrastructure.ollama.OllamaCodingModelProvider
import dev.agentplatform.infrastructure.openai.OpenAiCompatibleCodingModelProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class SelectingCodingModelProviderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `defaults to Ollama and reports the Ollama model`() {
        val requestedPath = AtomicReference<String>()
        withServer(requestedPath) { baseUrl ->
            val selector = selector(baseUrl, "ollama")
            assertEquals("ollama-model", selector.modelName)
            assertEquals("ollama reply", selector.chat(listOf(ModelMessage("user", "Hi")), emptyList()).content)
            assertEquals("/api/chat", requestedPath.get())
        }
    }

    @Test
    fun `routes every OpenAI-compatible provider spelling to the OpenAI-compatible adapter`() {
        val requestedPath = AtomicReference<String>()
        withServer(requestedPath) { baseUrl ->
            listOf("openai-compatible", "openai", "OpenAI-Compatible").forEach { providerName ->
                requestedPath.set(null)
                val selector = selector(baseUrl, providerName)
                assertEquals("openai-model", selector.modelName)
                assertEquals("openai reply", selector.chat(listOf(ModelMessage("user", "Hi")), emptyList()).content)
                assertEquals("/chat/completions", requestedPath.get())
            }
        }
    }

    @Test
    fun `rejects an unknown provider name instead of falling back to a default`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            selector("http://localhost:1", "anthropic")
        }
        assertTrue(error.message!!.contains("agent.model.provider"))
        assertTrue(error.message!!.contains("anthropic"))
    }

    private fun selector(baseUrl: String, provider: String) = SelectingCodingModelProvider(
        OllamaCodingModelProvider(baseUrl, "ollama-model", 5, mapper),
        OpenAiCompatibleCodingModelProvider(baseUrl, "openai-model", "disabled", 5, 5, 1000, 60000, mapper),
        provider
    )

    private fun withServer(requestedPath: AtomicReference<String>, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            requestedPath.set("/api/chat")
            respond(exchange, """{"message":{"content":"ollama reply"}}""")
        }
        server.createContext("/chat/completions") { exchange ->
            requestedPath.set("/chat/completions")
            respond(exchange, """{"choices":[{"message":{"content":"openai reply"}}]}""")
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        exchange.requestBody.close()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
