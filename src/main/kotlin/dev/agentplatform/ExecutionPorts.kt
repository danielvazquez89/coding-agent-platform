package dev.agentplatform

import java.nio.file.Path
import java.time.Duration
import java.util.UUID

interface TaskExecutionState {
    fun claimNextQueued(): CodingTask?
    fun claim(id: UUID): CodingTask?
    fun findById(id: UUID): CodingTask?
    fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String = "API"): CodingTask?
}

interface AttemptOperations {
    fun start(taskId: UUID): TaskAttempt?
    fun finish(
        taskId: UUID,
        attemptId: UUID,
        status: AttemptStatus,
        report: TaskExecutionReport?,
        errorSummary: String? = null
    ): TaskAttempt?
}

interface RepositoryWorkspaceFactory {
    fun open(repositoryUrl: String): RepositoryWorkspace
}

interface RepositoryWorkspace : AutoCloseable {
    val root: Path
    fun changedFiles(): List<String>
    fun patchDiff(maxChars: Int): String
    override fun close()
}

interface CommandExecutor {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        standardInput: String? = null,
        timeout: Duration = Duration.ofSeconds(30),
        maxOutputChars: Int = 20_000,
        environment: Map<String, String> = emptyMap()
    ): CommandResult
}

data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

interface ValidationRunner {
    fun validate(workspace: Path): ValidationCheck
}

