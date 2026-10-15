package dev.agentplatform.infrastructure

import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.model.CheckOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ExecutionControlHttpTest {
    @Test
    fun `sends authenticated workspace request and parses validation result`() {
        val authorization = AtomicReference<String>()
        val payload = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/execution/validate") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            payload.set(exchange.requestBody.bufferedReader().readText())
            respond(
                exchange,
                200,
                """{"name":"Maven tests","command":"mvn test","outcome":"PASSED","exitCode":0,"details":"All tests passed","durationMillis":42}"""
            )
        }
        server.start()
        val workspace = Files.createTempDirectory("execution-control-http-")
        try {
            val result = ExecutionControlHttp.validate(
                "http://127.0.0.1:${server.address.port}",
                "test-token",
                workspace,
                5
            )

            assertEquals("Bearer test-token", authorization.get())
            assertEquals(
                workspace.toAbsolutePath().normalize().toString(),
                com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.get()).path("workspacePath").asText()
            )
            assertEquals("Maven tests", result.name)
            assertEquals("mvn test", result.command)
            assertEquals(CheckOutcome.PASSED, result.outcome)
            assertEquals(0, result.exitCode)
            assertEquals("All tests passed", result.details)
            assertEquals(42L, result.durationMillis)
        } finally {
            server.stop(0)
            Files.deleteIfExists(workspace)
        }
    }

    @Test
    fun `reports non-success control-plane responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workspace = Files.createTempDirectory("execution-control-http-")
        server.createContext("/internal/execution/validate") { exchange ->
            respond(exchange, 403, "Workspace rejected")
        }
        server.start()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                ExecutionControlHttp.validate(
                    "http://127.0.0.1:${server.address.port}",
                    "test-token",
                    workspace,
                    5
                )
            }

            assertTrue(failure.message.orEmpty().contains("HTTP 403"))
            assertTrue(failure.message.orEmpty().contains("Workspace rejected"))
        } finally {
            server.stop(0)
            Files.deleteIfExists(workspace)
        }
    }

    @Test
    fun `rejects missing credentials and invalid timeouts before sending requests`() {
        val workspace = Files.createTempDirectory("execution-control-http-")

        try {
            assertThrows(IllegalArgumentException::class.java) {
                ExecutionControlHttp.validate("http://localhost", " ", workspace, 5)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ExecutionControlHttp.validate("http://localhost", "test-token", workspace, 0)
            }
        } finally {
            Files.deleteIfExists(workspace)
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
