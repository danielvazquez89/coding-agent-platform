package dev.agentplatform

enum class TaskStatus { QUEUED, RUNNING, VALIDATING, COMPLETED, FAILED, CANCELLED }

data class CreateTaskRequest(
    val title: String,
    val description: String,
    val acceptanceCriteria: List<String> = emptyList(),
    val repositoryUrl: String? = null
)

