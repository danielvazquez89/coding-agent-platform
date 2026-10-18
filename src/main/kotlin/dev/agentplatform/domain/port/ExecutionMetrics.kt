package dev.agentplatform.domain.port

/**
 * Observability seam for execution. The domain stays framework-free: an adapter decides how these
 * events leave the process (Micrometer, logs, nothing at all), so this port has no dependency on a
 * metrics library.
 *
 * The default implementations are intentionally empty, which keeps test doubles and adapters that do
 * not care about metrics small. Implementations must never throw: a broken meter must not fail a task.
 */
interface ExecutionMetrics {
    fun modelRequest(provider: String, outcome: String) {}

    fun modelRetry(provider: String, reason: String) {}

    fun rateLimitWait(provider: String, millis: Long) {}

    fun toolCall(tool: String, outcome: String) {}

    fun taskFinished(status: String) {}

    companion object {
        val NONE: ExecutionMetrics = object : ExecutionMetrics {}
    }
}
