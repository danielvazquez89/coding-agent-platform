package dev.agentplatform

import java.time.Instant
import java.util.UUID
import com.fasterxml.jackson.databind.JsonNode

enum class TaskStatus { QUEUED, RUNNING, VALIDATING, COMPLETED, FAILED, NEEDS_INPUT, CANCELLED }

data class CreateTaskRequest(
    val title: String,
    val description: String,
    val acceptanceCriteria: List<String> = emptyList(),
    val repositoryUrl: String? = null
)

data class TransitionTaskRequest(
    val status: TaskStatus,
    val reason: String? = null
)

data class TaskStatusTransitionResponse(
    val sequenceNumber: Int,
    val taskId: UUID,
    val fromStatus: TaskStatus?,
    val toStatus: TaskStatus,
    val reason: String?,
    val actor: String,
    val transitionedAt: Instant
)

data class AgentDecision(
    val topic: String,
    val choice: String,
    val rationale: String,
    val alternatives: List<String> = emptyList()
)

data class AgentQuestion(
    val question: String,
    val impact: String,
    val blocking: Boolean
)

enum class CheckOutcome { PASSED, FAILED, SKIPPED }

data class ValidationCheck(
    val name: String,
    val command: String? = null,
    val outcome: CheckOutcome,
    val exitCode: Int? = null,
    val details: String? = null,
    val durationMillis: Long? = null
)

data class TaskExecutionReport(
    val summary: String,
    val implementation: String,
    val decisions: List<AgentDecision> = emptyList(),
    val openQuestions: List<AgentQuestion> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val validation: List<ValidationCheck> = emptyList(),
    val patchDiff: String? = null
)

data class TaskAttemptResponse(
    val id: UUID,
    val taskId: UUID,
    val attemptNumber: Int,
    val status: AttemptStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorSummary: String?,
    val report: JsonNode?
)

data class ApiError(val message: String)

