package dev.agentplatform.infrastructure.docker

import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class DockerValidationRunnerTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `skips unsupported repositories and offline caches`() {
        val repo = Files.createDirectory(temporary.resolve("repo"))
        val noPom = runner(fakeCommands(), temporary.resolve("m2").toString()).validate(repo)
        assertEquals(CheckOutcome.SKIPPED, noPom.outcome)
        assertTrue(noPom.details!!.contains("Maven repositories only"))

        Files.writeString(repo.resolve("pom.xml"), "<project/>")
        val noCache = runner(fakeCommands(), temporary.resolve("missing-m2").toString()).validate(repo)
        assertEquals(CheckOutcome.SKIPPED, noCache.outcome)
        assertTrue(noCache.details!!.contains("artifact cache is missing"))
    }

    @Test
    fun `runs tests inside a private network with ephemeral postgres and removes resources`() {
        val repo = mavenRepository()
        val readyCalls = AtomicInteger()
        val commands = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ): CommandResult = when {
                command.take(3) == listOf("docker", "network", "create") -> CommandResult(0, "private-net")
                command.take(3) == listOf("docker", "run", "-d") -> CommandResult(0, "postgres-id")
                command.take(2) == listOf("docker", "exec") ->
                    if (readyCalls.incrementAndGet() == 1) CommandResult(1, "starting") else CommandResult(0, "ready")
                command.contains("mvn") -> {
                    assertTrue(command.contains("--internal").not())
                    assertTrue(command.contains("--network"))
                    assertTrue(command.none { it == "--network=host" })
                    assertTrue(command.contains("--cap-drop=ALL"))
                    assertTrue(command.contains("--security-opt=no-new-privileges"))
                    assertTrue(command.contains("--pids-limit=1024"))
                    assertTrue(command.contains("--read-only"))
                    assertTrue(command.contains("--memory=2g"))
                    assertTrue(command.contains("QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://agent-db:5432/postgres"))
                    assertTrue(command.contains("-o"))
                    CommandResult(0, "Tests passed")
                }
                command.take(2) == listOf("docker", "rm") -> CommandResult(0, "removed")
                command.take(3) == listOf("docker", "network", "rm") -> CommandResult(0, "removed")
                else -> error("Unexpected command $command")
            }
        }

        val result = runner(commands, temporary.resolve("m2").toString(), readyAttempts = 2).validate(repo)
        assertEquals(CheckOutcome.PASSED, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals(2, readyCalls.get())
        assertTrue(result.details!!.contains("Tests passed"))
        assertTrue(result.durationMillis!! >= 0)
    }

    @Test
    fun `mounts only the task workspace subdirectory and shared read-only Maven cache volumes`() {
        val workspaceRoot = Files.createDirectory(temporary.resolve("workspaces"))
        val workspace = Files.createDirectory(workspaceRoot.resolve("attempt-test-123"))
        Files.writeString(workspace.resolve("pom.xml"), "<project/>")
        val cache = Files.createDirectory(temporary.resolve("maven-cache"))
        val mavenCommand = mutableListOf<String>()
        val commands = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ): CommandResult {
                if (command.contains("mvn")) mavenCommand += command
                return when {
                    command.take(3) == listOf("docker", "network", "create") -> CommandResult(0, "network")
                    command.take(3) == listOf("docker", "run", "-d") -> CommandResult(0, "db")
                    command.take(2) == listOf("docker", "exec") -> CommandResult(0, "ready")
                    else -> CommandResult(0, "ok")
                }
            }
        }

        val result = DockerValidationRunner(
            commands, "maven:test", "postgres:test", cache.toString(), 10, 2, 0,
            mavenCacheVolume = "agent-maven-cache",
            workspaceVolume = "agent-workspaces",
            configuredWorkspaceRoot = workspaceRoot.toString()
        ).validate(workspace)

        assertEquals(CheckOutcome.PASSED, result.outcome)
        assertTrue(mavenCommand.any { it.contains("volume-subpath=attempt-test-123") })
        assertTrue(mavenCommand.any { it.contains("source=agent-maven-cache,target=/m2/repository,readonly") })
        assertTrue(mavenCommand.none { it.startsWith("type=bind,source=") })
    }

    @Test
    fun `delegates validation to the authenticated control plane when configured`() {
        val authorization = java.util.concurrent.atomic.AtomicReference<String>()
        val payload = java.util.concurrent.atomic.AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/execution/validate") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            payload.set(exchange.requestBody.bufferedReader().readText())
            val response = """{"name":"Maven tests","command":"mvn in isolated Docker","outcome":"PASSED","exitCode":0,"details":"Tests passed","durationMillis":120}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val runner = DockerValidationRunner(
                fakeCommands(),
                "maven:test",
                "postgres:test",
                temporary.toString(),
                10,
                2,
                0,
                controlPlaneUrl = "http://127.0.0.1:${server.address.port}",
                controlPlaneToken = "gateway-test-token"
            )

            val result = runner.validate(temporary)

            assertEquals(CheckOutcome.PASSED, result.outcome)
            assertEquals("Bearer gateway-test-token", authorization.get())
            assertTrue(payload.get().contains(temporary.fileName.toString()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reports sandbox startup failures and always cleans partial resources`() {
        val repo = mavenRepository()
        val networkFailure = runWith(repo, object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = if (command.take(3) == listOf("docker", "network", "create")) CommandResult(1, "denied") else CommandResult(0, "")
        })
        assertEquals(CheckOutcome.FAILED, networkFailure.outcome)
        assertTrue(networkFailure.details!!.contains("Could not create private Docker network"))

        val databaseFailure = runWith(repo, object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = when {
                command.take(3) == listOf("docker", "network", "create") -> CommandResult(0, "network")
                command.take(3) == listOf("docker", "run", "-d") -> CommandResult(1, "postgres failed")
                else -> CommandResult(0, "cleanup")
            }
        })
        assertEquals(CheckOutcome.FAILED, databaseFailure.outcome)
        assertTrue(databaseFailure.details!!.contains("Could not start temporary PostgreSQL"))
    }

    @Test
    fun `reports database readiness and Maven failures including timeouts`() {
        val repo = mavenRepository()
        val notReady = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = when {
                command.take(3) == listOf("docker", "network", "create") -> CommandResult(0, "network")
                command.take(3) == listOf("docker", "run", "-d") -> CommandResult(0, "db")
                command.take(2) == listOf("docker", "exec") -> CommandResult(1, "not ready")
                else -> CommandResult(0, "cleanup")
            }
        }
        val noDatabase = runWith(repo, notReady, readyAttempts = 2)
        assertEquals(CheckOutcome.FAILED, noDatabase.outcome)
        assertTrue(noDatabase.details!!.contains("did not become ready"))

        val failedTest = runWith(repo, fakeCommands(mavenResult = CommandResult(1, "tests failed")))
        assertEquals(CheckOutcome.FAILED, failedTest.outcome)
        assertEquals(1, failedTest.exitCode)
        assertTrue(failedTest.details!!.contains("tests failed"))

        val timeout = runWith(repo, fakeCommands(mavenResult = CommandResult(-1, "hung", timedOut = true)))
        assertEquals(CheckOutcome.FAILED, timeout.outcome)
        assertEquals(null, timeout.exitCode)
        assertTrue(timeout.details!!.contains("timed out"))
    }

    private fun runWith(repo: Path, commands: CommandExecutor, readyAttempts: Int = 2) =
        runner(commands, temporary.resolve("m2").toString(), readyAttempts).validate(repo)

    private fun runner(commands: CommandExecutor, cache: String, readyAttempts: Int = 2) =
        DockerValidationRunner(commands, "maven:test", "postgres:test", cache, 10, readyAttempts, 0)

    private fun mavenRepository(): Path {
        val repo = Files.createDirectory(temporary.resolve("repo-${System.nanoTime()}"))
        Files.writeString(repo.resolve("pom.xml"), "<project/>")
        Files.createDirectory(temporary.resolve("m2"))
        return repo
    }

    private fun fakeCommands(mavenResult: CommandResult = CommandResult(0, "Tests passed")) = object : CommandExecutor {
        override fun run(
            command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
            maxOutputChars: Int, environment: Map<String, String>
        ): CommandResult = when {
            command.take(3) == listOf("docker", "network", "create") -> CommandResult(0, "network")
            command.take(3) == listOf("docker", "run", "-d") -> CommandResult(0, "db")
            command.take(2) == listOf("docker", "exec") -> CommandResult(0, "ready")
            command.contains("mvn") -> mavenResult
            else -> CommandResult(0, "cleanup")
        }
    }
}
