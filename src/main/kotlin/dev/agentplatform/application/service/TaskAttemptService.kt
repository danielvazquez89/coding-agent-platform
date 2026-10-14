package dev.agentplatform.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.api.dto.TaskAttemptResponse
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.TaskAttempt
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.infrastructure.persistence.TaskAttemptRepository
import dev.agentplatform.infrastructure.persistence.TaskRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class TaskAttemptService(
    private val taskRepository: TaskRepository,
    private val attemptRepository: TaskAttemptRepository,
    private val objectMapper: ObjectMapper
) : AttemptOperations {
    /** Called by the execution worker after it has durably claimed a RUNNING task. */
    @Transactional
    override fun start(taskId: UUID): TaskAttempt? {
        val task = taskRepository.findForUpdate(taskId) ?: return null
        check(task.status == TaskStatus.RUNNING) {
            "An attempt can only start while its task is RUNNING (current status: ${task.status})"
        }
        check(!attemptRepository.hasRunningAttempt(taskId)) { "Task $taskId already has a running attempt" }

        return TaskAttempt(
            taskId = taskId,
            attemptNumber = attemptRepository.nextAttemptNumber(taskId),
            status = AttemptStatus.RUNNING
        ).also(attemptRepository::persist)
    }

    @Transactional
    override fun finish(
        taskId: UUID,
        attemptId: UUID,
        status: AttemptStatus,
        report: TaskExecutionReport?,
        errorSummary: String?
    ): TaskAttempt? {
        require(status != AttemptStatus.RUNNING) { "A finished attempt must have a terminal status" }
        val attempt = attemptRepository.findForUpdate(attemptId) ?: return null
        if (attempt.taskId != taskId) return null
        check(attempt.status == AttemptStatus.RUNNING) { "Attempt ${attempt.id} is already finished" }

        attempt.status = status
        attempt.finishedAt = Instant.now()
        attempt.errorSummary = errorSummary?.take(4000)
        attempt.reportJson = report?.let(objectMapper::writeValueAsString)
        return attempt
    }

    fun list(taskId: UUID): List<TaskAttemptResponse> = attemptRepository.forTaskInOrder(taskId).map(::toResponse)

    fun find(taskId: UUID, attemptId: UUID): TaskAttemptResponse? =
        attemptRepository.findById(attemptId)
            ?.takeIf { it.taskId == taskId }
            ?.let(::toResponse)

    private fun toResponse(attempt: TaskAttempt) = TaskAttemptResponse(
        id = attempt.id,
        taskId = attempt.taskId,
        attemptNumber = attempt.attemptNumber,
        status = attempt.status,
        startedAt = attempt.startedAt,
        finishedAt = attempt.finishedAt,
        errorSummary = attempt.errorSummary,
        report = attempt.reportJson?.let(objectMapper::readTree)
    )
}
