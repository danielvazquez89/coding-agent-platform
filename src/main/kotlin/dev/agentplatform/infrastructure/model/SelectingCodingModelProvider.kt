package dev.agentplatform.infrastructure.model

import dev.agentplatform.domain.port.CodingModelProvider
import dev.agentplatform.domain.port.ModelMessage
import dev.agentplatform.domain.port.ModelToolDefinition
import dev.agentplatform.domain.port.ModelTurn
import dev.agentplatform.infrastructure.ollama.OllamaCodingModelProvider
import dev.agentplatform.infrastructure.openai.OpenAiCompatibleCodingModelProvider
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Chooses the active [CodingModelProvider] from `agent.model.provider`, so an operator can point the
 * worker at a local Ollama server or any OpenAI-compatible endpoint without rebuilding the image.
 *
 * The concrete adapters carry the [OllamaModelProvider] / [OpenAiCompatibleModelProvider] qualifiers,
 * which is what keeps this bean the single candidate whenever the application injects the plain
 * [CodingModelProvider] type. [Startup] makes an unknown provider value fail the deployment instead
 * of silently falling back to a default.
 */
@Startup
@ApplicationScoped
class SelectingCodingModelProvider @Inject constructor(
    @OllamaModelProvider private val ollama: OllamaCodingModelProvider,
    @OpenAiCompatibleModelProvider private val openAiCompatible: OpenAiCompatibleCodingModelProvider,
    @ConfigProperty(name = "agent.model.provider", defaultValue = PROVIDER_OLLAMA)
    private val configuredProvider: String
) : CodingModelProvider {
    private val delegate: CodingModelProvider = when (configuredProvider.trim().lowercase()) {

        PROVIDER_OLLAMA -> ollama
        PROVIDER_OPENAI, PROVIDER_OPENAI_ALIAS -> openAiCompatible
        else -> throw IllegalArgumentException(
            "agent.model.provider must be '$PROVIDER_OLLAMA' or '$PROVIDER_OPENAI' (was '$configuredProvider')"
        )
    }

    override val modelName: String get() = delegate.modelName

    override fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn =
        delegate.chat(messages, tools)

    private companion object {
        const val PROVIDER_OLLAMA = "ollama"
        const val PROVIDER_OPENAI = "openai-compatible"
        const val PROVIDER_OPENAI_ALIAS = "openai"
    }
}
