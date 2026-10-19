package dev.agentplatform.infrastructure.model

import dev.agentplatform.domain.port.ModelProviderException
import java.util.concurrent.ThreadLocalRandom

/**
 * Shared retry pacing for the model providers. Both the hosted and the local adapter bound their
 * retries and wait with exponential backoff plus jitter, so several in-flight attempts do not retry in
 * lock-step. Each adapter still decides *what* is retryable and reports its own metrics; only the wait
 * policy lives here, once.
 */
internal object ProviderRetryBackoff {

    /** Caps the exponent so a long retry budget cannot overflow the shift below. */
    const val MAX_EXPONENT = 6

    /**
     * A bounded, jittered wait for the given one-based attempt: never longer than [maxMillis] and never
     * shorter than half of it once the ceiling is reached, so a retried request still makes progress.
     */
    fun delayMillis(attempt: Int, initialMillis: Long, maxMillis: Long): Long {
        val exponent = (attempt - 1).coerceAtMost(MAX_EXPONENT)
        val ceiling = (initialMillis shl exponent).coerceAtMost(maxMillis)
        val floor = ceiling / 2
        return floor + ThreadLocalRandom.current().nextLong(ceiling - floor + 1)
    }

    /** Sleeps before a retry, preserving the interrupt flag and failing honestly when interrupted. */
    fun sleep(delayMillis: Long, provider: String) {
        try {
            Thread.sleep(delayMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ModelProviderException("$provider retry wait was interrupted", exception)
        }
    }
}
