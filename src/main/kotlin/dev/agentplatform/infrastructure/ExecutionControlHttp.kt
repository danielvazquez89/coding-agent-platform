package dev.agentplatform.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.TestResult
import dev.agentplatform.domain.model.ValidationCheck
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

object ExecutionControlHttp {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun validate(baseUrl: String, token: String, workspace: Path, timeoutSeconds: Long): ValidationCheck {
        val body = mapper.createObjectNode().put("workspacePath", workspace.toAbsolutePath().normalize().toString())
        val response = post(baseUrl, token, "/internal/execution/validate", body, timeoutSeconds)
        val json = mapper.readTree(response)
        val testResults = json.path("testResults").takeIf { it.isArray }?.mapNotNull { node ->
            val className = node.path("className").asText("").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val methodName = node.path("methodName").asText("").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val outcome = runCatching { CheckOutcome.valueOf(node.path("outcome").asText("")) }
                .getOrNull() ?: return@mapNotNull null
            TestResult(
                className = className,
                methodName = methodName,
                outcome = outcome,
                durationMillis = node.path("durationMillis").takeIf { it.isNumber }?.asLong()
            )
        }.orEmpty()
        return ValidationCheck(
            name = json.path("name").asText(),
            command = json.path("command").textValue(),
            outcome = CheckOutcome.valueOf(json.path("outcome").asText()),
            exitCode = json.path("exitCode").takeIf { it.isInt }?.asInt(),
            details = json.path("details").textValue(),
            durationMillis = json.path("durationMillis").takeIf { it.isNumber }?.asLong(),
            testResults = testResults,
            testResultsAvailable = json.path("testResultsAvailable").asBoolean(false)
        )
    }

    private fun post(baseUrl: String, token: String, path: String, body: com.fasterxml.jackson.databind.JsonNode, timeoutSeconds: Long): String {
        require(token.isNotBlank()) { "Execution control-plane token is not configured" }
        require(timeoutSeconds > 0) { "Execution control-plane timeout must be positive" }
        val endpoint = URI.create(baseUrl.trimEnd('/') + path)
        require((endpoint.scheme == "http" || endpoint.scheme == "https") &&
            endpoint.host != null && endpoint.userInfo == null) {
            "Execution control-plane URL must be an absolute HTTP or HTTPS URL without credentials"
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Execution control-plane request was interrupted", failure)
        } catch (failure: Exception) {
            throw IllegalStateException("Execution control-plane request failed: ${failure.message}", failure)
        }
        check(response.statusCode() in 200..299) {
            "Execution control-plane returned HTTP ${response.statusCode()}: ${response.body().take(2000)}"
        }
        return response.body()
    }
}
