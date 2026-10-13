package dev.agentplatform

object TaskLifecycle {
    private val allowedTransitions = mapOf(
        TaskStatus.QUEUED to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED),
        TaskStatus.RUNNING to setOf(TaskStatus.VALIDATING, TaskStatus.FAILED, TaskStatus.NEEDS_INPUT, TaskStatus.CANCELLED),
        TaskStatus.VALIDATING to setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.NEEDS_INPUT, TaskStatus.CANCELLED),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.FAILED to emptySet(),
        TaskStatus.NEEDS_INPUT to emptySet(),
        TaskStatus.CANCELLED to emptySet()
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean =
        to in allowedTransitions.getValue(from)

    fun requireAllowed(from: TaskStatus, to: TaskStatus) {
        if (!canTransition(from, to)) throw InvalidTaskTransitionException(from, to)
    }
}

class InvalidTaskTransitionException(from: TaskStatus, to: TaskStatus) :
    RuntimeException("Cannot transition task from $from to $to") {
    val apiMessage = "Cannot transition task from $from to $to"
}
