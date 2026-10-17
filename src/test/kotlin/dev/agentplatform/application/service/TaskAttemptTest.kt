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
import java.time.Instant
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
        )!!

        assertEquals(1, first.attemptNumber)
        assertEquals(2, second.attemptNumber)
        assertNotNull(finishedFirst.finishedAt)
        assertEquals(
            4500,
            attemptService.find(taskId, second.id)?.report?.path("executionDurationMillis")?.asInt()
        )
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
    fun `prior budget becomes unknown when finished reports omit usage fields`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")

        val durationMissing = attemptService.start(taskId)!!
        attemptService.finish(
            taskId,
            durationMissing.id,
            AttemptStatus.FAILED,
            TaskExecutionReport(
                summary = "No duration",
                implementation = "The provider omitted duration.",
                executionDurationMillis = null,
                modelUsage = ModelUsage("qwen3:8b", null, null, 0, 0, 0.01)
            )
        )
        assertEquals(TaskBudgetUsage(null, 0.01), attemptService.priorBudgetUsage(taskId))

        val costMissing = attemptService.start(taskId)!!
        attemptService.finish(
            taskId,
            costMissing.id,
            AttemptStatus.FAILED,
            TaskExecutionReport(
                summary = "No cost",
                implementation = "The provider omitted cost.",
                executionDurationMillis = 10,
                modelUsage = ModelUsage("qwen3:8b", null, null, 0, 0, null)
            )
        )
        assertEquals(TaskBudgetUsage(null, null), attemptService.priorBudgetUsage(taskId))
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
            .body("[0].durationMillis", equalTo(24))
            .body("[0].resultSummary", equalTo("Returned 120 characters"))
    }

    @Test
    fun `tool event audit operations reject missing mismatched and already completed records`() {
        val taskId = createTask()
        val otherTaskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!
        val audit = ToolExecutionAudit(
            sequenceNumber = 1,
            toolName = "read_file",
            argumentsSummary = "path=README.md",
            outcome = ToolExecutionOutcome.STARTED,
            durationMillis = 0,
            resultSummary = "Started"
        )

        assertThrows(IllegalStateException::class.java) {
            attemptService.recordToolExecution(taskId, UUID.randomUUID(), audit)
        }
        assertThrows(IllegalStateException::class.java) {
            attemptService.recordToolExecution(otherTaskId, attempt.id, audit)
        }
        assertThrows(IllegalStateException::class.java) {
            attemptService.recordToolExecution(taskId, attempt.id, audit.copy(sequenceNumber = 2))
        }
        attemptService.recordToolExecution(taskId, attempt.id, audit)
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(taskId, UUID.randomUUID(), audit)
        }
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(otherTaskId, attempt.id, audit)
        }
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(taskId, attempt.id, audit.copy(sequenceNumber = 2))
        }
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(
                taskId,
                attempt.id,
                audit.copy(outcome = ToolExecutionOutcome.STARTED)
            )
        }

        attemptService.finishToolExecution(
            taskId,
            attempt.id,
            audit.copy(outcome = ToolExecutionOutcome.SUCCEEDED)
        )
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(
                taskId,
                attempt.id,
                audit.copy(outcome = ToolExecutionOutcome.FAILED)
            )
        }
        attemptService.finish(taskId, attempt.id, AttemptStatus.COMPLETED, null)
        assertThrows(IllegalStateException::class.java) {
            attemptService.finishToolExecution(
                taskId,
                attempt.id,
                audit.copy(outcome = ToolExecutionOutcome.FAILED)
            )
        }
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

    @Test
    fun `lists running attempts started before a cutoff`() {
        val taskId = createTask()
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!

        assertTrue(
            attemptService.runningAttemptsStartedBefore(Instant.now().plusSeconds(60)).any { it.id == attempt.id }
        )
        assertTrue(
            attemptService.runningAttemptsStartedBefore(Instant.now().minusSeconds(60)).none { it.id == attempt.id }
        )

        attemptService.finish(taskId, attempt.id, AttemptStatus.FAILED, null, "worker stopped")
        assertTrue(
            attemptService.runningAttemptsStartedBefore(Instant.now().plusSeconds(60)).none { it.id == attempt.id }
        )
    }

    @Test
    fun `patch export enforces completed status passed checks and evidenced acceptance`() {
        val criterion = "Add a greeting endpoint"
        val validReport = TaskExecutionReport(
            summary = "Added the greeting endpoint",
            implementation = "A greeting resource was added.",
            changedFiles = listOf("Greeting.kt"),
            validation = listOf(ValidationCheck("Maven tests", "mvn test", CheckOutcome.PASSED, 0, "ok")),
            acceptanceChecks = listOf(AcceptanceCheck(criterion, AcceptanceOutcome.PASSED, "GreetingTest passed")),
            patchDiff = "diff --git a/Greeting.kt b/Greeting.kt"
        )

        val (taskId, attemptId) = completedAttempt(listOf(criterion), validReport)
        assertEquals("diff --git a/Greeting.kt b/Greeting.kt", attemptService.exportablePatch(taskId, attemptId))

        assertNull(attemptService.exportablePatch(UUID.randomUUID(), attemptId))
        assertNull(attemptService.exportablePatch(taskId, UUID.randomUUID()))
        assertNull(attemptService.exportablePatch(createTask(), attemptId))

        val (runningTask, runningAttempt) =
            completedAttempt(listOf(criterion), validReport, taskStatus = TaskStatus.RUNNING)
        assertExportRejected(runningTask, runningAttempt, "only a COMPLETED task")

        val (failedAttemptTask, failedAttempt) =
            completedAttempt(listOf(criterion), validReport, attemptStatus = AttemptStatus.FAILED)
        assertExportRejected(failedAttemptTask, failedAttempt, "only a COMPLETED attempt")

        assertExportRejected(listOf(criterion), null, "has no execution report")
        assertExportRejected(listOf(criterion), validReport.copy(validation = emptyList()), "no validation checks")
        assertExportRejected(
            listOf(criterion),
            validReport.copy(
                validation = listOf(ValidationCheck("Maven tests", outcome = CheckOutcome.FAILED, exitCode = 1))
            ),
            "must have passed"
        )
        assertExportRejected(listOf(criterion), validReport.copy(patchDiff = "  "), "no patch diff")
        assertExportRejected(
            listOf(criterion),
            validReport.copy(patchDiffTruncated = true),
            "larger than the report diff limit"
        )

        assertExportRejected(listOf(criterion, "Second criterion"), validReport, "exactly one acceptance check")
        assertExportRejected(
            listOf("First criterion", "Second criterion"),
            validReport.copy(
                acceptanceChecks = listOf(
                    AcceptanceCheck("Second criterion", AcceptanceOutcome.PASSED, "evidence"),
                    AcceptanceCheck("First criterion", AcceptanceOutcome.PASSED, "evidence")
                )
            ),
            "match the task criteria in order"
        )
        assertExportRejected(
            listOf(criterion),
            validReport.copy(acceptanceChecks = listOf(AcceptanceCheck(criterion, AcceptanceOutcome.FAILED, "evidence"))),
            "did not pass"
        )
        assertExportRejected(
            listOf(criterion),
            validReport.copy(acceptanceChecks = listOf(AcceptanceCheck(criterion, AcceptanceOutcome.PASSED, " "))),
            "has no evidence"
        )
    }

    private fun completedAttempt(
        criteria: List<String>,
        report: TaskExecutionReport?,
        taskStatus: TaskStatus = TaskStatus.COMPLETED,
        attemptStatus: AttemptStatus = AttemptStatus.COMPLETED
    ): Pair<UUID, UUID> {
        val taskId = createTask(criteria)
        taskService.transition(taskId, TaskStatus.RUNNING, "Worker claimed task")
        val attempt = attemptService.start(taskId)!!
        attemptService.finish(taskId, attempt.id, attemptStatus, report, null)
        if (taskStatus == TaskStatus.COMPLETED) {
            taskService.transition(taskId, TaskStatus.VALIDATING, "Validation started")
            taskService.transition(taskId, TaskStatus.COMPLETED, "All checks passed")
        }
        return taskId to attempt.id
    }

    private fun assertExportRejected(criteria: List<String>, report: TaskExecutionReport?, messagePart: String) {
        val (taskId, attemptId) = completedAttempt(criteria, report)
        assertExportRejected(taskId, attemptId, messagePart)
    }

    private fun assertExportRejected(taskId: UUID, attemptId: UUID, messagePart: String) {
        val failure = assertThrows(IllegalStateException::class.java) {
            attemptService.exportablePatch(taskId, attemptId)
        }
        assertTrue(failure.message!!.contains(messagePart)) {
            "Expected '${failure.message}' to contain '$messagePart'"
        }
    }

    private fun createTask(criteria: List<String>): UUID =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "title" to "Patch export test",
                    "description" to "Export and apply an attempt patch",
                    "acceptanceCriteria" to criteria
                )
            )
            .`when`().post("/api/tasks")
            .then()
            .statusCode(201)
            .extract().path<String>("id").let(UUID::fromString)

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
