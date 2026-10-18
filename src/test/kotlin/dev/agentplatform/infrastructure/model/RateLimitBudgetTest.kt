package dev.agentplatform.infrastructure.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.Logger

class RateLimitBudgetTest {

    @Test
    fun `disabled budgets never wait`() {
        val time = FakeTime()
        val budget = budget(time = time)

        assertEquals(0L, budget.acquire(100_000))
        assertEquals(0L, budget.acquire(100_000))
        assertFalse(budget.enabled)
        assertTrue(time.sleeps.isEmpty())
    }

    @Test
    fun `request budget waits for the oldest reservation to age out`() {
        val time = FakeTime()
        val budget = budget(requests = 2, time = time)

        assertEquals(0L, budget.acquire(10))
        assertEquals(0L, budget.acquire(10))
        assertEquals(60_000L, budget.acquire(10))
        assertEquals(listOf(60_000L), time.sleeps)
        assertEquals(1, budget.reservations())
    }

    @Test
    fun `token budget waits until the window has room`() {
        val time = FakeTime()
        val budget = budget(tokens = 100, time = time)

        assertEquals(0L, budget.acquire(80))
        assertEquals(60_000L, budget.acquire(80))
        assertEquals(1, budget.reservations())
    }

    @Test
    fun `waits no longer than the configured maximum`() {
        val time = FakeTime()
        val budget = budget(requests = 1, maxWait = 5_000, time = time)

        assertEquals(0L, budget.acquire(10))
        assertEquals(5_000L, budget.acquire(10))
        assertEquals(listOf(5_000L), time.sleeps)
        assertEquals(2, budget.reservations())
    }

    @Test
    fun `a request larger than the whole minute budget is sent immediately`() {
        val time = FakeTime()
        val budget = budget(tokens = 10, time = time)

        assertEquals(0L, budget.acquire(50))
        assertTrue(time.sleeps.isEmpty())
        assertEquals(1, budget.reservations())
    }

    @Test
    fun `reservations leave the window after a minute`() {
        val time = FakeTime()
        val budget = budget(requests = 1, time = time)

        assertEquals(0L, budget.acquire(10))
        time.now += 60_000
        assertEquals(0L, budget.acquire(10))
        assertEquals(1, budget.reservations())
    }

    @Test
    fun `rejects negative configuration and estimates`() {
        val time = FakeTime()
        assertThrows(IllegalArgumentException::class.java) { budget(requests = -1, time = time) }
        assertThrows(IllegalArgumentException::class.java) { budget(tokens = -1, time = time) }
        assertThrows(IllegalArgumentException::class.java) { budget(maxWait = -1, time = time) }
        assertThrows(IllegalArgumentException::class.java) { budget(time = time).acquire(-1) }
    }

    @Test
    fun `parses optional budgets with the disabled sentinel`() {
        assertFalse(ModelRateLimiter("disabled", "DISABLED", 60_000).enabled)
        assertFalse(ModelRateLimiter("", "  ", 60_000).enabled)
        assertTrue(ModelRateLimiter(" 12 ", "disabled", 60_000).enabled)
        assertEquals(12, ModelRateLimiter.budget("12", "test"))
        assertEquals(0, ModelRateLimiter.budget("Disabled", "test"))
        assertThrows(IllegalArgumentException::class.java) { ModelRateLimiter.budget("12.5", "test") }
        assertThrows(IllegalArgumentException::class.java) { ModelRateLimiter.budget("-3", "test") }
        assertThrows(IllegalArgumentException::class.java) { ModelRateLimiter("-1", "disabled", 60_000) }
        assertEquals(0, ModelRateLimiter.estimateTokens(0))
        assertEquals(1, ModelRateLimiter.estimateTokens(1))
        assertEquals(1, ModelRateLimiter.estimateTokens(4))
        assertEquals(2, ModelRateLimiter.estimateTokens(5))
    }

    private fun budget(
        requests: Int = 0,
        tokens: Int = 0,
        maxWait: Long = 120_000,
        time: FakeTime
    ) = RateLimitBudget(
        requestsPerMinute = requests,
        tokensPerMinute = tokens,
        maxWaitMillis = maxWait,
        clock = { time.now },
        sleeper = time::sleep,
        logger = Logger.getLogger("rate-limit-test").apply { level = Level.OFF }
    )

    private class FakeTime {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        fun sleep(millis: Long) {
            sleeps += millis
            now += millis
        }
    }
}
