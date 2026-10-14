package dev.agentplatform.application.service

import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TaskStatusTransitionResponse
import dev.agentplatform.domain.model.TaskStatus
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

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
    fun `blank transition reasons normalize to null and custom actor is retained`() {
        val task = taskService.create(CreateTaskRequest("Task", "Description"))
        taskService.transition(task.id, TaskStatus.RUNNING, "   ", actor = "WORKER")

        val event = taskService.history(task.id).last()
        assertNull(event.reason)
        assertEquals("WORKER", event.actor)
    }

    @Test
    fun `worker claims queued tasks once and records the worker transition`() {
        taskService.findAll().filter { it.status == TaskStatus.QUEUED }
            .forEach { taskService.transition(it.id, TaskStatus.CANCELLED, "test cleanup") }
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
