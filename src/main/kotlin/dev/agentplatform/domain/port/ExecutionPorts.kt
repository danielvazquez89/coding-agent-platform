package dev.agentplatform.domain.port

import dev.agentplatform.domain.model.*
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*

interface TaskExecutionState {
    fun claimNextQueued(): CodingTask?
    fun claim(id: UUID): CodingTask?
    fun findById(id: UUID): CodingTask?
    fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String = "API"): CodingTask?
}

interface AttemptOperations {
    fun priorBudgetUsage(taskId: UUID): TaskBudgetUsage = TaskBudgetUsage()
    fun recordToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit)
    fun finishToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit)
    fun toolExecutions(taskId: UUID, attemptId: UUID): List<ToolExecutionAudit>
    fun start(taskId: UUID): TaskAttempt?
    fun finish(
        taskId: UUID,
        attemptId: UUID,
        status: AttemptStatus,
        report: TaskExecutionReport?,
        errorSummary: String? = null
    ): TaskAttempt?

    /**
     * Attempts that have been RUNNING since before [startedBefore] and therefore belong to a worker
     * that is no longer reporting progress. Used to reconcile attempts orphaned by a crashed worker.
     */
    fun runningAttemptsStartedBefore(startedBefore: Instant): List<TaskAttempt> = emptyList()
}

data class TaskBudgetUsage(
    val executionDurationMillis: Long? = 0,
    val estimatedCostUsd: Double? = 0.0
)

interface RepositoryWorkspaceFactory {
    fun open(repositoryUrl: String): RepositoryWorkspace
}

interface RepositoryWorkspace : AutoCloseable {
    val root: Path
    fun changedFiles(): List<String>
    fun patchDiff(maxChars: Int): PatchDiff
    override fun close()
}

/**
 * A repository diff bounded to the requested size. [truncated] is true when the real diff was larger
 * than the limit, in which case [diff] ends mid-hunk and is **not** a valid patch: it must never be
 * presented as something that can be applied with `git apply`.
 */
data class PatchDiff(val diff: String, val truncated: Boolean)

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

interface PullRequestPublisher {
    fun createDraft(
        repositoryUrl: String,
        workspace: Path,
        branchName: String,
        title: String,
        body: String,
        changedFiles: List<String>
    ): PullRequestReference
}

data class ToolExecutionResult(val content: String, val validation: ValidationCheck? = null)
