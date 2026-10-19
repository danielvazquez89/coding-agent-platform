package dev.agentplatform.infrastructure.model

import dev.agentplatform.domain.port.ModelProviderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderRetryBackoffTest {

    @Test
    fun `backs off between half the ceiling and the ceiling`() {
        repeat(200) {
            listOf(1, 2, 3, 7, 20).forEach { attempt ->
                val delay = ProviderRetryBackoff.delayMillis(attempt, 100, 60_000)
                val exponent = (attempt - 1).coerceAtMost(ProviderRetryBackoff.MAX_EXPONENT)
                val ceiling = 100L shl exponent
                assertTrue(delay in (ceiling / 2)..ceiling, "attempt $attempt produced ${delay}ms")
            }
        }
    }

    @Test
    fun `never waits longer than the configured maximum`() {
        repeat(100) {
            // A late attempt would shift far past the cap; the cap and the half-of-cap floor still bound it.
            val delay = ProviderRetryBackoff.delayMillis(30, 1000, 5000)
            assertTrue(delay in 2500..5000, "capped delay was ${delay}ms")
        }
    }

    @Test
    fun `sleeping preserves the interrupt flag and fails honestly`() {
        val interrupted = Thread.currentThread().isInterrupted
        try {
            Thread.currentThread().interrupt()
            val failure = assertThrows(ModelProviderException::class.java) {
                ProviderRetryBackoff.sleep(10_000, "Ollama")
            }
            assertEquals("Ollama retry wait was interrupted", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            if (!interrupted) Thread.interrupted()
        }
    }

    @Test
    fun `sleeps for the requested delay`() {
        val startedAt = System.nanoTime()
        ProviderRetryBackoff.sleep(50, "Ollama")
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMillis >= 40, "expected a real wait, took ${elapsedMillis}ms")
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
