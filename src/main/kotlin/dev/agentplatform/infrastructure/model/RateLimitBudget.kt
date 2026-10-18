package dev.agentplatform.infrastructure.model

import java.util.ArrayDeque
import java.util.logging.Logger

/**
 * A sliding one-minute budget over request count and estimated input tokens.
 *
 * Hosted free tiers throttle per-minute token budgets, so the retry loop is not the only defence:
 * pacing keeps a sequence of turns below the published cap instead of collecting `429`s. The budget
 * is deliberately best effort. A reservation is made before the request is sent and is held for a
 * full minute, which is conservative but needs no knowledge of the provider's exact accounting.
 *
 * If the budget is still full after [maxWaitMillis], the request is sent anyway with a warning: the
 * provider's own retry/backoff still applies, and making the budget a hard failure would turn a
 * throughput setting into a correctness boundary. A single request bigger than the whole minute
 * budget is the same case, and it is logged rather than retried in a spin.
 *
 * [clock] and [sleeper] exist so tests can advance time without sleeping.
 */
class RateLimitBudget(
    private val requestsPerMinute: Int,
    private val tokensPerMinute: Int,
    private val maxWaitMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val logger: Logger = Logger.getLogger(RateLimitBudget::class.java.name)
) {
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    init {
        require(requestsPerMinute >= 0) { "Rate-limit requests per minute must not be negative" }
        require(tokensPerMinute >= 0) { "Rate-limit tokens per minute must not be negative" }
        require(maxWaitMillis >= 0) { "Rate-limit maximum wait must not be negative" }
    }

    val enabled: Boolean get() = requestsPerMinute > 0 || tokensPerMinute > 0

    /** Blocks until the reservation fits, and returns the milliseconds it actually waited. */
    fun acquire(estimatedTokens: Int): Long {
        require(estimatedTokens >= 0) { "Estimated token count must not be negative" }
        if (!enabled) return 0L
        if (tokensPerMinute > 0 && estimatedTokens > tokensPerMinute) {
            logger.warning(
                "Estimated $estimatedTokens tokens exceed the per-minute budget of $tokensPerMinute; sending anyway"
            )
            reserve(estimatedTokens)
            return 0L
        }
        var waited = 0L
        while (true) {
            val wait = synchronized(lock) {
                val now = clock()
                evict(now)
                if (fits(estimatedTokens)) {
                    entries.addLast(Entry(now, estimatedTokens))
                    0L
                } else {
                    waitMillis(now)
                }
            }
            if (wait == 0L) return waited
            val remaining = maxWaitMillis - waited
            if (wait >= remaining) {
                sleeper(remaining)
                reserve(estimatedTokens)
                logger.warning("Rate-limit budget still full after ${maxWaitMillis}ms; sending anyway")
                return maxWaitMillis
            }
            sleeper(wait)
            waited += wait
        }
    }

    /** Number of reservations still inside the current minute; exposed for tests and diagnostics. */
    fun reservations(): Int = synchronized(lock) { entries.size }

    private fun reserve(estimatedTokens: Int) {
        synchronized(lock) {
            val now = clock()
            evict(now)
            entries.addLast(Entry(now, estimatedTokens))
        }
    }

    private fun fits(estimatedTokens: Int): Boolean {
        if (requestsPerMinute > 0 && entries.size + 1 > requestsPerMinute) return false
        if (tokensPerMinute > 0 && entries.sumOf { it.tokens } + estimatedTokens > tokensPerMinute) return false
        return true
    }

    private fun waitMillis(now: Long): Long {
        val oldest = entries.firstOrNull() ?: return 0L
        return (oldest.at + WINDOW_MILLIS - now).coerceAtLeast(1L)
    }

    private fun evict(now: Long) {
        while (entries.isNotEmpty() && entries.first.at + WINDOW_MILLIS <= now) {
            entries.removeFirst()
        }
    }

    private data class Entry(val at: Long, val tokens: Int)

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}
