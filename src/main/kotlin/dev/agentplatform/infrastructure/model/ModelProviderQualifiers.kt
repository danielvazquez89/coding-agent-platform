package dev.agentplatform.infrastructure.model

import jakarta.inject.Qualifier

/**
 * CDI qualifiers that keep the concrete model adapters off the plain [dev.agentplatform.domain.port.CodingModelProvider]
 * type. Without them a second adapter would make every unqualified injection point ambiguous; with
 * them the only bean a caller resolves through the interface is [SelectingCodingModelProvider].
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class OllamaModelProvider

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class OpenAiCompatibleModelProvider
