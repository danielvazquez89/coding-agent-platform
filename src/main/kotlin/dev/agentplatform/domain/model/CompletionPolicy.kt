package dev.agentplatform.domain.model

/**
 * The single decision that turns an attempt's machine-observable evidence into a terminal task
 * status. It is intentionally pure: the worker supplies the facts it gathered, and this policy owns
 * the precedence so the honesty rule can be tested as a table instead of being inferred from a long
 * orchestration method.
 *
 * Precedence matters. A blocking question or validation that could not run needs a human even when a
 * model claims success. A known negative (failed criterion, failed validation, unchanged workspace)
 * fails the task rather than becoming an open question. Only after those facts does an unresolved
 * patch/write or an unevidenced criterion ask for input. Completion requires a changed workspace, a
 * passing validation, every criterion passed, and no unresolved failure.
 */
object CompletionPolicy {
    fun decide(
        validationOutcome: CheckOutcome?,
        workspaceChanged: Boolean,
        hasBlockingQuestion: Boolean,
        acceptanceChecks: List<AcceptanceCheck>,
        hasUnresolvedPatchFailure: Boolean,
        hasUnresolvedWriteFailure: Boolean
    ): TaskStatus = when {
        hasBlockingQuestion || validationOutcome == CheckOutcome.SKIPPED -> TaskStatus.NEEDS_INPUT
        acceptanceChecks.any { it.outcome == AcceptanceOutcome.FAILED } -> TaskStatus.FAILED
        validationOutcome == CheckOutcome.FAILED -> TaskStatus.FAILED
        !workspaceChanged -> TaskStatus.FAILED
        hasUnresolvedPatchFailure -> TaskStatus.NEEDS_INPUT
        hasUnresolvedWriteFailure -> TaskStatus.NEEDS_INPUT
        acceptanceChecks.any { it.outcome != AcceptanceOutcome.PASSED } -> TaskStatus.NEEDS_INPUT
        validationOutcome == CheckOutcome.PASSED -> TaskStatus.COMPLETED
        else -> TaskStatus.FAILED
    }
}
