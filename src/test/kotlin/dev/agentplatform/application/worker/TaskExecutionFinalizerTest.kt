package dev.agentplatform.application.worker

import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.CodingTask
import dev.agentplatform.domain.model.TaskAttempt
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.domain.port.TaskExecutionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TaskExecutionFinalizerTest {
    @Test
    fun `rejects invalid final states and attempts or a cancelled task`() {
        val task = CodingTask(
            id = UUID.randomUUID(),
            title = "Task",
            description = "Description",
            status = TaskStatus.CANCELLED
        )
        val state = State(task)
        val attempts = Attempts()
        val finalizer = TaskExecutionFinalizer(state, attempts)

        assertThrows(IllegalArgumentException::class.java) {
            finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.COMPLETED, AttemptStatus.COMPLETED, report(), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.FAILED, AttemptStatus.RUNNING, report(), null)
        }
        assertThrows(IllegalStateException::class.java) {
            finalizer.finish(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.FAILED, AttemptStatus.FAILED, report(), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.FAILED, AttemptStatus.FAILED, report(), null)
        }
    }

    @Test
    fun `rejects missing and no longer owned tasks before finishing the attempt`() {
        val task =
            CodingTask(id = UUID.randomUUID(), title = "Task", description = "Description", status = TaskStatus.QUEUED)
        val attempts = Attempts()
        val finalizer = TaskExecutionFinalizer(State(task), attempts)

        assertThrows(IllegalArgumentException::class.java) {
            finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.FAILED, AttemptStatus.FAILED, report(), null)
        }
        assertEquals(null, attempts.finished)
    }

    private fun report() = TaskExecutionReport("summary", "implementation")

    private class State(private val task: CodingTask?) : TaskExecutionState {
        override fun claimNextQueued() = null
        override fun claim(id: UUID) = null
        override fun findById(id: UUID) = task?.takeIf { it.id == id }
        override fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String): CodingTask? = null
    }

    private class Attempts : AttemptOperations {
        var finished: TaskAttempt? = null
        override fun start(taskId: UUID) = null
        override fun finish(
            taskId: UUID, attemptId: UUID, status: AttemptStatus, report: TaskExecutionReport?, errorSummary: String?
        ): TaskAttempt? {
            val attempt = TaskAttempt(
                id = attemptId,
                taskId = taskId,
                attemptNumber = 1,
                status = status,
                startedAt = Instant.now()
            )
            finished = attempt
            return attempt
        }
    }
}
