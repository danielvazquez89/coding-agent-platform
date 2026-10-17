package dev.agentplatform.application.worker

import dev.agentplatform.domain.model.AgentDecision
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.domain.port.TaskExecutionState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.*

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

    /**
     * Reconciles an attempt whose execution worker disappeared (for example after a crash or restart).
     * The attempt is failed with an explicit "outcome unknown" report so the task and attempt states
     * stay consistent; it is never reported as a pass.
     */
    @Transactional
    fun failOrphaned(taskId: UUID, attemptId: UUID, reason: String) {
        val report = TaskExecutionReport(
            summary = "The execution worker stopped while this attempt was still running, so no result was recorded.",
            implementation = reason,
            decisions = listOf(
                AgentDecision(
                    "Crash recovery",
                    "Fail the abandoned attempt",
                    "An attempt whose worker disappeared cannot be treated as a pass, so its outcome stays unknown."
                )
            )
        )
        finish(taskId, attemptId, TaskStatus.FAILED, AttemptStatus.FAILED, report, reason)
    }
}
