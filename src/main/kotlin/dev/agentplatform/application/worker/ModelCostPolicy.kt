package dev.agentplatform.application.worker

/**
 * Rejects a configuration where a hosted model would incur real spend but both token rates are
 * zero. In that state the per-task cost estimate is always zero, so `AGENT_MAX_TASK_COST_USD`
 * cannot bound anything.
 *
 * A local OpenAI-compatible server normally has no API key, and local Ollama runs are intentionally
 * unpriced, so those configurations stay valid. The explicit override is for an operator who has
 * accepted that the cost ceiling is advisory only.
 */
object ModelCostPolicy {
    private val HOSTED_PROVIDERS = setOf("openai-compatible", "openai")

    fun rejection(
        provider: String,
        apiKey: String,
        inputCostPerMillionUsd: Double,
        outputCostPerMillionUsd: Double,
        allowUnpricedHostedModel: Boolean
    ): String? {
        if (allowUnpricedHostedModel || !isHosted(provider) || !isConfigured(apiKey)) return null
        if (inputCostPerMillionUsd > 0.0 || outputCostPerMillionUsd > 0.0) return null
        return "The '${provider.trim()}' provider is configured with an API key but both per-million-token " +
            "rates are zero, so estimated cost is always zero and AGENT_MAX_TASK_COST_USD cannot bound " +
            "spend. Set AGENT_MODEL_INPUT_COST_PER_MILLION_USD and " +
            "AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD to the provider's published rates, or set " +
            "AGENT_ALLOW_UNPRICED_HOSTED_MODEL=true to accept an unenforced cost ceiling."
    }

    private fun isHosted(provider: String): Boolean =
        provider.trim().lowercase() in HOSTED_PROVIDERS

    private fun isConfigured(value: String): Boolean =
        value.isNotBlank() && !value.trim().equals("disabled", ignoreCase = true)
}
