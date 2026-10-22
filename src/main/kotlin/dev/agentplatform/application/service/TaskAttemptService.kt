package dev.agentplatform.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.api.dto.TaskAttemptResponse
import dev.agentplatform.domain.model.AcceptanceCheck
import dev.agentplatform.domain.model.AcceptanceOutcome
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.HumanReview
import dev.agentplatform.domain.model.TaskAttempt
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.model.ToolExecutionAudit
import dev.agentplatform.domain.model.ToolExecutionEvent
import dev.agentplatform.domain.model.UNVERIFIED_ACCEPTANCE_QUESTION
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
    private val objectMapper: ObjectMapper,
    private val taskService: TaskService
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
                    else Math.addExact(elapsedMillis, duration)

                    val cost = json.path("modelUsage").path("estimatedCostUsd")
                        .takeIf { it.isNumber }?.asDouble()
                    estimatedCostUsd = if (cost == null || estimatedCostUsd == null) null
                    else estimatedCostUsd + cost
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
    override fun runningAttemptsStartedBefore(startedBefore: Instant): List<TaskAttempt> =
        attemptRepository.runningStartedBefore(startedBefore)

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

    /**
     * Completes a NEEDS_INPUT attempt after a human reviewed the stored diff and validation and
     * explicitly confirmed the acceptance criteria. Machine checks are not bypassed: the attempt
     * must have changed files, every supported validation check must have passed, the stored diff
     * must be complete, no criterion may have been reported FAILED, and no model blocking question
     * may remain. The top-level acceptance checks become the human's evidence; the model's original
     * checks are kept under `modelAcceptanceChecks` for audit.
     *
     * Returns null when the task or attempt does not exist. Throws [IllegalStateException] when the
     * attempt is not eligible, which the API surfaces as HTTP 409.
     */
    @Transactional
    fun confirmAcceptance(
        taskId: UUID,
        attemptId: UUID,
        evidence: String,
        reviewer: String?
    ): TaskAttemptResponse? {
        val normalizedEvidence = evidence.trim()
        require(normalizedEvidence.isNotEmpty()) { "evidence must not be blank" }
        require(normalizedEvidence.length <= MAX_CONFIRMATION_EVIDENCE_LENGTH) {
            "evidence must be $MAX_CONFIRMATION_EVIDENCE_LENGTH characters or fewer"
        }
        val normalizedReviewer = (reviewer?.trim()?.takeIf(String::isNotEmpty) ?: "operator")
            .take(MAX_REVIEWER_LENGTH)

        val task = taskRepository.findById(taskId) ?: return null
        val attempt = attemptRepository.findForUpdate(attemptId)?.takeIf { it.taskId == taskId }
            ?: return null
        check(attempt.status == AttemptStatus.NEEDS_INPUT) {
            "Attempt $attemptId is ${attempt.status}; only a NEEDS_INPUT attempt can be confirmed"
        }
        check(task.status == TaskStatus.NEEDS_INPUT) {
            "Task $taskId is ${task.status}; only a NEEDS_INPUT task can be confirmed"
        }
        val report = attempt.reportJson?.let { objectMapper.readValue(it, TaskExecutionReport::class.java) }
            ?: throw IllegalStateException("Attempt $attemptId has no execution report to confirm")
        check(report.changedFiles.isNotEmpty()) {
            "Attempt $attemptId did not change any files; there is nothing to confirm"
        }
        check(report.validation.isNotEmpty()) {
            "Attempt $attemptId has no validation checks; a patch cannot be confirmed without them"
        }
        check(report.validation.all { it.outcome == CheckOutcome.PASSED }) {
            "Every validation check must have passed before a human can confirm the attempt"
        }
        check(!report.patchDiff.isNullOrBlank()) {
            "Attempt $attemptId has no patch diff to confirm"
        }
        check(!report.patchDiffTruncated) {
            "Attempt $attemptId produced a truncated patch diff, so it cannot be confirmed for local " +
                "application; use the stored report or retry the task"
        }
        check(report.acceptanceChecks.none { it.outcome == AcceptanceOutcome.FAILED }) {
            "One or more acceptance criteria were reported as not met; retry the task instead"
        }
        check(report.openQuestions.none { it.blocking && it.question != UNVERIFIED_ACCEPTANCE_QUESTION }) {
            "The attempt has a blocking question; answer it instead of confirming acceptance"
        }

        val humanEvidence = "Human confirmation: $normalizedEvidence"
        val confirmed = report.copy(
            acceptanceChecks = task.acceptanceCriteria.map {
                AcceptanceCheck(it, AcceptanceOutcome.PASSED, humanEvidence)
            },
            modelAcceptanceChecks = report.acceptanceChecks,
            openQuestions = report.openQuestions.filterNot { it.question == UNVERIFIED_ACCEPTANCE_QUESTION },
            review = HumanReview(normalizedReviewer, normalizedEvidence, Instant.now())
        )
        attempt.status = AttemptStatus.COMPLETED
        attempt.errorSummary = null
        attempt.reportJson = objectMapper.writeValueAsString(confirmed)
        attemptRepository.flush()

        taskService.completeAfterReview(taskId, normalizedReviewer, normalizedEvidence)
        return toResponse(attempt)
    }

    /**
     * Returns the patch diff an attempt produced when it is safe to apply locally, or null when the
     * task or attempt does not exist. The "safe to export" rules live here (not in a client) so every
     * consumer is guarded identically: the task and attempt must be COMPLETED, every validation check
     * must have passed, and the report must contain exactly one passing, evidenced acceptance check
     * for every task criterion. When the attempt exists but is not exportable this throws
     * [IllegalStateException] with the reason, which the API surfaces as HTTP 409.
     */
    @Transactional
    fun exportablePatch(taskId: UUID, attemptId: UUID): String? {
        val task = taskRepository.findById(taskId) ?: return null
        val attempt = attemptRepository.findById(attemptId)?.takeIf { it.taskId == taskId } ?: return null
        check(task.status == TaskStatus.COMPLETED) {
            "Task $taskId is ${task.status}; only a COMPLETED task has a patch that can be applied locally"
        }
        check(attempt.status == AttemptStatus.COMPLETED) {
            "Attempt $attemptId is ${attempt.status}; only a COMPLETED attempt has a patch that can be applied locally"
        }
        val report = attempt.reportJson?.let(objectMapper::readTree)
            ?: throw IllegalStateException("Attempt $attemptId has no execution report to export")

        val validation = report.path("validation")
        check(validation.isArray && validation.size() > 0) {
            "Attempt $attemptId has no validation checks, so its patch cannot be trusted"
        }
        check(validation.all { it.path("outcome").asText() == CheckOutcome.PASSED.name }) {
            "Every validation check must have passed before a patch can be applied locally"
        }

        val criteria = task.acceptanceCriteria
        val acceptance = report.path("acceptanceChecks")
        check(acceptance.isArray && acceptance.size() == criteria.size) {
            "The attempt must contain exactly one acceptance check for every task criterion"
        }
        criteria.forEachIndexed { index, criterion ->
            val entry = acceptance.get(index)
            check(entry.path("criterion").asText() == criterion) {
                "The attempt acceptance checks must match the task criteria in order"
            }
            check(entry.path("outcome").asText() == AcceptanceOutcome.PASSED.name) {
                "Acceptance criterion '${criterion.take(120)}' did not pass"
            }
            check(entry.path("evidence").asText().isNotBlank()) {
                "Acceptance criterion '${criterion.take(120)}' has no evidence"
            }
        }

        val diff = report.path("patchDiff").takeIf { it.isTextual }?.asText()
        check(!diff.isNullOrBlank()) { "Attempt $attemptId has no patch diff to export" }
        check(!report.path("patchDiffTruncated").asBoolean(false)) {
            "Attempt $attemptId produced a patch larger than the report diff limit; it was stored truncated, " +
                "so it cannot be applied locally. Use the draft pull request instead"
        }
        return diff
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

    private companion object {
        const val MAX_CONFIRMATION_EVIDENCE_LENGTH = 2000
        const val MAX_REVIEWER_LENGTH = 100
    }
}
