package dev.agentplatform.application

import dev.agentplatform.AgentWorkflowTestSupport
import dev.agentplatform.AgentWorkflowTestResource
import dev.agentplatform.application.worker.TaskExecutionWorker
import dev.agentplatform.domain.model.*
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import dev.agentplatform.infrastructure.docker.DockerValidationRunner
import dev.agentplatform.infrastructure.github.GitHubPullRequestPublisher
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@QuarkusTest
@TestProfile(AgentWorkflowTestProfile::class)
@QuarkusTestResource(AgentWorkflowTestResource::class, restrictToAnnotatedClass = true)
class AgentWorkflowIntegrationTest {
    @Inject
    lateinit var worker: TaskExecutionWorker

    private lateinit var validation: RecordingValidationRunner
    private lateinit var publisher: RecordingPullRequestPublisher

    @BeforeEach
    fun installAdaptersAndClearScript() {
        AgentWorkflowTestSupport.reset()
        Files.list(AgentWorkflowTestSupport.workspaceRootPath()).use { workspaces ->
            workspaces.forEach(::deleteTree)
        }
        validation = RecordingValidationRunner()
        publisher = RecordingPullRequestPublisher()
        QuarkusMock.installMockForType(validation, DockerValidationRunner::class.java)
        QuarkusMock.installMockForType(publisher, GitHubPullRequestPublisher::class.java)
    }

    @Test
    fun `REST submission flows through repository copy tool model validation durable audit and draft PR`() {
        val criterion = "README contains after"
        val repository = repository("before")
        validation.result = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED, exitCode = 0, details = "All tests passed")
        validation.onValidate = { workspace ->
            assertEquals("after\n", Files.readString(workspace.resolve("README.md")))
        }
        AgentWorkflowTestSupport.enqueueToolCall("apply_patch", mapOf("patch" to AgentWorkflowTestSupport.patch("before", "after")))
        AgentWorkflowTestSupport.enqueueAssistant(AgentWorkflowTestSupport.successfulFinal(criterion))

        val taskId = submitTask(repository, criterion)
        assertEquals("QUEUED", task(taskId).path("status").asText())
        assertTrue(worker.execute(java.util.UUID.fromString(taskId)))

        val task = task(taskId)
        assertEquals("COMPLETED", task.path("status").asText())
        assertEquals("before\n", Files.readString(repository.resolve("README.md")), "the source repository must remain unchanged")
        assertEquals(1, validation.calls)
        assertEquals(1, publisher.calls.size)
        assertEquals(
            "https://github.com/integration/repo/pull/7",
            attempts(taskId).path(0).path("report").path("pullRequest").path("url").asText()
        )
        assertEquals(listOf("QUEUED", "RUNNING", "VALIDATING", "COMPLETED"), history(taskId))

        val attempt = attempts(taskId).path(0)
        assertEquals("COMPLETED", attempt.path("status").asText())
        assertEquals("PASSED", attempt.path("report").path("validation").path(0).path("outcome").asText())
        assertEquals(
            listOf("README.md"),
            attempt.path("report").path("changedFiles").map { it.asText() }
        )
        assertEquals(2, attempt.path("report").path("toolExecutions").size())
        assertEquals(
            listOf("SUCCEEDED", "SUCCEEDED"),
            attempt.path("report").path("toolExecutions").map { it.path("outcome").asText() }
        )
        assertEquals("SUCCEEDED", attempt.path("report").path("toolExecutions").path(0).path("outcome").asText())
        assertEquals("PASSED", attempt.path("report").path("acceptanceChecks").path(0).path("outcome").asText())
        assertTrue(attempt.path("report").path("pullRequest").path("draft").asBoolean())

        val toolEvents = given()
            .`when`().get("/api/tasks/$taskId/attempts/${attempt.path("id").asText()}/tool-executions")
            .then()
            .statusCode(200)
            .extract().`as`(com.fasterxml.jackson.databind.JsonNode::class.java)
        assertEquals(2, toolEvents.size())
        assertEquals(listOf(1, 2), toolEvents.map { it.path("sequenceNumber").asInt() })
        assertEquals("apply_patch", toolEvents.path(0).path("toolName").asText())
        assertTrue(toolEvents.path(0).path("argumentsSummary").asText().contains("patchCharacters="))
        assertEquals("run_validation", toolEvents.path(1).path("toolName").asText())
        assertEquals("SUCCEEDED", toolEvents.path(1).path("outcome").asText())

        assertTrue(AgentWorkflowTestSupport.modelRequests.isNotEmpty())
        assertTrue(AgentWorkflowTestSupport.modelRequests.first().contains("apply_patch"))
        assertTrue(Files.list(AgentWorkflowTestSupport.workspaceRootPath()).use { !it.findAny().isPresent })
    }

    @Test
    fun `blocking question persists first attempt and answer endpoint resumes to a completed second attempt`() {
        val criterion = "README contains after"
        val repository = repository("before")
        validation.result = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED, exitCode = 0)
        AgentWorkflowTestSupport.enqueueToolCall("apply_patch", mapOf("patch" to AgentWorkflowTestSupport.patch("before", "after")))
        AgentWorkflowTestSupport.enqueueAssistant(
            AgentWorkflowTestSupport.mapper.writeValueAsString(
                mapOf(
                    "summary" to "Change needs a decision",
                    "implementation" to "Applied the requested patch.",
                    "openQuestions" to listOf(mapOf("question" to "Which output format?", "impact" to "The API response depends on this.", "blocking" to true))
                )
            )
        )
        AgentWorkflowTestSupport.enqueueToolCall("apply_patch", mapOf("patch" to AgentWorkflowTestSupport.patch("before", "after")))
        AgentWorkflowTestSupport.enqueueAssistant(AgentWorkflowTestSupport.successfulFinal(criterion))

        val taskId = submitTask(repository, criterion)
        worker.execute(java.util.UUID.fromString(taskId))
        assertEquals("NEEDS_INPUT", task(taskId).path("status").asText())
        assertEquals(1, attempts(taskId).size())
        val firstAttempt = attempts(taskId).path(0)
        assertEquals("NEEDS_INPUT", firstAttempt.path("status").asText())
        assertEquals("Which output format?", firstAttempt.path("report").path("openQuestions").path(0).path("question").asText())
        val firstAttemptId = firstAttempt.path("id").asText()
        given()
            .`when`().get("/api/tasks/$taskId/attempts/$firstAttemptId/tool-executions")
            .then()
            .statusCode(200)
            .body("size()", equalTo(2))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "Use JSON output"))
            .`when`().post("/api/tasks/$taskId/answer")
            .then()
            .statusCode(200)
            .body("status", equalTo("QUEUED"))
            .body("description", containsString("Use JSON output"))

        worker.execute(java.util.UUID.fromString(taskId))

        assertEquals("COMPLETED", task(taskId).path("status").asText())
        assertEquals(listOf("QUEUED", "RUNNING", "NEEDS_INPUT", "QUEUED", "RUNNING", "VALIDATING", "COMPLETED"), history(taskId))
        assertEquals(2, attempts(taskId).size())
        assertEquals(listOf("NEEDS_INPUT", "COMPLETED"), attempts(taskId).map { it.path("status").asText() })
        assertTrue(
            AgentWorkflowTestSupport.modelRequests.drop(2).any { request ->
                AgentWorkflowTestSupport.mapper.readTree(request).path("messages")
                    .any { it.path("content").asText().contains("Use JSON output") }
            },
            "the clarification should be included in the next model prompt"
        )
        assertEquals(1, publisher.calls.size, "only the completed attempt should publish a PR")
    }

    @Test
    fun `failed validation cannot complete or publish a task`() {
        val criterion = "README contains after"
        val repository = repository("before")
        validation.result = ValidationCheck("Maven tests", outcome = CheckOutcome.FAILED, exitCode = 1, details = "Tests failed")
        AgentWorkflowTestSupport.enqueueToolCall("apply_patch", mapOf("patch" to AgentWorkflowTestSupport.patch("before", "after")))
        // The first report arrives after the failed validation; the worker asks for a fix twice
        // before accepting that the task cannot complete. The validation keeps failing, so the
        // outcome must still be FAILED and no pull request may be opened.
        repeat(1 + 2) { AgentWorkflowTestSupport.enqueueAssistant(AgentWorkflowTestSupport.successfulFinal(criterion)) }

        val taskId = submitTask(repository, criterion)
        worker.execute(java.util.UUID.fromString(taskId))

        assertEquals("FAILED", task(taskId).path("status").asText())
        val attempt = attempts(taskId).path(0)
        assertEquals("FAILED", attempt.path("report").path("validation").path(0).path("outcome").asText())
        val pullRequest = attempt.path("report").path("pullRequest")
        assertTrue(pullRequest.isMissingNode || pullRequest.isNull)
        assertTrue(publisher.calls.isEmpty())
        assertEquals(listOf("QUEUED", "RUNNING", "VALIDATING", "FAILED"), history(taskId))
        assertTrue(
            AgentWorkflowTestSupport.modelRequests.any { request ->
                AgentWorkflowTestSupport.mapper.readTree(request).path("messages")
                    .any { it.path("content").asText().contains("the last validation failed") }
            },
            "the worker should ask the model to fix the failed validation before giving up"
        )
    }

    @Test
    fun `unapproved model tool is durably rejected and never reaches the command executor`() {
        val repository = repository("before")
        AgentWorkflowTestSupport.enqueueToolCall("run_shell", mapOf("command" to "touch compromised"))
        repeat(3) { AgentWorkflowTestSupport.enqueueAssistant("""{"summary":"No approved change was made","implementation":"No patch was applied."}""") }

        val taskId = submitTask(repository)
        worker.execute(java.util.UUID.fromString(taskId))

        assertEquals("FAILED", task(taskId).path("status").asText())
        val attempt = attempts(taskId).path(0)
        assertEquals("FAILED", attempt.path("status").asText())
        val events = given()
            .`when`().get("/api/tasks/$taskId/attempts/${attempt.path("id").asText()}/tool-executions")
            .then().statusCode(200).extract().`as`(com.fasterxml.jackson.databind.JsonNode::class.java)
        assertEquals("REJECTED", events.single { it.path("toolName").asText() == "run_shell" }.path("outcome").asText())
        assertEquals("SUCCEEDED", events.single { it.path("toolName").asText() == "run_validation" }.path("outcome").asText())
        assertFalse(Files.exists(repository.resolve("compromised")))
    }

    @Test
    fun `gateway authenticates worker and only validates workspace paths under its configured root`() {
        val workspace = Files.createDirectory(AgentWorkflowTestSupport.workspaceRootPath().resolve("attempt-gateway-test"))
        validation.result = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
        validation.onValidate = { assertEquals(workspace, it) }

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("workspacePath" to workspace.toString()))
            .`when`().post("/internal/execution/validate")
            .then().statusCode(401)
        given()
            .header("Authorization", "Bearer integration-control-plane-token")
            .contentType(ContentType.JSON)
            .body(mapOf("workspacePath" to AgentWorkflowTestSupport.allowedRepositoryRootPath().toString()))
            .`when`().post("/internal/execution/validate")
            .then().statusCode(403)
        given()
            .header("Authorization", "Bearer integration-control-plane-token")
            .contentType(ContentType.JSON)
            .body(mapOf("workspacePath" to workspace.toString()))
            .`when`().post("/internal/execution/validate")
            .then()
            .statusCode(200)
            .body("outcome", equalTo("PASSED"))
        assertEquals(1, validation.calls)
    }

    private fun repository(content: String): Path {
        val root = Files.createTempDirectory(AgentWorkflowTestSupport.allowedRepositoryRootPath(), "repo-")
        Files.writeString(root.resolve("README.md"), "$content\n")
        runGit(root, "init")
        runGit(root, "add", "README.md")
        return root
    }

    private fun runGit(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString(), *args))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git command timed out: $output")
        assertEquals(0, process.exitValue(), "git command failed: $output")
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun submitTask(repository: Path, criterion: String? = null): String {
        val request = mutableMapOf<String, Any>(
            "title" to "Integration test task",
            "description" to "Change README.md from before to after.",
            "acceptanceCriteria" to emptyList<String>(),
            "repositoryUrl" to repository.toString()
        )
        if (criterion != null) request["acceptanceCriteria"] = listOf(criterion)
        return given()
            .contentType(ContentType.JSON)
            .body(request)
            .`when`().post("/api/tasks")
            .then().statusCode(201)
            .extract().asString().let { AgentWorkflowTestSupport.mapper.readTree(it).path("id").asText() }
    }

    private fun task(id: String) = AgentWorkflowTestSupport.mapper.readTree(
        given().`when`().get("/api/tasks/$id").then().statusCode(200).extract().asString()
    )

    private fun attempts(id: String) = AgentWorkflowTestSupport.mapper.readTree(
        given().`when`().get("/api/tasks/$id/attempts").then().statusCode(200).extract().asString()
    )

    private fun history(id: String): List<String> = AgentWorkflowTestSupport.mapper.readTree(
        given().`when`().get("/api/tasks/$id/history").then().statusCode(200).extract().asString()
    ).map { it.path("toStatus").asText() }

    private class RecordingValidationRunner : DockerValidationRunner(
        commands = object : CommandExecutor {
            override fun run(
                command: List<String>,
                workingDirectory: Path?,
                standardInput: String?,
                timeout: java.time.Duration,
                maxOutputChars: Int,
                environment: Map<String, String>
            ): CommandResult = error("Validation test double should not execute host commands")
        },
        mavenImage = "maven:test",
        postgresImage = "postgres:test",
        configuredMavenRepository = ".",
        validationTimeoutSeconds = 1,
        databaseReadyAttempts = 1,
        databaseReadyDelayMillis = 0
    ) {
        var result = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
        var calls = 0
        var onValidate: (Path) -> Unit = {}

        override fun validate(workspace: Path): ValidationCheck {
            calls++
            onValidate(workspace)
            return result
        }
    }

    private class RecordingPullRequestPublisher : GitHubPullRequestPublisher(
        commands = object : CommandExecutor {
            override fun run(
                command: List<String>,
                workingDirectory: Path?,
                standardInput: String?,
                timeout: java.time.Duration,
                maxOutputChars: Int,
                environment: Map<String, String>
            ): CommandResult = error("Pull-request publisher test double should not execute host commands")
        },
        objectMapper = AgentWorkflowTestSupport.mapper,
        configuredToken = "integration-test-token",
        configuredAllowedRepositories = "integration/repo",
        apiUrl = "https://api.github.com",
        enabled = true
    ) {
        data class Call(val repositoryUrl: String, val changedFiles: List<String>)
        val calls = mutableListOf<Call>()

        override fun createDraft(
            repositoryUrl: String,
            workspace: Path,
            branchName: String,
            title: String,
            body: String,
            changedFiles: List<String>
        ): PullRequestReference {
            calls += Call(repositoryUrl, changedFiles)
            return PullRequestReference(7, "https://github.com/integration/repo/pull/7", title)
        }
    }
}

class AgentWorkflowTestProfile : io.quarkus.test.junit.QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "agent.execution.enabled" to "false",
        "agent.execution.worker.enabled" to "false",
        "agent.github.pull-requests.enabled" to "true",
        "agent.github.token" to "integration-test-token",
        "agent.github.allowed-repositories" to "integration/repo",
        "agent.execution.control-plane-url" to "disabled",
        "agent.execution.docker.maven-cache-volume" to "disabled",
        "agent.execution.docker.workspace-volume" to "disabled",
        "quarkus.scheduler.enabled" to "false"
    )

}
