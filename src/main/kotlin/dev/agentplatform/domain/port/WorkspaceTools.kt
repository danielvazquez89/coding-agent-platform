package dev.agentplatform.domain.port

import java.nio.file.Path

/**
 * The application's boundary to repository inspection and editing.
 *
 * Implementations own the allow-list, path checks, patch limits, and validation invocation. A caller
 * may propose a tool and its arguments, but the adapter decides whether the call runs. Arguments are
 * passed as JSON text so this boundary does not expose a JSON library to the domain or application.
 * [repositoryMap] returns prompt context rather than executing a model tool; it lives here because the
 * same adapter owns the workspace heuristics.
 */
interface WorkspaceTools {
    fun definitions(): List<ModelToolDefinition>

    fun repositoryMap(workspace: Path, query: String): String

    fun execute(name: String, argumentsJson: String, workspace: Path): ToolExecutionResult
}
