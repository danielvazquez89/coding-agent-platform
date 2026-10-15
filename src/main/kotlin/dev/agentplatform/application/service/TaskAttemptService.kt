package dev.agentplatform.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.api.dto.TaskAttemptResponse
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.TaskAttempt
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.model.ToolExecutionAudit
import dev.agentplatform.domain.model.ToolExecutionEvent
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.domain.port.TaskBudgetUsage
import dev.agentplatform.infrastructure.persistence.TaskAttemptRepository
import dev.agentplatform.infrastructure.persistence.TaskRepository
import dev.agentplatform.infrastructure.persistence.ToolExecutionEventRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.*

@ApplicationScoped
class TaskAttemptService(
    private val taskRepository: TaskRepository,
    private val attemptRepository: TaskAttemptRepository,
    private val toolExecutionEventRepository: ToolExecutionEventRepository,
    private val objectMapper: ObjectMapper
) : AttemptOperations {
    @Transactional
    override fun priorBudgetUsage(taskId: UUID): TaskBudgetUsage {
        var elapsedMillis: Long? = 0
        var estimatedCostUsd: Double? = 0.0
        attemptRepository.forTaskInOrder(taskId)
            .filter { it.status != AttemptStatus.RUNNING }
            .forEach { attempt ->
                val json = attempt.reportJson?.let(objectMapper::readTree)
                if (json == null) {
                    elapsedMillis = null
                    estimatedCostUsd = null
                } else {
                    val duration = json.path("executionDurationMillis")
                        .takeIf { it.isNumber }?.asLong()
                    elapsedMillis = if (duration == null || elapsedMillis == null) null
                    else Math.addExact(elapsedMillis!!, duration)

                    val cost = json.path("modelUsage").path("estimatedCostUsd")
                        .takeIf { it.isNumber }?.asDouble()
                    estimatedCostUsd = if (cost == null || estimatedCostUsd == null) null
                    else estimatedCostUsd!! + cost
                }
            }
        return TaskBudgetUsage(elapsedMillis, estimatedCostUsd)
    }

    @Transactional
    override fun recordToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {
        val attempt = attemptRepository.findForUpdate(attemptId)
            ?: throw IllegalStateException("Attempt $attemptId does not exist")
        check(attempt.taskId == taskId && attempt.status == AttemptStatus.RUNNING) {
            "Tool execution can only be recorded for a running attempt belonging to task $taskId"
        }
        val expectedSequence = toolExecutionEventRepository.nextSequenceNumber(attemptId)
        check(audit.sequenceNumber == expectedSequence) {
            "Tool event sequence ${audit.sequenceNumber} does not match expected sequence $expectedSequence"
        }
        toolExecutionEventRepository.persist(
            ToolExecutionEvent(
                taskId = taskId,
                attemptId = attemptId,
                sequenceNumber = audit.sequenceNumber,
                toolName = audit.toolName.take(100),
                argumentsSummary = audit.argumentsSummary.take(500),
                outcome = audit.outcome,
                durationMillis = audit.durationMillis.coerceAtLeast(0),
                resultSummary = audit.resultSummary.take(300),
                recordedAt = Instant.now()
            )
        )
    }

    @Transactional
    override fun finishToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {
        val attempt = attemptRepository.findForUpdate(attemptId)
            ?: throw IllegalStateException("Attempt $attemptId does not exist")
        check(attempt.taskId == taskId && attempt.status == AttemptStatus.RUNNING) {
            "Tool execution can only be completed for a running attempt belonging to task $taskId"
        }
        val event = toolExecutionEventRepository.findForAttemptSequence(attemptId, audit.sequenceNumber)
            ?: throw IllegalStateException("Tool event ${audit.sequenceNumber} does not exist")
        check(event.taskId == taskId && event.outcome == dev.agentplatform.domain.model.ToolExecutionOutcome.STARTED) {
            "Tool event ${audit.sequenceNumber} is not a pending execution for task $taskId"
        }
        check(audit.outcome != dev.agentplatform.domain.model.ToolExecutionOutcome.STARTED) {
            "A tool execution completion must have a terminal outcome"
        }
        event.outcome = audit.outcome
        event.durationMillis = audit.durationMillis.coerceAtLeast(0)
        event.resultSummary = audit.resultSummary.take(300)
        toolExecutionEventRepository.flush()
    }

    @Transactional
    override fun toolExecutions(taskId: UUID, attemptId: UUID): List<ToolExecutionAudit> =
        toolExecutionEventRepository.forAttemptInOrder(taskId, attemptId).map {
            ToolExecutionAudit(
                sequenceNumber = it.sequenceNumber,
                toolName = it.toolName,
                argumentsSummary = it.argumentsSummary,
                outcome = it.outcome,
                durationMillis = it.durationMillis,
                resultSummary = it.resultSummary
            )
        }

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

        val finishedAt = Instant.now()
        val boundedError = errorSummary?.take(4000)
        val reportJson = report?.let(objectMapper::writeValueAsString)
        attempt.status = status
        attempt.finishedAt = finishedAt
        attempt.errorSummary = boundedError
        attempt.reportJson = reportJson
        attemptRepository.flush()
        return attempt
    }

    @Transactional
    fun list(taskId: UUID): List<TaskAttemptResponse> = attemptRepository.forTaskInOrder(taskId).map(::toResponse)

    @Transactional
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
