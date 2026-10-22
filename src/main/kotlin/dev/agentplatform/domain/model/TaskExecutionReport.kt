package dev.agentplatform.domain.model

import java.time.Instant

/** The platform's synthetic question for an attempt whose criteria could not be evidenced. */
const val UNVERIFIED_ACCEPTANCE_QUESTION = "Acceptance criteria were not verified"

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

/**
 * A human's explicit acceptance of an attempt whose machine checks passed but whose model report
 * left one or more criteria unverified. The reviewer and evidence are stored in the report so the
 * completion can be audited; the model's original acceptance checks are kept separately.
 */
data class HumanReview(
    val reviewer: String,
    val evidence: String,
    val confirmedAt: Instant
)

data class ModelUsage(
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val callCount: Int,
    val durationMillis: Long,
    val estimatedCostUsd: Double?
)

data class PullRequestReference(
    val number: Int,
    val url: String,
    val title: String,
    val state: String = "open",
    val draft: Boolean = true
)

enum class ToolExecutionOutcome { STARTED, SUCCEEDED, FAILED, REJECTED }

data class ToolExecutionAudit(
    val sequenceNumber: Int,
    val toolName: String,
    val argumentsSummary: String,
    val outcome: ToolExecutionOutcome,
    val durationMillis: Long,
    val resultSummary: String
)

data class TaskExecutionReport(
    val summary: String,
    val implementation: String,
    val decisions: List<AgentDecision> = emptyList(),
    val openQuestions: List<AgentQuestion> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val validation: List<ValidationCheck> = emptyList(),
    val patchDiff: String? = null,
    /**
     * True when [patchDiff] was cut to the report diff limit and is therefore not a valid patch.
     * The stored text is still useful for review, but it must not be exported for local application.
     */
    val patchDiffTruncated: Boolean = false,
    val acceptanceChecks: List<AcceptanceCheck> = emptyList(),
    val patchFailures: List<String> = emptyList(),
    val modelUsage: ModelUsage? = null,
    val executionDurationMillis: Long? = null,
    val pullRequest: PullRequestReference? = null,
    val pullRequestError: String? = null,
    val toolExecutions: List<ToolExecutionAudit> = emptyList(),
    val maxExecutionDurationMillis: Long? = null,
    val maxEstimatedCostUsd: Double? = null,
    val executionBudgetExceeded: String? = null,
    val totalTaskExecutionDurationMillis: Long? = null,
    val totalEstimatedCostUsd: Double? = null,
    /**
     * Set only when a human confirmed the acceptance criteria after reviewing a validated attempt.
     * The top-level [acceptanceChecks] then carry the human evidence; the model's original checks
     * are preserved in [modelAcceptanceChecks].
     */
    val review: HumanReview? = null,
    val modelAcceptanceChecks: List<AcceptanceCheck>? = null
)
