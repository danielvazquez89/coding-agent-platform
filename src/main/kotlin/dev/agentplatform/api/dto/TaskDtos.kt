package dev.agentplatform.api.dto

import com.fasterxml.jackson.databind.JsonNode
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.TaskStatus
import java.time.Instant
import java.util.UUID

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
