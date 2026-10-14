package dev.agentplatform.application.service

import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TransitionTaskRequest
import dev.agentplatform.domain.model.AgentDecision
import dev.agentplatform.domain.model.AgentQuestion
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.model.ValidationCheck
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

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
            patchDiff = "diff --git a/src/main/kotlin/RetryPolicy.kt b/src/main/kotlin/RetryPolicy.kt"
        )
        val finishedFirst = attemptService.finish(taskId, first.id, AttemptStatus.COMPLETED, report)!!

        val second = attemptService.start(taskId)!!
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
        assertNull(attemptService.start(UUID.randomUUID()))
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
