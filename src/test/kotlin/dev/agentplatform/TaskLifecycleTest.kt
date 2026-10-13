package dev.agentplatform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskLifecycleTest {
    @Test
    fun `only declared transitions are allowed`() {
        val allowed = setOf(
            TaskStatus.QUEUED to TaskStatus.RUNNING,
            TaskStatus.QUEUED to TaskStatus.CANCELLED,
            TaskStatus.RUNNING to TaskStatus.VALIDATING,
            TaskStatus.RUNNING to TaskStatus.FAILED,
            TaskStatus.RUNNING to TaskStatus.NEEDS_INPUT,
            TaskStatus.RUNNING to TaskStatus.CANCELLED,
            TaskStatus.VALIDATING to TaskStatus.COMPLETED,
            TaskStatus.VALIDATING to TaskStatus.FAILED,
            TaskStatus.VALIDATING to TaskStatus.NEEDS_INPUT,
            TaskStatus.VALIDATING to TaskStatus.CANCELLED
        )

        for (from in TaskStatus.entries) {
            for (to in TaskStatus.entries) {
                assertEquals(
                    (from to to) in allowed,
                    TaskLifecycle.canTransition(from, to),
                    "Unexpected transition rule for $from -> $to"
                )
            }
        }
    }

    @Test
    fun `invalid transition exception identifies source and target`() {
        val exception = org.junit.jupiter.api.Assertions.assertThrows(InvalidTaskTransitionException::class.java) {
            TaskLifecycle.requireAllowed(TaskStatus.QUEUED, TaskStatus.COMPLETED)
        }

        assertEquals("Cannot transition task from QUEUED to COMPLETED", exception.message)
    }
}
