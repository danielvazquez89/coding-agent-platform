package dev.agentplatform.domain.model

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

enum class AcceptanceOutcome { PASSED, FAILED, UNVERIFIED }

data class AcceptanceCheck(
    val criterion: String,
    val outcome: AcceptanceOutcome,
    val evidence: String? = null
)

data class ModelUsage(
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val callCount: Int,
    val durationMillis: Long,
    val estimatedCostUsd: Double?
)

data class TaskExecutionReport(
    val summary: String,
    val implementation: String,
    val decisions: List<AgentDecision> = emptyList(),
    val openQuestions: List<AgentQuestion> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val validation: List<ValidationCheck> = emptyList(),
    val patchDiff: String? = null,
    val acceptanceChecks: List<AcceptanceCheck> = emptyList(),
    val patchFailures: List<String> = emptyList(),
    val modelUsage: ModelUsage? = null,
    val executionDurationMillis: Long? = null
)
