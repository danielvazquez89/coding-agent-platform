package dev.agentplatform.infrastructure.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MicrometerExecutionMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerExecutionMetrics(registry)

    @Test
    fun `records model requests retries and bounded waits`() {
        metrics.modelRequest("openai-compatible", "success")
        metrics.modelRequest("openai-compatible", "failure")
        metrics.modelRetry("openai-compatible", "rate_limited")
        metrics.rateLimitWait("openai-compatible", 1_500)
        metrics.rateLimitWait("openai-compatible", 0)

        assertEquals(
            1.0,
            registry.get("agent.model.requests").tag("outcome", "success").counter().count()
        )
        assertEquals(
            1.0,
            registry.get("agent.model.requests").tag("outcome", "failure").counter().count()
        )
        assertEquals(
            1.0,
            registry.get("agent.model.retries").tag("reason", "rate_limited").counter().count()
        )
        val waits = registry.get("agent.model.rate.limit.wait").timer()
        assertEquals(1L, waits.count())
        assertEquals(1_500.0, waits.totalTime(TimeUnit.MILLISECONDS), 1.0)
    }

    @Test
    fun `records tool calls and terminal task statuses`() {
        metrics.toolCall("read_file", "success")
        metrics.toolCall("read_file", "rejected")
        metrics.toolCall("apply_patch", "failed")
        metrics.taskFinished("COMPLETED")
        metrics.taskFinished("FAILED")

        assertEquals(1.0, registry.get("agent.tool.calls").tag("outcome", "success").counter().count())
        assertEquals(1.0, registry.get("agent.tool.calls").tag("outcome", "rejected").counter().count())
        assertEquals(1.0, registry.get("agent.tool.calls").tag("tool", "apply_patch").counter().count())
        assertEquals(1.0, registry.get("agent.tasks.finished").tag("status", "COMPLETED").counter().count())
        assertEquals(1.0, registry.get("agent.tasks.finished").tag("status", "FAILED").counter().count())
    }

    @Test
    fun `the default port implementation is a no-op`() {
        val none = dev.agentplatform.domain.port.ExecutionMetrics.NONE
        assertDoesNotThrow {
            none.modelRequest("ollama", "success")
            none.modelRetry("ollama", "server_error")
            none.rateLimitWait("ollama", 10)
            none.toolCall("list_files", "success")
            none.taskFinished("COMPLETED")
        }
        assertTrue(registry.meters.isEmpty())
    }
}
