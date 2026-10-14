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

data class TaskExecutionReport(
    val summary: String,
    val implementation: String,
    val decisions: List<AgentDecision> = emptyList(),
    val openQuestions: List<AgentQuestion> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val validation: List<ValidationCheck> = emptyList(),
    val patchDiff: String? = null
)
