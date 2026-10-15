package dev.agentplatform.application.service

import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TransitionTaskRequest
import dev.agentplatform.domain.model.*
import dev.agentplatform.domain.port.TaskBudgetUsage
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

@QuarkusTest
class TaskAttemptTest {
    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var attemptService: TaskAttemptService

    @Test
    fun `attempts preserve structured agent report and are exposed in order`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")

        val first = attemptService.start(taskId)!!
        assertThrows(IllegalStateException::class.java) { attemptService.start(taskId) }
        val report = TaskExecutionReport(
            summary = "Added bounded retry handling",
            implementation = "Retries preserve the original request and stop at the configured limit.",
            decisions = listOf(
                AgentDecision(
                    topic = "Retry policy",
                    choice = "Use bounded exponential backoff",
                    rationale = "Avoids immediate repeated load while keeping retries finite.",
                    alternatives = listOf("Fixed delay", "No automatic retry")
                )
            ),
            openQuestions = listOf(
                AgentQuestion(
                    question = "Should retry limits vary by provider?",
                    impact = "Provider-specific limits could improve reliability and cost control.",
                    blocking = false
                )
            ),
            changedFiles = listOf("src/main/kotlin/RetryPolicy.kt"),
            validation = listOf(
                ValidationCheck("RetryPolicyTest", "./mvnw test", CheckOutcome.PASSED, 0, "All assertions passed", 1280)
            ),
            patchDiff = "diff --git a/src/main/kotlin/RetryPolicy.kt b/src/main/kotlin/RetryPolicy.kt",
            acceptanceChecks = listOf(
                AcceptanceCheck("Retries stop at the configured limit", AcceptanceOutcome.PASSED, "RetryPolicyTest passed")
            ),
            patchFailures = listOf("The first diff was rejected; a corrected patch applied."),
            modelUsage = ModelUsage("qwen3:8b", 1200, 350, 4, 2100, 0.0),
            executionDurationMillis = 4500,
            toolExecutions = listOf(
                ToolExecutionAudit(1, "read_file", "path=README.md", ToolExecutionOutcome.SUCCEEDED, 12, "Returned 42 characters")
            ),
            maxExecutionDurationMillis = 1_800_000,
            maxEstimatedCostUsd = 1.0,
            totalTaskExecutionDurationMillis = 4500,
            totalEstimatedCostUsd = 0.0
        )
        val finishedFirst = attemptService.finish(taskId, first.id, AttemptStatus.COMPLETED, report)!!

        val second = attemptService.start(taskId)!!
        assertEquals(TaskBudgetUsage(4500, 0.0), attemptService.priorBudgetUsage(taskId))
        attemptService.finish(
            taskId,
            second.id,
            AttemptStatus.NEEDS_INPUT,
            report.copy(
                openQuestions = listOf(
                    AgentQuestion(
                        question = "Which supported runtime should be targeted?",
                        impact = "The patch depends on APIs available only on newer runtimes.",
                        blocking = true
                    )
                )
            )
        )

        assertEquals(1, first.attemptNumber)
        assertEquals(2, second.attemptNumber)
        assertNotNull(finishedFirst.finishedAt)
        assertEquals(TaskBudgetUsage(9000, 0.0), attemptService.priorBudgetUsage(taskId))

        given()
            .`when`().get("/api/tasks/$taskId/attempts")
            .then()
            .statusCode(200)
            .body("size()", equalTo(2))
            .body("[0].attemptNumber", equalTo(1))
            .body("[0].status", equalTo("COMPLETED"))
            .body("[0].report.summary", equalTo("Added bounded retry handling"))
            .body("[0].report.decisions[0].rationale", equalTo("Avoids immediate repeated load while keeping retries finite."))
            .body("[0].report.openQuestions[0].question", equalTo("Should retry limits vary by provider?"))
            .body("[0].report.openQuestions[0].impact", equalTo("Provider-specific limits could improve reliability and cost control."))
            .body("[0].report.openQuestions[0].blocking", equalTo(false))
            .body("[0].report.validation[0].outcome", equalTo("PASSED"))
            .body("[0].report.acceptanceChecks[0].outcome", equalTo("PASSED"))
            .body("[0].report.acceptanceChecks[0].evidence", equalTo("RetryPolicyTest passed"))
            .body("[0].report.patchFailures[0]", equalTo("The first diff was rejected; a corrected patch applied."))
            .body("[0].report.modelUsage.promptTokens", equalTo(1200))
            .body("[0].report.modelUsage.estimatedCostUsd", notNullValue())
            .body("[0].report.executionDurationMillis", equalTo(4500))
            .body("[0].report.toolExecutions[0].outcome", equalTo("SUCCEEDED"))
            .body("[0].report.toolExecutions[0].argumentsSummary", equalTo("path=README.md"))
            .body("[0].report.maxExecutionDurationMillis", equalTo(1_800_000))
            .body("[0].report.totalTaskExecutionDurationMillis", equalTo(4500))
            .body("[1].attemptNumber", equalTo(2))
            .body("[1].status", equalTo("NEEDS_INPUT"))

        given()
            .`when`().get("/api/tasks/$taskId/attempts/${first.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(first.id.toString()))
            .body("report.patchDiff", notNullValue())
    }

    @Test
    fun `attempt creation requires a running task and finished attempts are immutable`() {
        val queuedTaskId = createTask()
        assertThrows(IllegalStateException::class.java) { attemptService.start(queuedTaskId) }

        taskService.transition(queuedTaskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(queuedTaskId)!!
        assertNull(attemptService.finish(queuedTaskId, UUID.randomUUID(), AttemptStatus.FAILED, null))
        assertNull(attemptService.finish(UUID.randomUUID(), attempt.id, AttemptStatus.FAILED, null))
        assertThrows(IllegalArgumentException::class.java) {
            attemptService.finish(queuedTaskId, attempt.id, AttemptStatus.RUNNING, null)
        }
        attemptService.finish(queuedTaskId, attempt.id, AttemptStatus.FAILED, null, "Provider timed out")

        assertThrows(IllegalStateException::class.java) {
            attemptService.finish(queuedTaskId, attempt.id, AttemptStatus.COMPLETED, null)
        }
        assertNull(attemptService.find(UUID.randomUUID(), attempt.id))
    }

    @Test
    fun `attempt failure details are truncated and absent reports remain null`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!
        val failure = attemptService.finish(taskId, attempt.id, AttemptStatus.FAILED, null, "x".repeat(4100))!!

        assertEquals(4000, failure.errorSummary?.length)
        assertNull(failure.reportJson)
        assertNull(attemptService.find(taskId, attempt.id)?.report)
        assertEquals(TaskBudgetUsage(null, null), attemptService.priorBudgetUsage(taskId))
        assertNull(attemptService.start(UUID.randomUUID()))
    }

    @Test
    fun `tool events persist independently of the final attempt report`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!
        val event = ToolExecutionAudit(
            sequenceNumber = 1,
            toolName = "read_file",
            argumentsSummary = "path=src/Main.kt",
            outcome = ToolExecutionOutcome.STARTED,
            durationMillis = 0,
            resultSummary = "Tool execution started"
        )

        attemptService.recordToolExecution(taskId, attempt.id, event)

        assertEquals(listOf(event), attemptService.toolExecutions(taskId, attempt.id))
        attemptService.finishToolExecution(
            taskId, attempt.id,
            event.copy(
                outcome = ToolExecutionOutcome.SUCCEEDED,
                durationMillis = 24,
                resultSummary = "Returned 120 characters"
            )
        )
        assertEquals(ToolExecutionOutcome.SUCCEEDED, attemptService.toolExecutions(taskId, attempt.id).single().outcome)
        assertNull(attempt.reportJson)
        given()
            .`when`().get("/api/tasks/$taskId/attempts/${attempt.id}/tool-executions")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].sequenceNumber", equalTo(1))
            .body("[0].toolName", equalTo("read_file"))
            .body("[0].outcome", equalTo("SUCCEEDED"))
            .body("[0].resultSummary", equalTo("Returned 120 characters"))
    }

    @Test
    fun `prior budget usage is unknown when a saved attempt report omits measurements`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!
        attemptService.finish(
            taskId, attempt.id, AttemptStatus.NEEDS_INPUT, TaskExecutionReport("Summary", "Implementation")
        )

        assertEquals(TaskBudgetUsage(null, null), attemptService.priorBudgetUsage(taskId))
    }

    @Test
    fun `report models support their documented optional defaults`() {
        assertEquals(emptyList<String>(), CreateTaskRequest("Title", "Description").acceptanceCriteria)
        assertNull(TransitionTaskRequest(TaskStatus.QUEUED).reason)
        assertEquals(emptyList<AgentDecision>(), TaskExecutionReport("Summary", "Implementation").decisions)
        assertEquals(emptyList<String>(), AgentDecision("Topic", "Choice", "Reason").alternatives)
        assertNull(ValidationCheck("Tests", outcome = CheckOutcome.SKIPPED).command)
    }

    @Test
    fun `unknown task attempt collection and attempt return not found`() {
        val missingTaskId = UUID.randomUUID()

        given()
            .`when`().get("/api/tasks/$missingTaskId/attempts")
            .then()
            .statusCode(404)

        given()
            .`when`().get("/api/tasks/$missingTaskId/attempts/${UUID.randomUUID()}")
            .then()
            .statusCode(404)

        val taskId = createTask()
        given()
            .`when`().get("/api/tasks/$taskId/attempts/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    private fun createTask(): UUID =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "title" to "Attempt test",
                    "description" to "Persist agent execution details",
                    "acceptanceCriteria" to emptyList<String>()
                )
            )
            .`when`().post("/api/tasks")
            .then()
            .statusCode(201)
            .extract().path<String>("id").let(UUID::fromString)
}
