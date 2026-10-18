package dev.agentplatform.application.worker

import dev.agentplatform.domain.model.*
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.domain.port.ExecutionMetrics
import dev.agentplatform.domain.port.TaskBudgetUsage
import dev.agentplatform.domain.port.TaskExecutionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class TaskExecutionFinalizerTest {
    @Test
    fun `attempt operations default prior budget is empty`() {
        val operations = object : AttemptOperations {
            override fun recordToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) = Unit
            override fun finishToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) = Unit
            override fun toolExecutions(taskId: UUID, attemptId: UUID) = emptyList<ToolExecutionAudit>()
            override fun start(taskId: UUID) = null
            override fun finish(
                taskId: UUID, attemptId: UUID, status: AttemptStatus,
                report: TaskExecutionReport?, errorSummary: String?
            ) = null
        }

        assertEquals(TaskBudgetUsage(), operations.priorBudgetUsage(UUID.randomUUID()))
    }

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
        val finalizer = TaskExecutionFinalizer(state, attempts, ExecutionMetrics.NONE)

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
        val finalizer = TaskExecutionFinalizer(State(task), attempts, ExecutionMetrics.NONE)

        assertThrows(IllegalArgumentException::class.java) {
            finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.FAILED, AttemptStatus.FAILED, report(), null)
        }
        assertEquals(null, attempts.finished)
    }

    @Test
    fun `fails finalization when the attempt disappears after the task transition`() {
        val task = CodingTask(
            id = UUID.randomUUID(),
            title = "Task",
            description = "Description",
            status = TaskStatus.RUNNING
        )
        val finalizer = TaskExecutionFinalizer(State(task), Attempts(returnMissing = true), ExecutionMetrics.NONE)

        assertThrows(IllegalStateException::class.java) {
            finalizer.finish(
                task.id,
                UUID.randomUUID(),
                TaskStatus.FAILED,
                AttemptStatus.FAILED,
                report(),
                "execution failed"
            )
        }
        assertEquals(TaskStatus.FAILED, task.status)
    }

    @Test
    fun `records the terminal status after it is persisted`() {
        val task = CodingTask(
            id = UUID.randomUUID(),
            title = "Task",
            description = "Description",
            status = TaskStatus.RUNNING
        )
        val attempts = Attempts()
        val statuses = mutableListOf<String>()
        val finalizer = TaskExecutionFinalizer(State(task), attempts, object : ExecutionMetrics {
            override fun taskFinished(status: String) {
                statuses += status
            }
        })

        finalizer.finish(task.id, UUID.randomUUID(), TaskStatus.COMPLETED, AttemptStatus.COMPLETED, report(), null)

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("COMPLETED", attempts.finished?.status?.name)
        assertEquals(listOf("COMPLETED"), statuses)
    }

    private fun report() = TaskExecutionReport("summary", "implementation")

    private class State(private val task: CodingTask?) : TaskExecutionState {
        override fun claimNextQueued() = null
        override fun claim(id: UUID) = null
        override fun findById(id: UUID) = task?.takeIf { it.id == id }
        override fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String): CodingTask? {
            val current = task?.takeIf { it.id == id } ?: return null
            current.status = target
            return current
        }
    }

    private class Attempts(private val returnMissing: Boolean = false) : AttemptOperations {
        var finished: TaskAttempt? = null
        override fun priorBudgetUsage(taskId: UUID) = TaskBudgetUsage()
        override fun recordToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {}
        override fun finishToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {}
        override fun toolExecutions(taskId: UUID, attemptId: UUID) = emptyList<ToolExecutionAudit>()
        override fun start(taskId: UUID) = null
        override fun finish(
            taskId: UUID, attemptId: UUID, status: AttemptStatus, report: TaskExecutionReport?, errorSummary: String?
        ): TaskAttempt? {
            if (returnMissing) return null
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
