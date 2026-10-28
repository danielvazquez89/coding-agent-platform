package dev.agentplatform.application.worker

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelCostPolicyTest {

    @Test
    fun `rejects a key-backed hosted provider with both rates at zero`() {
        val rejection = ModelCostPolicy.rejection(
            provider = "openai-compatible",
            apiKey = "sk-test",
            inputCostPerMillionUsd = 0.0,
            outputCostPerMillionUsd = 0.0,
            allowUnpricedHostedModel = false
        )

        assertTrue(rejection!!.contains("AGENT_MODEL_INPUT_COST_PER_MILLION_USD"), rejection)
        assertTrue(rejection.contains("AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD"), rejection)
        assertTrue(rejection.contains("AGENT_ALLOW_UNPRICED_HOSTED_MODEL=true"), rejection)
        assertTrue(rejection.contains("AGENT_MAX_TASK_COST_USD"), rejection)
    }

    @Test
    fun `accepts a hosted provider with either token rate priced`() {
        assertNull(rejection(inputCostPerMillionUsd = 0.25))
        assertNull(rejection(outputCostPerMillionUsd = 0.75))
    }

    @Test
    fun `accepts a keyless local OpenAI-compatible server`() {
        assertNull(rejection(apiKey = "disabled"))
        assertNull(rejection(apiKey = " Disabled "))
        assertNull(rejection(apiKey = ""))
        assertNull(rejection(apiKey = "   "))
    }

    @Test
    fun `accepts an unpriced ollama provider because local inference has no spend ceiling`() {
        assertNull(rejection(provider = "ollama"))
    }

    @Test
    fun `accepts the openai alias case-insensitively and flags it`() {
        assertNull(
            ModelCostPolicy.rejection(" Ollama ", "sk-test", 0.0, 0.0, false)
        )
        assertTrue(
            ModelCostPolicy.rejection(" OpenAI ", "sk-test", 0.0, 0.0, false)!!
                .contains("OpenAI")
        )
        assertTrue(
            ModelCostPolicy.rejection("OPENAI-COMPATIBLE", "sk-test", 0.0, 0.0, false) != null
        )
    }

    @Test
    fun `the explicit override accepts an unpriced hosted provider`() {
        assertNull(
            ModelCostPolicy.rejection(
                provider = "openai-compatible",
                apiKey = "sk-test",
                inputCostPerMillionUsd = 0.0,
                outputCostPerMillionUsd = 0.0,
                allowUnpricedHostedModel = true
            )
        )
    }

    private fun rejection(
        provider: String = "openai-compatible",
        apiKey: String = "sk-test",
        inputCostPerMillionUsd: Double = 0.0,
        outputCostPerMillionUsd: Double = 0.0,
        allowUnpricedHostedModel: Boolean = false
    ): String? = ModelCostPolicy.rejection(
        provider,
        apiKey,
        inputCostPerMillionUsd,
        outputCostPerMillionUsd,
        allowUnpricedHostedModel
    )
}
