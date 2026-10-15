package dev.agentplatform.infrastructure.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class GitHubPullRequestPublisherTest {
    private val mapper = ObjectMapper()

    @Test
    fun `pushes an allowlisted branch and creates a draft pull request`() {
        val receivedBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = server { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/repos/example/repo/pulls" ->
                    respond(exchange, 200, "[]")
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/repos/example/repo/pulls" -> {
                    receivedBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                    respond(exchange, 201, """{"number":17,"html_url":"https://github.com/example/repo/pull/17","title":"Agent change","state":"open","draft":true}""")
                }
                else -> respond(exchange, 404, """{"message":"unexpected path"}""")
            }
        }
        try {
            val commands = RecordingGitCommands()
            val publisher = publisher(commands, server, "Example/Repo")
            val result = publisher.createDraft(
                "https://github.com/example/repo.git",
                Path.of("workspace"),
                "agent/task-01234567-89ab-cdef-0123-456789abcdef",
                "Agent change",
                "Validated task summary",
                listOf("src/Main.kt")
            )

            assertEquals(17, result.number)
            assertEquals("https://github.com/example/repo/pull/17", result.url)
            assertEquals("Agent change", result.title)
            assertEquals("open", result.state)
            assertTrue(result.draft)
            assertEquals("Bearer test-token", authorization.get())
            val body = mapper.readTree(receivedBody.get())
            assertEquals("agent/task-01234567-89ab-cdef-0123-456789abcdef", body.path("head").asText())
            assertEquals("main", body.path("base").asText())
            assertTrue(body.path("draft").asBoolean())
            assertTrue(body.path("body").asText().contains("Validated task summary"))
            assertTrue(commands.pushCommand!!.contains("https://github.com/example/repo.git"))
            assertFalse(commands.pushCommand!!.contains("test-token"))
            assertTrue(commands.pushEnvironment!!.getValue("GIT_CONFIG_VALUE_0").contains("AUTHORIZATION: basic "))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects missing credentials unapproved repositories and unsafe repository URLs before git`() {
        val server = server { exchange -> respond(exchange, 200, "{}") }
        try {
            val commands = RecordingGitCommands()
            val noToken = publisher(commands, server, "example/repo", token = "", enabled = false)
            assertThrows(IllegalArgumentException::class.java) {
                noToken.createDraft("https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files)
            }

            val restricted = publisher(commands, server, "example/other")
            assertThrows(IllegalArgumentException::class.java) {
                restricted.createDraft("https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files)
            }
            assertThrows(IllegalArgumentException::class.java) {
                restricted.createDraft("https://github.com.example/attacker/repo", Path.of("workspace"), branch, "title", "body", files)
            }
            assertTrue(commands.commands.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `returns an existing pull request instead of creating a duplicate`() {
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestURI.path == "/repos/example/repo/pulls" ->
                    respond(exchange, 200, """[{"number":9,"html_url":"https://github.com/example/repo/pull/9","title":"Existing","state":"open","draft":false,"head":{"ref":"$branch"}}]""")
                else -> respond(exchange, 404, "{}")
            }
        }
        try {
            val result = publisher(RecordingGitCommands(), server, "example/repo")
                .createDraft("https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files)
            assertEquals(9, result.number)
            assertEquals("Existing", result.title)
            assertFalse(result.draft)
        } finally {
            server.stop(0)
        }
    }

    private fun publisher(
        commands: RecordingGitCommands,
        server: HttpServer,
        allowlist: String,
        token: String = "test-token",
        enabled: Boolean = true
    ) = GitHubPullRequestPublisher(
        commands,
        mapper,
        token,
        allowlist,
        "http://127.0.0.1:${server.address.port}",
        enabled
    )

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class RecordingGitCommands : CommandExecutor {
        val commands = mutableListOf<List<String>>()
        var cachedDiffCalls = 0
        var pushCommand: List<String>? = null
        var pushEnvironment: Map<String, String>? = null

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            standardInput: String?,
            timeout: Duration,
            maxOutputChars: Int,
            environment: Map<String, String>
        ): CommandResult {
            commands += command
            if (command.contains("push")) {
                pushCommand = command
                pushEnvironment = environment
                return CommandResult(0, "pushed")
            }
            if (command.contains("remote.origin.url")) return CommandResult(0, "https://github.com/example/repo.git\n")
            if (command.contains("remote.origin.pushurl")) return CommandResult(1, "")
            if (command.contains("--get-regexp")) return CommandResult(1, "")
            if (command.contains("diff") && command.contains("--cached") && command.contains("--quiet")) {
                cachedDiffCalls++
                return CommandResult(if (cachedDiffCalls == 1) 0 else 1, "")
            }
            return CommandResult(0, "")
        }
    }

    private companion object {
        const val branch = "agent/task-01234567-89ab-cdef-0123-456789abcdef"
        val files = listOf("src/Main.kt")
    }
}
