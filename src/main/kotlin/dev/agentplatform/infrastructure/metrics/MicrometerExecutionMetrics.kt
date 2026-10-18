package dev.agentplatform.infrastructure.metrics

import dev.agentplatform.domain.port.ExecutionMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import java.time.Duration

/**
 * Exports execution events as Micrometer meters, scraped from `/q/metrics` by Prometheus.
 *
 * Names are stable and low-cardinality by design. Task ids, paths and prompts never become tags;
 * only the tool name, provider, outcome/reason and terminal status are.
 */
@Singleton
class MicrometerExecutionMetrics(private val registry: MeterRegistry) : ExecutionMetrics {

    override fun modelRequest(provider: String, outcome: String) {
        registry.counter("agent.model.requests", "provider", provider, "outcome", outcome).increment()
    }

    override fun modelRetry(provider: String, reason: String) {
        registry.counter("agent.model.retries", "provider", provider, "reason", reason).increment()
    }

    override fun rateLimitWait(provider: String, millis: Long) {
        if (millis <= 0) return
        registry.timer("agent.model.rate.limit.wait", "provider", provider).record(Duration.ofMillis(millis))
    }

    override fun toolCall(tool: String, outcome: String) {
        registry.counter("agent.tool.calls", "tool", tool, "outcome", outcome).increment()
    }

    override fun taskFinished(status: String) {
        registry.counter("agent.tasks.finished", "status", status).increment()
    }
}
