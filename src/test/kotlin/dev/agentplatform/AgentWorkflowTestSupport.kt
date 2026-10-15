package dev.agentplatform

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

object AgentWorkflowTestSupport {
    val mapper = ObjectMapper()
    val modelTurns = ConcurrentLinkedQueue<String>()
    val modelRequests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var allowedRepositoryRoot: Path
    private lateinit var workspaceRoot: Path

    fun allowedRepositoryRootPath(): Path = allowedRepositoryRoot

    fun workspaceRootPath(): Path = workspaceRoot

    fun configureRoots(allowedRepository: Path, workspace: Path) {
        allowedRepositoryRoot = allowedRepository
        workspaceRoot = workspace
    }

    fun reset() {
        modelTurns.clear()
        modelRequests.clear()
    }

    fun enqueueToolCall(name: String, arguments: Any) {
        val response = mapper.createObjectNode()
        val message = response.putObject("message")
        message.putNull("content")
        val call = message.putArray("tool_calls").addObject()
        call.put("id", "integration-tool-call-${modelTurns.size}")
        call.put("type", "function")
        val function = call.putObject("function")
        function.put("name", name)
        function.set<com.fasterxml.jackson.databind.JsonNode>("arguments", mapper.valueToTree(arguments))
        response.put("done_reason", "tool_calls")
        response.put("prompt_eval_count", 12)
        response.put("eval_count", 4)
        modelTurns += mapper.writeValueAsString(response)
    }

    fun enqueueAssistant(content: String) {
        val response = mapper.createObjectNode()
        response.putObject("message").put("content", content)
        response.put("done_reason", "stop")
        response.put("prompt_eval_count", 18)
        response.put("eval_count", 6)
        modelTurns += mapper.writeValueAsString(response)
    }

    fun patch(from: String, to: String): String = listOf(
        "diff --git a/README.md b/README.md",
        "--- a/README.md",
        "+++ b/README.md",
        "@@ -1 +1 @@",
        "-$from",
        "+$to"
    ).joinToString("\n", postfix = "\n")

    fun successfulFinal(criterion: String) = mapper.writeValueAsString(
        mapOf(
            "summary" to "Applied the requested repository change",
            "implementation" to "Updated README.md and validated the change.",
            "acceptanceChecks" to listOf(
                mapOf("criterion" to criterion, "outcome" to "PASSED", "evidence" to "README.md contains the requested text")
            )
        )
    )
}

class AgentWorkflowTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer
    private lateinit var temporaryRoot: Path

    override fun start(): Map<String, String> {
        temporaryRoot = Files.createTempDirectory("agent-workflow-it-")
        val allowedRepositoryRoot = Files.createDirectory(temporaryRoot.resolve("repositories"))
        val workspaceRoot = Files.createDirectory(temporaryRoot.resolve("workspaces"))
        AgentWorkflowTestSupport.configureRoots(allowedRepositoryRoot, workspaceRoot)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            AgentWorkflowTestSupport.modelRequests += request
            val response = AgentWorkflowTestSupport.modelTurns.poll()
                ?: """{"error":"No scripted model response remains"}"""
            val status = if (response.startsWith("""{"error":""")) 500 else 200
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return mapOf(
            "agent.model.ollama.base-url" to "http://127.0.0.1:${server.address.port}",
            "agent.execution.allowed-local-root" to AgentWorkflowTestSupport.allowedRepositoryRootPath().toString(),
            "agent.execution.workspace-root" to AgentWorkflowTestSupport.workspaceRootPath().toString(),
            "agent.execution.enabled" to "false",
            "agent.execution.worker.enabled" to "false",
            "agent.execution.control-plane-token" to "integration-control-plane-token",
            "quarkus.scheduler.enabled" to "false"
        )
    }

    override fun stop() {
        if (::server.isInitialized) server.stop(0)
        if (::temporaryRoot.isInitialized) {
            Files.walk(temporaryRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
