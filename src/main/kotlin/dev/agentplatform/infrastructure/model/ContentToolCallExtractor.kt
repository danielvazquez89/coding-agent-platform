package dev.agentplatform.infrastructure.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.port.ModelToolCall
import dev.agentplatform.domain.port.ModelToolDefinition
import java.io.IOException
import java.util.UUID

/**
 * Recovers a tool call from free-form model text. Weak local models (and some OpenAI-compatible
 * servers) answer with a JSON object or a fenced diff instead of using the structured tool-call
 * channel. This never weakens the allow-list: a candidate is accepted only when it names a tool the
 * caller actually offered, and it is only consulted when the provider returned no structured calls.
 */
internal object ContentToolCallExtractor {
    fun extract(
        content: String?,
        tools: List<ModelToolDefinition>,
        objectMapper: ObjectMapper
    ): ModelToolCall? {
        if (content == null || tools.isEmpty()) return null
        if (tools.any { it.name == "apply_patch" }) {
            val diff = extractFencedGitDiff(content)
            if (diff != null) {
                val arguments = objectMapper.createObjectNode().put("patch", diff)
                return ModelToolCall(UUID.randomUUID().toString(), "apply_patch", arguments)
            }
        }
        val directCandidate = try {
            objectMapper.readTree(content)
        } catch (_: IOException) {
            null
        }
        val candidates = sequenceOf(directCandidate).plus(embeddedJsonCandidates(content, objectMapper))
        for (candidate in candidates) {
            if (candidate == null || !candidate.isObject) continue
            val name = candidate.path("name").takeIf(JsonNode::isTextual)?.asText() ?: continue
            if (tools.none { it.name == name }) continue
            val arguments = candidate.path("arguments")
            if (!arguments.isObject) continue
            return ModelToolCall(UUID.randomUUID().toString(), name, arguments)
        }
        return null
    }

    private fun extractFencedGitDiff(content: String): String? {
        val match = FENCED_DIFF.find(content) ?: return null
        val diff = match.groupValues[1].trimEnd()
        if (!diff.startsWith("diff --git ")) return null
        if (diff.lineSequence().none { it.startsWith("--- ") } || diff.lineSequence().none { it.startsWith("+++ ") }) return null
        return diff
    }

    private fun embeddedJsonCandidates(content: String, objectMapper: ObjectMapper): Sequence<JsonNode?> = sequence {
        for (start in content.indices) {
            if (content[start] != '{') continue
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start..content.lastIndex) {
                val character = content[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                    continue
                }
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = try {
                                objectMapper.readTree(content.substring(start, index + 1))
                            } catch (_: IOException) {
                                null
                            }
                            yield(candidate)
                            break
                        }
                    }
                }
            }
        }
    }

    private val FENCED_DIFF = Regex("```diff\\s*\\r?\\n(.*?)\\r?\\n```", RegexOption.DOT_MATCHES_ALL)
}
