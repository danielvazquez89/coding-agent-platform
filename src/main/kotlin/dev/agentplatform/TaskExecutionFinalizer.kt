package dev.agentplatform

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class TaskExecutionFinalizer(
    private val taskState: TaskExecutionState,
    private val attempts: AttemptOperations
) {
    @Transactional
    fun finish(
        taskId: UUID,
        attemptId: UUID,
        taskStatus: TaskStatus,
        attemptStatus: AttemptStatus,
        report: TaskExecutionReport,
        errorSummary: String?
    ) {
        require(taskStatus in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.NEEDS_INPUT))
        require(attemptStatus in setOf(AttemptStatus.COMPLETED, AttemptStatus.FAILED, AttemptStatus.NEEDS_INPUT))
        val task = taskState.findById(taskId) ?: throw IllegalStateException("Task $taskId disappeared during execution")
        require(task.status == TaskStatus.RUNNING || task.status == TaskStatus.VALIDATING) {
            "Task $taskId is no longer owned by this execution (current status: ${task.status})"
        }
        taskState.transition(taskId, taskStatus, errorSummary, "WORKER")
        checkNotNull(attempts.finish(taskId, attemptId, attemptStatus, report, errorSummary)) {
            "Attempt $attemptId disappeared during execution"
        }
    }
}
