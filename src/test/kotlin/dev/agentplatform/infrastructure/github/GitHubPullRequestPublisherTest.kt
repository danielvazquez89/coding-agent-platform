package dev.agentplatform.infrastructure.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
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
    fun `validates GitHub publisher configuration and pull request inputs`() {
        val server = server { exchange -> respond(exchange, 200, """{"default_branch":"main"}""") }
        try {
            val commands = RecordingGitCommands()
            assertThrows(IllegalArgumentException::class.java) {
                GitHubPullRequestPublisher(commands, mapper, "token", "bad/name/extra", "https://api.github.com", false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GitHubPullRequestPublisher(commands, mapper, "token", "example/repo", "http://example.com", false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GitHubPullRequestPublisher(commands, mapper, "not-configured", "example/repo", "https://api.github.com", true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GitHubPullRequestPublisher(commands, mapper, "token", "not-configured", "https://api.github.com", true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GitHubPullRequestPublisher(commands, mapper, "token", "example/repo", "ftp://api.github.com", false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                publisher(commands, server, "example/repo", token = "not-configured")
                    .createDraft("https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files)
            }

            val client = publisher(commands, server, "example/repo")
            assertThrows(IllegalArgumentException::class.java) {
                client.createDraft("https://github.com/example/repo", Path.of("workspace"), "invalid-branch", "title", "body", files)
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.createDraft("https://github.com/example/repo", Path.of("workspace"), branch, " ", "body", files)
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.createDraft("https://github.com/example/repo", Path.of("workspace"), branch, "x".repeat(300), "body", files)
            }
            assertTrue(commands.commands.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects unsafe changed file lists before invoking git`() {
        val server = server { exchange ->
            when (exchange.requestURI.path) {
                "/repos/example/repo" -> respond(exchange, 200, """{"default_branch":"main"}""")
                "/repos/example/repo/pulls" -> respond(exchange, 200, "[]")
                else -> respond(exchange, 404, "{}")
            }
        }
        try {
            val commands = RecordingGitCommands()
            val client = publisher(commands, server, "example/repo")
            fun publish(changed: List<String>) = client.createDraft(
                "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", changed
            )

            assertThrows(IllegalArgumentException::class.java) { publish(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) { publish(listOf("../outside")) }
            assertThrows(IllegalArgumentException::class.java) {
                publish(listOf(Path.of(System.getProperty("java.io.tmpdir"), "outside").toString()))
            }
            assertThrows(IllegalArgumentException::class.java) { publish(listOf("unsafe\npath")) }
            assertThrows(IllegalArgumentException::class.java) { publish(listOf("unsafe\u0000path")) }
            assertThrows(IllegalArgumentException::class.java) { publish((0..250).map { "src/File$it.kt" }) }
            assertThrows(IllegalArgumentException::class.java) {
                publish((0..249).map { "d$it/" + "x".repeat(100) })
            }
            assertTrue(commands.commands.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects changed paths that traverse symbolic links when supported`() {
        val workspace = Files.createTempDirectory("github-publish-workspace-")
        val target = Files.createTempFile("github-publish-target-", ".txt")
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestURI.path == "/repos/example/repo/pulls" ->
                    respond(exchange, 200, "[]")
                else -> respond(exchange, 404, "{}")
            }
        }
        try {
            try {
                Files.createSymbolicLink(workspace.resolve("linked.txt"), target)
            } catch (_: Exception) {
                return
            }
            val commands = RecordingGitCommands()
            val failure = assertThrows(IllegalArgumentException::class.java) {
                publisher(commands, server, "example/repo").createDraft(
                    "https://github.com/example/repo",
                    workspace,
                    branch,
                    "title",
                    "body",
                    listOf("linked.txt")
                )
            }
            assertTrue(failure.message.orEmpty().contains("escapes"))
            assertTrue(commands.commands.isEmpty())
        } finally {
            server.stop(0)
            Files.deleteIfExists(workspace.resolve("linked.txt"))
            Files.deleteIfExists(workspace)
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `rejects incomplete GitHub repository and pull request responses`() {
        val body = AtomicReference("""{"number":17,"html_url":"https://github.com/example/repo/pull/17","title":"Agent change","state":"open","draft":true}""")
        val repository = AtomicReference("""{"default_branch":"main"}""")
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" -> respond(exchange, 200, repository.get())
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, "[]")
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "POST" ->
                    respond(exchange, 201, body.get())
                else -> respond(exchange, 404, "{}")
            }
        }
        try {
            val publisher = publisher(RecordingGitCommands(), server, "example/repo")
            repository.set("""{"default_branch":" "}""")
            assertThrows(IllegalStateException::class.java) {
                publisher.createDraft(
                    "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                )
            }
            repository.set("""{"default_branch":"main"}""")
            listOf(
                "{}",
                """{"number":"not-a-number","html_url":"url","state":"open","draft":true}""",
                """{"number":1,"state":"open","draft":true}""",
                """{"number":1,"html_url":"url","state":"unknown","draft":true}""",
                """{"number":1,"html_url":"url","state":"closed"}"""
            ).forEach { response ->
                body.set(response)
                assertThrows(IllegalStateException::class.java) {
                    publisher.createDraft(
                        "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                    )
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reports GitHub API errors and invalid JSON`() {
        val status = AtomicReference(403)
        val responseBody = AtomicReference("token denied")
        val server = server { exchange ->
            respond(exchange, status.get(), responseBody.get())
        }
        try {
            val publisher = publisher(RecordingGitCommands(), server, "example/repo")
            val denied = assertThrows(IllegalStateException::class.java) {
                publisher.createDraft(
                    "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                )
            }
            assertTrue(denied.message.orEmpty().contains("HTTP 403"))

            status.set(200)
            responseBody.set("{")
            val malformed = assertThrows(IllegalStateException::class.java) {
                publisher.createDraft(
                    "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                )
            }
            assertTrue(malformed.message.orEmpty().contains("invalid JSON"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects unsafe Git workspace configuration and staged states`() {
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, "[]")
                else -> respond(exchange, 201, """{"number":17,"html_url":"url","title":"title","state":"open","draft":true}""")
            }
        }
        try {
            val scenarios: List<Pair<String, (List<String>) -> CommandResult?>> = listOf(
                "origin" to { command -> if (command.contains("remote.origin.url")) CommandResult(0, "https://github.com/other/repo.git") else null },
                "pushurl" to { command -> if (command.contains("remote.origin.pushurl")) CommandResult(0, "https://attacker.invalid/repo.git") else null },
                "rewrite" to { command -> if (command.contains("--get-regexp")) CommandResult(0, "url.other.insteadOf https://github.com") else null },
                "pre-staged" to { command -> if (command.contains("--cached") && command.contains("--quiet")) CommandResult(1, "staged") else null },
                "no-staged-diff" to { command -> if (command.contains("--cached") && command.contains("--quiet")) CommandResult(0, "") else null },
                "push" to { command -> if (command.contains("push")) CommandResult(1, "push rejected") else null }
            )

            scenarios.forEach { (name, override) ->
                val commands = RecordingGitCommands().apply { resultOverride = override }
                val failure = assertThrows(Exception::class.java) {
                    publisher(commands, server, "example/repo").createDraft(
                        "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                    )
                }
                assertTrue(failure.message.orEmpty().isNotBlank(), name)
            }
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

    @Test
    fun `cleans up a non-empty temporary hooks directory without masking success`() {
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, "[]")
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "POST" ->
                    respond(exchange, 201, """{"number":5,"html_url":"url","title":"title","state":"open","draft":true}""")
                else -> respond(exchange, 404, "{}")
            }
        }
        var hooksDirectory: Path? = null
        try {
            val commands = RecordingGitCommands().apply {
                resultOverride = { command ->
                    command.firstOrNull { it.startsWith("core.hooksPath=") }?.let { flag ->
                        val directory = Path.of(flag.substringAfter('='))
                        hooksDirectory = directory
                        Files.writeString(directory.resolve("pre-commit"), "#!/bin/sh\n")
                    }
                    null
                }
            }
            val result = publisher(commands, server, "example/repo").createDraft(
                "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
            )
            assertEquals(5, result.number)
        } finally {
            server.stop(0)
        }
        assertNotNull(hooksDirectory)
        assertFalse(Files.exists(hooksDirectory!!), "temp hooks directory should be removed even when non-empty")
    }

    @Test
    fun `redacts the configured token from git failure messages`() {
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/repos/example/repo" ->
                    respond(exchange, 200, """{"default_branch":"main"}""")
                exchange.requestURI.path == "/repos/example/repo/pulls" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, "[]")
                else -> respond(exchange, 404, "{}")
            }
        }
        try {
            val commands = RecordingGitCommands().apply {
                resultOverride = { command ->
                    if (command.contains("push")) CommandResult(1, "fatal: authentication failed for test-token")
                    else null
                }
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                publisher(commands, server, "example/repo").createDraft(
                    "https://github.com/example/repo", Path.of("workspace"), branch, "title", "body", files
                )
            }
            assertFalse(failure.message.orEmpty().contains("test-token"))
            assertTrue(failure.message.orEmpty().contains("[redacted]"))
        } finally {
            server.stop(0)
        }
    }

    private fun publisher(
        commands: RecordingGitCommands,
        server: HttpServer,
        allowlist: String,
        token: String = "test-token",
        enabled: Boolean = true,
        apiUrl: String = "http://127.0.0.1:${server.address.port}"
    ) = GitHubPullRequestPublisher(
        commands,
        mapper,
        token,
        allowlist,
        apiUrl,
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
        var resultOverride: ((List<String>) -> CommandResult?)? = null
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
            resultOverride?.invoke(command)?.let { return it }
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
