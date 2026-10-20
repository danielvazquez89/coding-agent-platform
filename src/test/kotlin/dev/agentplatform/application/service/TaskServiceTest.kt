package dev.agentplatform.application.service

import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TaskStatusTransitionResponse
import dev.agentplatform.domain.model.InvalidTaskTransitionException
import dev.agentplatform.domain.model.TaskStatus
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

@QuarkusTest
class TaskServiceTest {
    @Inject
    lateinit var taskService: TaskService

    @Test
    fun `creation trims fields filters blank criteria and normalizes repository url`() {
        val task = taskService.create(
            CreateTaskRequest(
                title = "  Add tests  ",
                description = "  Cover the new behavior  ",
                acceptanceCriteria = listOf("  ", "  tests pass  ", ""),
                repositoryUrl = "  "
            )
        )

        assertEquals("Add tests", task.title)
        assertEquals("Cover the new behavior", task.description)
        assertEquals(listOf("tests pass"), task.acceptanceCriteria)
        assertNull(task.repositoryUrl)

        val withRepository = taskService.create(
            CreateTaskRequest("Task", "Description", repositoryUrl = " https://example.com/repo.git ")
        )
        assertEquals("https://example.com/repo.git", withRepository.repositoryUrl)
    }

    @Test
    fun `missing task lookups and transitions are represented as absent`() {
        val missingId = UUID.randomUUID()

        assertNull(taskService.findById(missingId))
        assertNull(taskService.transition(missingId, TaskStatus.RUNNING, "no task"))
        assertEquals(emptyList<TaskStatusTransitionResponse>(), taskService.history(missingId))
    }

    @Test
    fun `retrying a failed task clones it into a queued task linked to the source`() {
        val source = taskService.create(
            CreateTaskRequest(
                title = "Retry me",
                description = "Original description",
                acceptanceCriteria = listOf("First", "Second"),
                repositoryUrl = "https://example.com/repo.git"
            )
        )
        taskService.transition(source.id, TaskStatus.RUNNING, null, "API")
        taskService.transition(source.id, TaskStatus.FAILED, "Daily quota exhausted", "WORKER")

        val retry = taskService.retry(source.id)!!

        assertNotEquals(source.id, retry.id)
        assertEquals("Retry me", retry.title)
        assertEquals("Original description", retry.description)
        assertEquals(listOf("First", "Second"), retry.acceptanceCriteria)
        assertEquals("https://example.com/repo.git", retry.repositoryUrl)
        assertEquals(TaskStatus.QUEUED, retry.status)
        assertEquals(source.id, retry.retryOf)
        val history = taskService.history(retry.id)
        assertEquals(1, history.size)
        assertEquals("Retry of task ${source.id}", history.single().reason)
        assertEquals("API", history.single().actor)

        assertEquals(TaskStatus.FAILED, taskService.findById(source.id)!!.status)
    }

    @Test
    fun `retry rejects non-terminal tasks and reports a missing source`() {
        assertNull(taskService.retry(UUID.randomUUID()))

        val queued = taskService.create(CreateTaskRequest("Queued", "Description"))
        assertThrows(IllegalStateException::class.java) { taskService.retry(queued.id) }

        val cancelled = taskService.create(CreateTaskRequest("Cancelled", "Description"))
        taskService.transition(cancelled.id, TaskStatus.CANCELLED, null, "API")
        assertEquals(TaskStatus.QUEUED, taskService.retry(cancelled.id)!!.status)
    }

    @Test
    fun `blank transition reasons normalize to null and custom actor is retained`() {
        val task = taskService.create(CreateTaskRequest("Task", "Description"))
        taskService.transition(task.id, TaskStatus.RUNNING, "   ", actor = "WORKER")

        val event = taskService.history(task.id).last()
        assertNull(event.reason)
        assertEquals("WORKER", event.actor)
    }

    @Test
    fun `answering a needs-input task records clarification and queues another attempt`() {
        val task = taskService.create(CreateTaskRequest("Task", "Original request"))
        taskService.transition(task.id, TaskStatus.RUNNING, null)
        taskService.transition(task.id, TaskStatus.NEEDS_INPUT, "Agent needs a decision")
        assertThrows(InvalidTaskTransitionException::class.java) {
            taskService.transition(task.id, TaskStatus.QUEUED, "Bypass clarification")
        }

        val resumed = taskService.answerAndRequeue(task.id, "Use cursor pagination")

        assertEquals(TaskStatus.QUEUED, resumed!!.status)
        assertEquals("\n\nUser clarification:\nUse cursor pagination\n\nOriginal request", resumed.description)
        val history = taskService.history(task.id)
        assertEquals(TaskStatus.NEEDS_INPUT, history[3].fromStatus)
        assertEquals(TaskStatus.QUEUED, history[3].toStatus)
        assertEquals("API", history[3].actor)
        assertNull(taskService.answerAndRequeue(UUID.randomUUID(), "answer"))
    }

    @Test
    fun `answering rejects blank answers and tasks not awaiting input`() {
        val task = taskService.create(CreateTaskRequest("Task", "Description"))

        assertThrows(IllegalArgumentException::class.java) { taskService.answerAndRequeue(task.id, "  ") }
        assertThrows(IllegalArgumentException::class.java) {
            taskService.answerAndRequeue(task.id, "x".repeat(5001))
        }
        assertThrows(IllegalStateException::class.java) { taskService.answerAndRequeue(task.id, "answer") }
    }

    @Test
    fun `answering rejects clarifications that exceed the bounded task prompt size`() {
        val task = taskService.create(CreateTaskRequest("Task", "x".repeat(50_000)))
        taskService.transition(task.id, TaskStatus.RUNNING, null)
        taskService.transition(task.id, TaskStatus.NEEDS_INPUT, null)

        assertThrows(IllegalArgumentException::class.java) {
            taskService.answerAndRequeue(task.id, "additional context")
        }
        assertEquals(TaskStatus.NEEDS_INPUT, taskService.findById(task.id)!!.status)
    }

    @Test
    fun `worker claims queued tasks once and records the worker transition`() {
        taskService.findAll().filter { it.status == TaskStatus.QUEUED }
            .forEach { taskService.transition(it.id, TaskStatus.CANCELLED, "test cleanup") }
        assertNull(taskService.claim(UUID.randomUUID()))
        assertNull(taskService.claimNextQueued())
        val first = taskService.create(CreateTaskRequest("First", "Description"))
        val second = taskService.create(CreateTaskRequest("Second", "Description"))

        val claimed = taskService.claimNextQueued()!!
        assertTrue(claimed.id == first.id || claimed.id == second.id)
        assertEquals(TaskStatus.RUNNING, claimed.status)
        assertNull(taskService.claim(claimed.id))
        val remaining = listOf(first.id, second.id).single { it != claimed.id }
        assertEquals(remaining, taskService.claim(remaining)?.id)
        assertNull(taskService.claimNextQueued())

        val history = taskService.history(claimed.id)
        assertEquals(2, history.size)
        assertEquals("WORKER", history.last().actor)
        assertEquals(TaskStatus.QUEUED, history.last().fromStatus)
        assertEquals(TaskStatus.RUNNING, history.last().toStatus)
    }
}
