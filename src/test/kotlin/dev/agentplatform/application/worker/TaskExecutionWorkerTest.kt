package dev.agentplatform.application.worker

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.CodingTask
import dev.agentplatform.domain.model.TaskAttempt
import dev.agentplatform.domain.model.TaskExecutionReport
import dev.agentplatform.domain.model.TaskLifecycle
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.AttemptOperations
import dev.agentplatform.domain.port.CodingModelProvider
import dev.agentplatform.domain.port.ModelMessage
import dev.agentplatform.domain.port.ModelToolCall
import dev.agentplatform.domain.port.ModelToolDefinition
import dev.agentplatform.domain.port.ModelTurn
import dev.agentplatform.domain.port.RepositoryWorkspace
import dev.agentplatform.domain.port.RepositoryWorkspaceFactory
import dev.agentplatform.domain.port.TaskExecutionState
import dev.agentplatform.domain.port.ValidationRunner
import dev.agentplatform.infrastructure.process.LocalCommandExecutor
import dev.agentplatform.infrastructure.tool.WorkspaceToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class TaskExecutionWorkerTest {
    @TempDir
    lateinit var temporary: Path
    private val mapper = ObjectMapper()

    @Test
    fun `claims and runs a task through inspection and model returned report`() {
        val task = task()
        val state = FakeState(task)
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(temporary)
        Files.writeString(temporary.resolve("README.md"), "project context\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-project context",
            "+updated project context"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("read_file", """{"path":"README.md"}""")), "tool_calls", 10, 5),
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                12,
                5
            ),
            ModelTurn(
                """{"summary":"Improved project","implementation":"Read the project and made the requested change.","decisions":[{"topic":"Testing","choice":"Keep the regression test","rationale":"The behavior needs a durable check.","alternatives":["Manual verification"]}],"openQuestions":[{"question":"Should this support Java too?","impact":"That expands the target matrix.","blocking":false}]}""",
                emptyList(), "stop", 20, 8
            )
        )
        val worker = worker(state, attempts, provider, workspace)

        assertTrue(worker.processNext())
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(listOf(TaskStatus.RUNNING, TaskStatus.VALIDATING, TaskStatus.COMPLETED), state.transitions)
        assertEquals(AttemptStatus.COMPLETED, attempts.finished?.status)
        val report = attempts.finishedReport!!
        assertEquals("Improved project", report.summary)
        assertEquals("Testing", report.decisions.single().topic)
        assertEquals(false, report.openQuestions.single().blocking)
        assertTrue(provider.messages.any { it.role == "tool" && it.content.contains("project context") })
        val assistantCall = provider.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals("read_file", assistantCall.toolCalls.single().name)
        assertEquals("tool", provider.messages.last().role)
        assertEquals("apply_patch", provider.messages.last().toolName)
        assertTrue(provider.messages.last().content.contains("Patch applied"))
        assertEquals(CheckOutcome.PASSED, report.validation.single().outcome)
        assertEquals("src/main/Example.kt", report.changedFiles.single())
        assertTrue(workspace.closed)
    }

    @Test
    fun `uses explicit validation and marks blocking model questions as needs input`() {
        val task = task()
        val state = FakeState(task)
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(temporary)
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("run_validation", "{}")), "tool_calls", 3, 1),
            ModelTurn(
                """{"summary":"Need a decision","implementation":"No patch was made.","openQuestions":[{"question":"Which runtime should the patch target?","impact":"The available APIs differ.","blocking":true}]}""",
                emptyList(), "stop", 4, 2
            )
        )
        val worker = worker(state, attempts, provider, workspace, validation = ValidationCheck(
            "Maven tests",
            outcome = CheckOutcome.PASSED
        )
        )

        assertTrue(worker.execute(task.id))
        assertEquals(TaskStatus.NEEDS_INPUT, task.status)
        assertEquals(AttemptStatus.NEEDS_INPUT, attempts.finished?.status)
        assertTrue(attempts.finishedReport!!.openQuestions.single().blocking)
        assertEquals(1, attempts.finishedReport!!.validation.size)
    }

    @Test
    fun `nudges a prose-only response and retries when the model stops before patching`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "project context\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-project context",
            "+updated project context"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn("I will inspect the files and make a patch.", emptyList(), "stop", null, null),
            ModelTurn(null, listOf(call("read_file", """{"path":"README.md"}""")), "tool_calls", null, null),
            ModelTurn("I could not find the file.", emptyList(), "stop", null, null),
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                null,
                null
            ),
            ModelTurn(
                """{"summary":"Updated project","implementation":"Applied the patch."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(5, provider.calls)
        assertTrue(provider.messages.any { it.role == "user" && it.content.contains("Do not narrate another plan") })
        assertTrue(provider.messages.any { it.role == "user" && it.content.contains("Last tool result") && it.content.contains("project context") })
        assertTrue(provider.messages.any { it.role == "assistant" && it.content == "I will inspect the files and make a patch." })
        assertEquals("updated project context\n", Files.readString(temporary.resolve("README.md")))
        assertEquals(TaskStatus.COMPLETED, task.status)
    }

    @Test
    fun `retries rejected patches and completes when a corrected diff applies`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("Greeting.java"), "Hello\n")
        val validPatch = listOf(
            "diff --git a/Greeting.java b/Greeting.java",
            "--- a/Greeting.java",
            "+++ b/Greeting.java",
            "@@ -1 +1 @@",
            "-Hello",
            "+Hello from Qwen"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"not a diff"}""")), "tool_calls", null, null),
            ModelTurn("The first patch was malformed.", emptyList(), "stop", null, null),
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"still not a diff"}""")), "tool_calls", null, null),
            ModelTurn("The second patch was malformed.", emptyList(), "stop", null, null),
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to validPatch)))),
                "tool_calls", null, null
            ),
            ModelTurn(
                """{"summary":"Updated greeting","implementation":"Applied corrected patch."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals("Hello from Qwen\n", Files.readString(temporary.resolve("Greeting.java")))
        assertTrue(provider.messages.any { it.role == "user" && it.content.contains("complete git-compatible unified diff") })
        assertEquals(2, provider.messages.count { it.role == "user" && it.content.contains("Your apply_patch tool call failed") })
        assertEquals(TaskStatus.COMPLETED, task.status)
    }

    @Test
    fun `fails after patch recovery is exhausted instead of asking the user to repair it`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("Greeting.java"), "Hello\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"bad diff 1"}""")), "tool_calls", null, null),
            ModelTurn("First patch failed.", emptyList(), "stop", null, null),
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"bad diff 2"}""")), "tool_calls", null, null),
            ModelTurn("Second patch failed.", emptyList(), "stop", null, null),
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"bad diff 3"}""")), "tool_calls", null, null),
            ModelTurn("Third patch failed.", emptyList(), "stop", null, null),
            ModelTurn(null, listOf(call("apply_patch", """{"patch":"bad diff 4"}""")), "tool_calls", null, null),
            ModelTurn("Fourth patch failed.", emptyList(), "stop", null, null),
            ModelTurn("I cannot apply this patch.", emptyList(), "stop", null, null),
            ModelTurn("I still cannot apply this patch.", emptyList(), "stop", null, null),
            ModelTurn("""{"summary":"Unable to apply patch","implementation":"The patch could not be applied."}""", emptyList(), "stop", null, null)
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary, emptyList())).execute(task.id)

        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(AttemptStatus.FAILED, attempts.finished?.status)
        assertEquals("The agent did not produce a repository change", attempts.finished?.errorSummary)
        assertTrue(attempts.finishedReport!!.openQuestions.isEmpty())
        assertEquals(3, provider.messages.count { it.role == "user" && it.content.contains("Your apply_patch tool call failed") })
        assertTrue(attempts.finishedReport!!.implementation.contains("Patch application still failed"))
    }

    @Test
    fun `missing repository and skipped validation produce actionable blocking questions`() {
        val noRepository = task(repository = null)
        val noRepositoryState = FakeState(noRepository)
        val noRepositoryAttempts = FakeAttempts()
        val neverCalled = ScriptedProvider()
        val noRepositoryWorker = worker(noRepositoryState, noRepositoryAttempts, neverCalled, FakeWorkspace(temporary))

        assertTrue(noRepositoryWorker.execute(noRepository.id))
        assertEquals(TaskStatus.NEEDS_INPUT, noRepository.status)
        assertTrue(noRepositoryAttempts.finishedReport!!.openQuestions.single().blocking)
        assertEquals(0, neverCalled.calls)

        val unsupported = task()
        val unsupportedAttempts = FakeAttempts()
        val unsupportedWorker = worker(
            FakeState(unsupported), unsupportedAttempts,
            ScriptedProvider(ModelTurn("The repository was inspected.", emptyList(), "stop", null, null)),
            FakeWorkspace(temporary),
            validation = ValidationCheck(
                "Maven tests",
                outcome = CheckOutcome.SKIPPED,
                details = "Gradle is not supported yet."
            )
        )
        assertTrue(unsupportedWorker.execute(unsupported.id))
        assertEquals(TaskStatus.NEEDS_INPUT, unsupported.status)
        assertTrue(unsupportedAttempts.finishedReport!!.openQuestions.any { it.question.contains("validation") && it.blocking })
    }

    @Test
    fun `failed validation and model errors fail the task with honest reports`() {
        val failedValidation = task()
        val failedAttempts = FakeAttempts()
        val failedWorker = worker(
            FakeState(failedValidation), failedAttempts,
            ScriptedProvider(ModelTurn("Patch complete.", emptyList(), "stop", null, null)),
            FakeWorkspace(temporary),
            validation = ValidationCheck(
                "Maven tests",
                outcome = CheckOutcome.FAILED,
                exitCode = 1,
                details = "Compilation failed"
            )
        )
        failedWorker.execute(failedValidation.id)
        assertEquals(TaskStatus.FAILED, failedValidation.status)
        assertEquals("Compilation failed", failedAttempts.finished?.errorSummary)
        assertTrue(failedAttempts.finishedReport!!.implementation.contains("Patch complete"))

        val modelFailed = task()
        val modelFailedAttempts = FakeAttempts()
        val modelFailedWorkspace = FakeWorkspace(temporary)
        val modelFailedWorker = worker(
            FakeState(modelFailed), modelFailedAttempts,
            ScriptedProvider(failure = IllegalStateException("model is offline")),
            modelFailedWorkspace
        )
        modelFailedWorker.execute(modelFailed.id)
        assertEquals(TaskStatus.FAILED, modelFailed.status)
        assertTrue(modelFailedAttempts.finishedReport!!.implementation.contains("model is offline"))
        assertTrue(modelFailedWorkspace.closed)
    }

    @Test
    fun `rejects unapproved tools and stops after the configured tool call budget`() {
        val rejected = task()
        val rejectedAttempts = FakeAttempts()
        val rejectedProvider = ScriptedProvider(
            ModelTurn(null, listOf(call("run_shell", "{}")), "tool_calls", null, null),
            ModelTurn("No shell tool was available.", emptyList(), "stop", null, null)
        )
        val rejectedWorker = worker(FakeState(rejected), rejectedAttempts, rejectedProvider, FakeWorkspace(temporary, emptyList()))
        rejectedWorker.execute(rejected.id)
        assertEquals(TaskStatus.FAILED, rejected.status)
        assertTrue(rejectedAttempts.finishedReport!!.openQuestions.isEmpty())
        assertTrue(rejectedProvider.messages.last().content.contains("not in the allowlist"))

        val overBudget = task()
        val budgetAttempts = FakeAttempts()
        val overBudgetProvider = ScriptedProvider(
            ModelTurn(
                null,
                listOf(call("read_file", """{"path":"README.md"}"""), call("read_file", """{"path":"README.md"}""")),
                "tool_calls",
                null,
                null
            )
        )
        val workspace = FakeWorkspace(temporary)
        Files.writeString(temporary.resolve("README.md"), "repository")
        val budgetWorker = worker(FakeState(overBudget), budgetAttempts, overBudgetProvider, workspace, maxToolCalls = 1)
        budgetWorker.execute(overBudget.id)
        assertEquals(TaskStatus.FAILED, overBudget.status)
        assertTrue(budgetAttempts.finishedReport!!.implementation.contains("exceeded its limit"))
    }

    @Test
    fun `handles failed claims workspace errors missing attempts and disabled polling`() {
        val emptyState = FakeState(null)
        val emptyWorker = worker(emptyState, FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), enabled = false)
        emptyWorker.poll()
        assertFalse(emptyWorker.processNext())
        assertFalse(emptyWorker.execute(UUID.randomUUID()))

        val failedClaimTask = task()
        val failedClaimAttempts = FakeAttempts(startFails = true)
        worker(FakeState(failedClaimTask), failedClaimAttempts, ScriptedProvider(), FakeWorkspace(temporary)).execute(failedClaimTask.id)
        assertEquals(TaskStatus.FAILED, failedClaimTask.status)
        assertNull(failedClaimAttempts.finished)

        val badWorkspaceTask = task()
        val badWorkspaceAttempts = FakeAttempts()
        val badWorkspaceWorker = TaskExecutionWorker(
            FakeState(badWorkspaceTask), badWorkspaceAttempts,
            TaskExecutionFinalizer(FakeState(badWorkspaceTask), badWorkspaceAttempts),
            ScriptedProvider(), object : RepositoryWorkspaceFactory {
                override fun open(repositoryUrl: String): RepositoryWorkspace =
                    throw IllegalArgumentException("invalid repository")
            }, tools(), mapper, true, 2
        )
        badWorkspaceWorker.execute(badWorkspaceTask.id)
        assertEquals(TaskStatus.FAILED, badWorkspaceTask.status)
        assertTrue(badWorkspaceAttempts.finishedReport!!.implementation.contains("invalid repository"))
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionWorker(
                FakeState(null),
                FakeAttempts(),
                TaskExecutionFinalizer(FakeState(null), FakeAttempts()),
                ScriptedProvider(),
                object : RepositoryWorkspaceFactory {
                    override fun open(repositoryUrl: String) = FakeWorkspace(temporary)
                },
                tools(),
                mapper,
                true,
                0
            )
        }
    }

    private fun worker(
        state: FakeState,
        attempts: FakeAttempts,
        provider: ScriptedProvider,
        workspace: FakeWorkspace,
        validation: ValidationCheck = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED),
        enabled: Boolean = true,
        maxToolCalls: Int = 5
    ): TaskExecutionWorker {
        val validationRunner = object : ValidationRunner {
            override fun validate(workspace: Path) = validation
        }
        val toolExecutor = WorkspaceToolExecutor(validationRunner, LocalCommandExecutor(), mapper)
        val workspaceFactory = object : RepositoryWorkspaceFactory {
            override fun open(repositoryUrl: String) = workspace
        }
        return TaskExecutionWorker(
            state, attempts, TaskExecutionFinalizer(state, attempts), provider, workspaceFactory,
            toolExecutor, mapper, enabled, maxToolCalls
        )
    }

    private fun tools() = WorkspaceToolExecutor(object : ValidationRunner {
        override fun validate(workspace: Path) = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
    }, LocalCommandExecutor(), mapper)

    private fun task(repository: String? = "local-repo") = CodingTask(
        id = UUID.randomUUID(),
        title = "Add request validation",
        description = "Inspect the project and make the requested change.",
        acceptanceCriteria = mutableListOf("existing tests pass"),
        repositoryUrl = repository,
        status = TaskStatus.QUEUED,
        createdAt = Instant.now()
    )

    private fun call(name: String, arguments: String) = ModelToolCall("tool-id", name, mapper.readTree(arguments))

    private inner class ScriptedProvider(vararg turns: ModelTurn, private val failure: Exception? = null) :
        CodingModelProvider {
        private val results = turns.toMutableList()
        var calls = 0
        val messages = mutableListOf<ModelMessage>()
        override fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn {
            this.messages.clear()
            this.messages.addAll(messages)
            calls++
            failure?.let { throw it }
            return results.removeFirstOrNull() ?: ModelTurn("", emptyList(), "stop", null, null)
        }
    }

    private class FakeState(task: CodingTask?) : TaskExecutionState {
        private val tasks = mutableMapOf<UUID, CodingTask>()
        val transitions = mutableListOf<TaskStatus>()
        init { if (task != null) tasks[task.id] = task }
        override fun claimNextQueued(): CodingTask? = tasks.values.firstOrNull { it.status == TaskStatus.QUEUED }?.also { claim(it.id) }
        override fun claim(id: UUID): CodingTask? = tasks[id]?.takeIf { it.status == TaskStatus.QUEUED }?.also {
            it.status = TaskStatus.RUNNING
            transitions += TaskStatus.RUNNING
        }
        override fun findById(id: UUID): CodingTask? = tasks[id]
        override fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String): CodingTask? = tasks[id]?.also {
            TaskLifecycle.requireAllowed(it.status, target)
            it.status = target
            transitions += target
        }
    }

    private class FakeAttempts(private val startFails: Boolean = false) : AttemptOperations {
        var started: TaskAttempt? = null
        var finished: TaskAttempt? = null
        var finishedReport: TaskExecutionReport? = null
        override fun start(taskId: UUID): TaskAttempt? {
            if (startFails) return null
            return TaskAttempt(taskId = taskId).also { started = it }
        }
        override fun finish(
            taskId: UUID, attemptId: UUID, status: AttemptStatus, report: TaskExecutionReport?, errorSummary: String?
        ): TaskAttempt? {
            val attempt = started?.takeIf { it.id == attemptId && it.taskId == taskId } ?: return null
            attempt.status = status
            attempt.errorSummary = errorSummary
            finished = attempt
            finishedReport = report
            return attempt
        }
    }

    private class FakeWorkspace(override val root: Path, private val files: List<String> = listOf("src/main/Example.kt")) : RepositoryWorkspace {
        var closed = false
        override fun changedFiles() = files
        override fun patchDiff(maxChars: Int) = "diff --git a/src/main/Example.kt b/src/main/Example.kt".take(maxChars)
        override fun close() { closed = true }
    }
}
