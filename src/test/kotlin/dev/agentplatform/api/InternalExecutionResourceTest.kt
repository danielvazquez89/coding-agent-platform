package dev.agentplatform.api

import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.ValidationRunner
import jakarta.ws.rs.WebApplicationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class InternalExecutionResourceTest {
    private lateinit var root: Path
    private lateinit var workspace: Path
    private lateinit var runner: RecordingValidationRunner
    private lateinit var resource: InternalExecutionResource

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("execution-resource-test-")
        workspace = Files.createDirectory(root.resolve("attempt-1"))
        runner = RecordingValidationRunner()
        resource = InternalExecutionResource(runner, TOKEN.removePrefix("Bearer "), root.toString())
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `validates a real workspace under the configured root`() {
        val result = resource.validate(TOKEN, InternalExecutionResource.ValidationRequest(workspace.toString()))

        assertSame(runner.result, result)
        assertEquals(workspace.toRealPath(), runner.validatedPath)
        assertEquals(1, runner.calls)
    }

    @Test
    fun `rejects missing and incorrect authorization before invoking validation`() {
        assertStatus(401) {
            resource.validate(null, InternalExecutionResource.ValidationRequest(workspace.toString()))
        }
        assertStatus(401) {
            resource.validate("Bearer incorrect-token", InternalExecutionResource.ValidationRequest(workspace.toString()))
        }

        assertEquals(0, runner.calls)
    }

    @Test
    fun `returns service unavailable when gateway credentials are not configured`() {
        val unconfigured = InternalExecutionResource(runner, "", root.toString())

        assertStatus(503) {
            unconfigured.validate(TOKEN, InternalExecutionResource.ValidationRequest(workspace.toString()))
        }
        assertEquals(0, runner.calls)
    }

    @Test
    fun `rejects the root and paths outside the authorized workspace directory`() {
        val outside = Files.createDirectory(root.parent.resolve("execution-resource-outside-${System.nanoTime()}"))
        try {
            assertStatus(403) {
                resource.validate(TOKEN, InternalExecutionResource.ValidationRequest(root.toString()))
            }
            assertStatus(403) {
                resource.validate(TOKEN, InternalExecutionResource.ValidationRequest(outside.toString()))
            }
            assertEquals(0, runner.calls)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `rejects invalid missing and non-directory workspace paths`() {
        val file = Files.createFile(root.resolve("not-a-directory"))

        assertStatus(400) {
            resource.validate(TOKEN, InternalExecutionResource.ValidationRequest("\u0000"))
        }
        assertStatus(400) {
            resource.validate(TOKEN, InternalExecutionResource.ValidationRequest(root.resolve("missing").toString()))
        }
        assertStatus(403) {
            resource.validate(TOKEN, InternalExecutionResource.ValidationRequest(file.toString()))
        }
        assertEquals(0, runner.calls)
    }

    @Test
    fun `returns service unavailable when the configured workspace root is unavailable`() {
        val missingRoot = root.resolve("missing-root")
        val unavailable = InternalExecutionResource(runner, TOKEN.removePrefix("Bearer "), missingRoot.toString())

        assertStatus(503) {
            unavailable.validate(TOKEN, InternalExecutionResource.ValidationRequest(workspace.toString()))
        }
        assertEquals(0, runner.calls)
    }

    private fun assertStatus(status: Int, action: () -> Unit) {
        val failure = assertThrows(WebApplicationException::class.java) { action() }
        assertEquals(status, failure.response.status)
    }

    private class RecordingValidationRunner : ValidationRunner {
        val result = ValidationCheck("test validation", outcome = CheckOutcome.PASSED)
        var validatedPath: Path? = null
        var calls = 0

        override fun validate(workspace: Path): ValidationCheck {
            calls++
            validatedPath = workspace
            return result
        }
    }

    private companion object {
        const val TOKEN = "Bearer unit-test-token"
    }
}
