package dev.agentplatform.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompletionPolicyTest {
    private val passed = AcceptanceCheck("criterion", AcceptanceOutcome.PASSED)
    private val unverified = AcceptanceCheck("criterion", AcceptanceOutcome.UNVERIFIED)
    private val failed = AcceptanceCheck("criterion", AcceptanceOutcome.FAILED)

    @Test
    fun `matches the completion decision table and its precedence`() {
        val cases = listOf(
            case(
                "passing validation and evidenced checks complete",
                expected = TaskStatus.COMPLETED
            ),
            case(
                "a task without acceptance criteria completes on a passing validation",
                checks = emptyList(),
                expected = TaskStatus.COMPLETED
            ),
            case(
                "a blocking question needs input even when validation passed",
                blocking = true,
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a skipped validation needs input because nothing was verified",
                validation = CheckOutcome.SKIPPED,
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a skipped validation still needs input when a criterion failed",
                validation = CheckOutcome.SKIPPED,
                checks = listOf(failed),
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a failed criterion fails the task",
                checks = listOf(failed),
                expected = TaskStatus.FAILED
            ),
            case(
                "a blocking question outranks a failed criterion",
                blocking = true,
                checks = listOf(failed),
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a failed validation fails the task",
                validation = CheckOutcome.FAILED,
                expected = TaskStatus.FAILED
            ),
            case(
                "a failed validation outranks an unverified criterion",
                validation = CheckOutcome.FAILED,
                checks = listOf(unverified),
                expected = TaskStatus.FAILED
            ),
            case(
                "an unchanged workspace fails the task",
                changed = false,
                expected = TaskStatus.FAILED
            ),
            case(
                "an unchanged workspace fails even with a passing validation",
                validation = CheckOutcome.PASSED,
                changed = false,
                expected = TaskStatus.FAILED
            ),
            case(
                "an unchanged workspace is a known negative before an unresolved patch",
                changed = false,
                patchFailure = true,
                expected = TaskStatus.FAILED
            ),
            case(
                "an unresolved patch needs input",
                patchFailure = true,
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "an unresolved write needs input",
                writeFailure = true,
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "an unresolved patch outranks an unverified criterion",
                checks = listOf(unverified),
                patchFailure = true,
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "an unverified criterion needs input",
                checks = listOf(unverified),
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a missing validation with no other problem fails",
                validation = null,
                expected = TaskStatus.FAILED
            ),
            case(
                "a missing validation with an unverified criterion needs input",
                validation = null,
                checks = listOf(unverified),
                expected = TaskStatus.NEEDS_INPUT
            ),
            case(
                "a failed criterion outranks an unresolved patch",
                validation = CheckOutcome.PASSED,
                checks = listOf(failed),
                patchFailure = true,
                expected = TaskStatus.FAILED
            ),
            case(
                "both unresolved failures still need input",
                patchFailure = true,
                writeFailure = true,
                expected = TaskStatus.NEEDS_INPUT
            )
        )

        cases.forEach { case ->
            val actual = CompletionPolicy.decide(
                validationOutcome = case.validation,
                workspaceChanged = case.changed,
                hasBlockingQuestion = case.blocking,
                acceptanceChecks = case.checks,
                hasUnresolvedPatchFailure = case.patchFailure,
                hasUnresolvedWriteFailure = case.writeFailure
            )
            assertEquals(case.expected, actual, case.name)
        }
    }

    private fun case(
        name: String,
        expected: TaskStatus,
        validation: CheckOutcome? = CheckOutcome.PASSED,
        changed: Boolean = true,
        blocking: Boolean = false,
        checks: List<AcceptanceCheck> = listOf(passed),
        patchFailure: Boolean = false,
        writeFailure: Boolean = false
    ) = DecisionCase(name, expected, validation, changed, blocking, checks, patchFailure, writeFailure)

    private data class DecisionCase(
        val name: String,
        val expected: TaskStatus,
        val validation: CheckOutcome?,
        val changed: Boolean,
        val blocking: Boolean,
        val checks: List<AcceptanceCheck>,
        val patchFailure: Boolean,
        val writeFailure: Boolean
    )
}
