package dev.agentplatform.infrastructure.model

import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Reads the optional per-provider rate budget and exposes the shared [RateLimitBudget] policy.
 *
 * The bean is a singleton, so every turn of every task pays into one minute window. Budgets are
 * optional and use the `disabled` sentinel: an unset or `disabled` value turns pacing off, which is
 * the default for a local model. Only the OpenAI-compatible provider consults it; a local Ollama
 * server has no quota to protect.
 *
 * Tokens are estimated from the serialized request (`characters / 4`), the same rough convention as
 * the repository map. The estimate is conservative because the reservation is held for a full
 * minute, and a `429` retry keeps its reservation: the provider counted that attempt too.
 */
@Singleton
class ModelRateLimiter(
    @ConfigProperty(name = "agent.model.rate-limit.requests-per-minute", defaultValue = "disabled")
    requestsPerMinute: String,
    @ConfigProperty(name = "agent.model.rate-limit.tokens-per-minute", defaultValue = "disabled")
    tokensPerMinute: String,
    @ConfigProperty(name = "agent.model.rate-limit.max-wait-millis", defaultValue = "60000")
    maxWaitMillis: Long
) {
    private val budget = RateLimitBudget(
        requestsPerMinute = budget(requestsPerMinute, "agent.model.rate-limit.requests-per-minute"),
        tokensPerMinute = budget(tokensPerMinute, "agent.model.rate-limit.tokens-per-minute"),
        maxWaitMillis = maxWaitMillis
    )

    val enabled: Boolean get() = budget.enabled

    fun acquire(estimatedTokens: Int): Long = budget.acquire(estimatedTokens)

    fun reservations(): Int = budget.reservations()

    companion object {
        /** `disabled` (any case) and blank mean "no budget"; anything else must be a non-negative number. */
        fun budget(value: String, name: String): Int {
            if (value.isBlank() || value.trim().equals("disabled", ignoreCase = true)) return 0
            val parsed = value.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$name must be a whole number or 'disabled'")
            require(parsed >= 0) { "$name must not be negative" }
            return parsed
        }

        /** One token per four characters, rounded up, the same estimate the prompt budget uses. */
        fun estimateTokens(characters: Int): Int = (characters + 3) / 4
    }
}
