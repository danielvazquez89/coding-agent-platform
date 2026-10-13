package dev.agentplatform

import java.time.Instant
import java.util.UUID

enum class TaskStatus { QUEUED, RUNNING, VALIDATING, COMPLETED, FAILED, CANCELLED }

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

data class ApiError(val message: String)

