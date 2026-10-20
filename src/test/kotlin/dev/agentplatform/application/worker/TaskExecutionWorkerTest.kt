package dev.agentplatform.application.worker

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.model.*
import dev.agentplatform.domain.port.*
import dev.agentplatform.infrastructure.process.LocalCommandExecutor
import dev.agentplatform.infrastructure.tool.WorkspaceToolExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*

class TaskExecutionWorkerTest {
    @TempDir
    lateinit var temporary: Path
    private val mapper = ObjectMapper()

    @Test
    fun `claims and runs a task through inspection and model returned report`() {
        val criterion = "README includes the updated project context"
        val task = task(acceptanceCriteria = listOf(criterion))
        val state = FakeState(task)
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(temporary, listOf("README.md"))
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
            ModelTurn(
                null,
                listOf(
                    call("list_files", """{"path":"."}"""),
                    call("search_code", """{"path":".","query":"project context"}"""),
                    call("read_file", """{"path":"README.md"}""")
                ),
                "tool_calls",
                10,
                5
            ),
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                12,
                5
            ),
            ModelTurn(
                """{"summary":"Improved project","implementation":"Read the project and made the requested change.","decisions":[{"topic":"Testing","choice":"Keep the regression test","rationale":"The behavior needs a durable check.","alternatives":["Manual verification"]}],"openQuestions":[{"question":"Should this support Java too?","impact":"That expands the target matrix.","blocking":false}],"acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"The README now contains the updated project context."}]}""",
                emptyList(), "stop", 20, 8
            )
        )
        val publisher = FakePullRequestPublisher()
        val worker = worker(
            state, attempts, provider, workspace,
            publisher = publisher,
            inputCostPerMillionUsd = 2.0,
            outputCostPerMillionUsd = 4.0
        )

        assertTrue(worker.processNext())
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(listOf(TaskStatus.RUNNING, TaskStatus.VALIDATING, TaskStatus.COMPLETED), state.transitions)
        assertEquals(AttemptStatus.COMPLETED, attempts.finished?.status)
        val report = attempts.finishedReport!!
        assertEquals(
            "Changes were applied, reported acceptance criteria passed, and validation passed.",
            report.summary
        )
        assertEquals(criterion, report.acceptanceChecks.single().criterion)
        assertEquals(AcceptanceOutcome.PASSED, report.acceptanceChecks.single().outcome)
        assertTrue(report.acceptanceChecks.single().evidence!!.contains("README"))
        assertEquals(3, report.modelUsage!!.callCount)
        assertEquals(42, report.modelUsage.promptTokens)
        assertEquals(18, report.modelUsage.completionTokens)
        assertEquals(0.000156, report.modelUsage.estimatedCostUsd)
        assertEquals("qwen3:8b", report.modelUsage.model)
        assertEquals("Testing", report.decisions.single().topic)
        assertEquals(false, report.openQuestions.single().blocking)
        assertTrue(provider.messages.any { it.role == "tool" && it.content.contains("project context") })
        val assistantCall = provider.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals(listOf("list_files", "search_code", "read_file"), assistantCall.toolCalls.map { it.name })
        assertEquals("tool", provider.messages.last().role)
        assertEquals("apply_patch", provider.messages.last().toolName)
        assertTrue(provider.messages.last().content.contains("Patch applied"))
        assertEquals(
            provider.messages.filter { it.role == "assistant" && it.toolCalls.isNotEmpty() }.last().toolCalls.single().id,
            provider.messages.last().toolCallId
        )
        assertEquals(CheckOutcome.PASSED, report.validation.single().outcome)
        assertEquals(
            listOf("list_files", "search_code", "read_file", "apply_patch", "run_validation"),
            report.toolExecutions.map { it.toolName }
        )
        assertEquals(report.toolExecutions, attempts.persistedToolEvents)
        assertTrue(report.toolExecutions.all { it.outcome == ToolExecutionOutcome.SUCCEEDED })
        assertTrue(report.toolExecutions.none { it.argumentsSummary.contains(patch) })
        assertEquals(1, report.toolExecutions.first().sequenceNumber)
        assertTrue(report.toolExecutions[1].argumentsSummary.contains("queryLength"))
        assertFalse(report.toolExecutions[1].argumentsSummary.contains("project context"))
        assertTrue(report.maxExecutionDurationMillis!! > report.executionDurationMillis!!)
        assertEquals(1.0, report.maxEstimatedCostUsd)
        assertEquals("README.md", report.changedFiles.single())
        assertFalse(report.patchDiffTruncated)
        assertEquals("updated project context\n", Files.readString(temporary.resolve("README.md")))
        assertTrue(publisher.calls.isEmpty())
        assertTrue(workspace.closed)
    }

    @Test
    fun `recovers a final report wrapped in prose and a markdown fence`() {
        val criterion = "README includes the updated project context"
        val task = task(acceptanceCriteria = listOf(criterion))
        val state = FakeState(task)
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(temporary, listOf("README.md"))
        Files.writeString(temporary.resolve("README.md"), "project context\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-project context",
            "+updated project context"
        ).joinToString("\n", postfix = "\n")
        val reportJson =
            """{"implementation":"Applied the change.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README now reads updated project context."}]}"""
        val provider = ScriptedProvider(
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                1,
                1
            ),
            ModelTurn("Here is the report:\n```json\n$reportJson\n```\nDone.", emptyList(), "stop", 1, 1)
        )
        val worker = worker(state, attempts, provider, workspace)

        assertTrue(worker.processNext())
        assertEquals(TaskStatus.COMPLETED, task.status)
        val report = attempts.finishedReport!!
        assertEquals(AcceptanceOutcome.PASSED, report.acceptanceChecks.single().outcome)
        assertEquals("README now reads updated project context.", report.acceptanceChecks.single().evidence)
    }

    @Test
    fun `publishes a draft pull request only after a successful validated task`() {
        val task = task()
        val state = FakeState(task)
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val workspace = FakeWorkspace(temporary, listOf("README.md"))
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", 1, 1),
            ModelTurn("""{"implementation":"Applied the change."}""", emptyList(), "stop", 1, 1)
        )
        val publisher = FakePullRequestPublisher()

        worker(
            state, attempts, provider, workspace,
            publisher = publisher,
            publishPullRequests = true
        ).execute(task.id)

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(1, publisher.calls.size)
        assertEquals("agent/task-${task.id}", publisher.calls.single().branchName)
        assertEquals(listOf("README.md"), publisher.calls.single().changedFiles)
        assertEquals(42, attempts.finishedReport!!.pullRequest!!.number)
        assertEquals("https://github.com/example/repo/pull/42", attempts.finishedReport!!.pullRequest!!.url)
        assertTrue(attempts.finishedReport!!.summary.contains("pull request #42 (open, draft)"))
    }

    @Test
    fun `fails task and records publication error when draft PR cannot be opened`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", 1, 1),
            ModelTurn("""{"implementation":"Applied the change."}""", emptyList(), "stop", 1, 1)
        )
        val publisher = FakePullRequestPublisher(IllegalStateException("GitHub denied branch push"))

        worker(
            FakeState(task), attempts, provider, FakeWorkspace(temporary),
            publisher = publisher,
            publishPullRequests = true
        ).execute(task.id)

        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(AttemptStatus.FAILED, attempts.finished!!.status)
        assertTrue(attempts.finishedReport!!.summary.contains("publication failed"))
        assertEquals("GitHub denied branch push", attempts.finishedReport!!.pullRequestError)
        assertNull(attempts.finishedReport!!.pullRequest)
        assertEquals(CheckOutcome.PASSED, attempts.finishedReport!!.validation.single().outcome)
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
                """{"summary":"Patch failed","implementation":"The patch could not be applied."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals("Hello from Qwen\n", Files.readString(temporary.resolve("Greeting.java")))
        assertEquals(2, attempts.finishedReport!!.patchFailures.size)
        assertTrue(attempts.finishedReport!!.implementation.contains("Patch recovery: 2"))
        assertTrue(attempts.finishedReport!!.implementation.contains("not independently verified"))
        assertTrue(attempts.finishedReport!!.summary.contains("validation passed"))
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
    fun `does not complete a changed and validated task when acceptance criteria are unverified`() {
        val criterion = "README documents the new behavior"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn("""{"summary":"Updated README","implementation":"Updated the file."}""", emptyList(), "stop", null, null)
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.NEEDS_INPUT, task.status)
        assertEquals(AcceptanceOutcome.UNVERIFIED, attempts.finishedReport!!.acceptanceChecks.single().outcome)
        assertTrue(attempts.finishedReport!!.openQuestions.any { it.blocking && it.question.contains("Acceptance") })
        assertEquals(CheckOutcome.PASSED, attempts.finishedReport!!.validation.single().outcome)
    }

    @Test
    fun `re-asks for the report when the final answer cannot be parsed`() {
        val criterion = "README documents the new behavior"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn("""{"summary":"Updated README","decisions":[{"topic":"a"},{"topic":"b"}]""", emptyList(), "stop", null, null),
            ModelTurn(
                """{"summary":"Updated README","implementation":"Updated the file.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README now says new."}]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(3, provider.calls)
        assertTrue(provider.messages.last().content!!.contains("single valid JSON object"))
        assertEquals(AcceptanceOutcome.PASSED, attempts.finishedReport!!.acceptanceChecks.single().outcome)
    }

    @Test
    fun `stops re-asking after the bounded number of malformed reports`() {
        val criterion = "README documents the new behavior"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val broken = """{"summary":"Updated README","decisions":[{"topic":"a"}"""
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn(broken, emptyList(), "stop", null, null),
            ModelTurn(broken, emptyList(), "stop", null, null),
            ModelTurn(broken, emptyList(), "stop", null, null)
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.NEEDS_INPUT, task.status)
        assertEquals(4, provider.calls)
        assertEquals(AcceptanceOutcome.UNVERIFIED, attempts.finishedReport!!.acceptanceChecks.single().outcome)
    }

    @Test
    fun `drops the oldest tool results when the conversation exceeds the history budget`() {
        val criterion = "README lists the release notes"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        Files.writeString(temporary.resolve("First.java"), "A".repeat(6000))
        Files.writeString(temporary.resolve("Second.java"), "B".repeat(200))
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("read_file", """{"path":"First.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("read_file", """{"path":"Second.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", 10, 5),
            ModelTurn(
                """{"summary":"Updated README","implementation":"Added the release notes.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README lists them."}]}""",
                emptyList(), "stop", 10, 5
            )
        )

        worker(
            FakeState(task), attempts, provider, FakeWorkspace(temporary, listOf("README.md")),
            maxHistoryCharacters = "5000"
        ).execute(task.id)

        val request = provider.messages
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertTrue(
            request.none { it.role == "tool" && it.content.contains("AAAA") },
            "the oldest read must be dropped before the request is sent"
        )
        assertTrue(
            request.any { it.role == "tool" && it.content.contains("BBBB") },
            "the newest read must be kept"
        )
        assertTrue(request.any { it.role == "user" && it.content.contains("dropped from this conversation") })
        val note = request.single { it.role == "user" && it.content.contains("dropped from this conversation") }.content
        assertTrue(note.contains("Dropped: read_file(First.java)"), note)
        assertFalse(
            note.contains("Do not re-read a file whose contents you have already seen"),
            "the note must not forbid recovering content it just removed: $note"
        )
        val assistantCallIds = request.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id }.toSet()
        request.filter { it.role == "tool" }.forEach { result ->
            assertTrue(
                assistantCallIds.contains(result.toolCallId),
                "tool result ${result.toolCallId} must answer a retained assistant tool call"
            )
        }
        val characters = request.drop(2).sumOf { message ->
            message.content.length + message.toolCalls.sumOf { it.name.length + it.arguments.toString().length }
        }
        assertTrue(characters <= 5000, "the retained history ($characters characters) must fit the configured budget")
    }

    @Test
    fun `keeps tool results that fit the history budget even though the fixed prompt is larger`() {
        val criterion = "README lists the release notes"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        Files.writeString(temporary.resolve("First.java"), "A".repeat(2400))
        Files.writeString(temporary.resolve("Second.java"), "B".repeat(2400))
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("read_file", """{"path":"First.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("read_file", """{"path":"Second.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", 10, 5),
            ModelTurn(
                """{"summary":"Updated README","implementation":"Added the release notes.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README lists them."}]}""",
                emptyList(), "stop", 10, 5
            )
        )

        worker(
            FakeState(task), attempts, provider, FakeWorkspace(temporary, listOf("README.md")),
            maxHistoryCharacters = "6000"
        ).execute(task.id)

        val request = provider.messages
        assertTrue(request.any { it.role == "tool" && it.content.contains("AAAA") }, "the first read must be kept")
        assertTrue(request.any { it.role == "tool" && it.content.contains("BBBB") }, "the second read must be kept")
        assertTrue(request.none { it.role == "user" && it.content.contains("dropped from this conversation") })
    }

    @Test
    fun `keeps every tool result when the history budget is disabled`() {
        val criterion = "README lists the release notes"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        Files.writeString(temporary.resolve("First.java"), "A".repeat(6000))
        Files.writeString(temporary.resolve("Second.java"), "B".repeat(6000))
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("read_file", """{"path":"First.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("read_file", """{"path":"Second.java"}""")), "tool_calls", 10, 5),
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", 10, 5),
            ModelTurn(
                """{"summary":"Updated README","implementation":"Added the release notes.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README lists them."}]}""",
                emptyList(), "stop", 10, 5
            )
        )

        worker(
            FakeState(task), attempts, provider, FakeWorkspace(temporary, listOf("README.md")),
            maxHistoryCharacters = "disabled"
        ).execute(task.id)

        val request = provider.messages
        assertTrue(request.any { it.role == "tool" && it.content.contains("AAAA") })
        assertTrue(request.any { it.role == "tool" && it.content.contains("BBBB") })
        assertTrue(request.none { it.role == "user" && it.content.contains("dropped from this conversation") })
    }

    @Test
    fun `rejects a history budget that is neither a number nor disabled`() {
        assertThrows(IllegalArgumentException::class.java) {
            worker(
                FakeState(task()), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
                maxHistoryCharacters = "plenty"
            )
        }
    }

    @Test
    fun `reruns validation when the model patches after a successful validation`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("Greeting.java"), "Hello\n")
        val patch = listOf(
            "diff --git a/Greeting.java b/Greeting.java",
            "--- a/Greeting.java",
            "+++ b/Greeting.java",
            "@@ -1 +1 @@",
            "-Hello",
            "+Hello world"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("run_validation", "{}")), "tool_calls", null, null),
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                null,
                null
            ),
            ModelTurn(
                """{"summary":"Updated greeting","implementation":"Updated the greeting after validation."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )
        var validationCalls = 0
        val validationRunner = object : ValidationRunner {
            override fun validate(workspace: Path): ValidationCheck {
                validationCalls++
                return ValidationCheck(
                    "Maven tests",
                    outcome = if (validationCalls == 1) CheckOutcome.PASSED else CheckOutcome.FAILED,
                    details = if (validationCalls == 1) null else "Post-patch tests failed"
                )
            }
        }

        worker(
            FakeState(task), attempts, provider, FakeWorkspace(temporary),
            validationRunner = validationRunner
        ).execute(task.id)

        assertEquals(2, validationCalls)
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(2, attempts.finishedReport!!.validation.size)
        assertEquals(CheckOutcome.FAILED, attempts.finishedReport!!.validation.last().outcome)
        assertEquals("Post-patch tests failed", attempts.finished?.errorSummary)
    }

    @Test
    fun `fails a changed and validated task when an acceptance criterion is reported as failed`() {
        val criterion = "Greeting returns the requested exact text"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn(
                """{"summary":"Incomplete","implementation":"The requested behavior is not present.","acceptanceChecks":[{"criterion":"$criterion","outcome":"FAILED","evidence":"The expected text was not found."}]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(AcceptanceOutcome.FAILED, attempts.finishedReport!!.acceptanceChecks.single().outcome)
        assertTrue(attempts.finished?.errorSummary!!.contains("acceptance criteria"))
    }

    @Test
    fun `rejects duplicate or evidence-free acceptance assessments as unverified`() {
        val criterion = "README documents the new behavior"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn(
                """{"summary":"Updated","implementation":"Updated the file.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"It was updated."},{"criterion":"$criterion","outcome":"PASSED","evidence":"Duplicate assertion."}]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.NEEDS_INPUT, task.status)
        assertEquals(AcceptanceOutcome.UNVERIFIED, attempts.finishedReport!!.acceptanceChecks.single().outcome)
    }

    @Test
    fun `requires evidence and recognized outcomes for acceptance assessments`() {
        val first = "README explains the behavior"
        val second = "README includes an example"
        val task = task(acceptanceCriteria = listOf(first, second))
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), "old\n")
        val patch = listOf(
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-old",
            "+new"
        ).joinToString("\n", postfix = "\n")
        val provider = ScriptedProvider(
            ModelTurn(null, listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))), "tool_calls", null, null),
            ModelTurn(
                """{"summary":"Updated","implementation":"Updated the file.","acceptanceChecks":[{"criterion":"$first","outcome":"PASSED"},{"criterion":"$second","outcome":"UNKNOWN","evidence":"Claimed without a valid outcome."}]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        assertEquals(TaskStatus.NEEDS_INPUT, task.status)
        assertTrue(attempts.finishedReport!!.acceptanceChecks.all { it.outcome == AcceptanceOutcome.UNVERIFIED })
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
        assertEquals(
            ToolExecutionOutcome.REJECTED,
            rejectedAttempts.finishedReport!!.toolExecutions.single { it.toolName == "run_shell" }.outcome
        )
        assertEquals(rejectedAttempts.finishedReport!!.toolExecutions, rejectedAttempts.persistedToolEvents)

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
        assertEquals(
            listOf(ToolExecutionOutcome.SUCCEEDED, ToolExecutionOutcome.REJECTED),
            budgetAttempts.persistedToolEvents.map { it.outcome }
        )
    }

    @Test
    fun `records a deliberate refusal as rejected instead of a tool failure`() {
        val task = task()
        val attempts = FakeAttempts()
        val provider = ScriptedProvider(
            ModelTurn(
                null,
                listOf(call("read_file", """{"path":"../outside.txt"}""")),
                "tool_calls",
                null,
                null
            ),
            ModelTurn(
                """{"summary":"Refused","implementation":"The path was refused.","acceptanceChecks":[]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        val refusal = attempts.finishedReport!!.toolExecutions.single { it.toolName == "read_file" }
        assertEquals(ToolExecutionOutcome.REJECTED, refusal.outcome)
        assertTrue(refusal.resultSummary.startsWith("Rejected: "), refusal.resultSummary)
        assertTrue(refusal.resultSummary.contains("Path must stay inside the repository"), refusal.resultSummary)
        // The model receives the actionable message, not an opaque "tool failed" line.
        val modelFacing = provider.messages.joinToString("\n") { it.content }
        assertTrue(modelFacing.contains("Rejected: Path must stay inside the repository"), modelFacing)
        assertFalse(modelFacing.contains("Tool failed safely"))
    }

    @Test
    fun `records the requested read range in the tool audit`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(temporary.resolve("README.md"), (1..10).joinToString("\n") { "line $it" })
        val provider = ScriptedProvider(
            ModelTurn(
                null,
                listOf(call("read_file", """{"path":"README.md","startLine":2,"endLine":5}""")),
                "tool_calls",
                null,
                null
            ),
            ModelTurn(
                """{"summary":"Read","implementation":"Read the requested lines.","acceptanceChecks":[]}""",
                emptyList(), "stop", null, null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        val read = attempts.finishedReport!!.toolExecutions.single { it.toolName == "read_file" }
        assertEquals("path=README.md, lines=2-5", read.argumentsSummary)
    }

    @Test
    fun `estimated cost and elapsed time limits stop execution and are reported`() {
        val costTask = task()
        val costAttempts = FakeAttempts()
        val costProvider = ScriptedProvider(ModelTurn("No changes.", emptyList(), "stop", 100, 100))
        worker(
            FakeState(costTask), costAttempts, costProvider, FakeWorkspace(temporary),
            inputCostPerMillionUsd = 100.0, outputCostPerMillionUsd = 100.0, maxEstimatedCostUsd = 0.01
        ).execute(costTask.id)
        assertEquals(TaskStatus.FAILED, costTask.status)
        assertTrue(costAttempts.finishedReport!!.executionBudgetExceeded!!.contains("cost budget"))
        assertEquals(0, costAttempts.finishedReport!!.toolExecutions.size)

        val unknownCostTask = task()
        val unknownCostAttempts = FakeAttempts()
        worker(
            FakeState(unknownCostTask), unknownCostAttempts,
            ScriptedProvider(ModelTurn("No changes.", emptyList(), "stop", null, null)),
            FakeWorkspace(temporary), inputCostPerMillionUsd = 1.0
        ).execute(unknownCostTask.id)
        assertEquals(TaskStatus.FAILED, unknownCostTask.status)
        assertTrue(unknownCostAttempts.finishedReport!!.executionBudgetExceeded!!.contains("usage is unavailable"))

        val priorCostTask = task()
        val priorCostAttempts = FakeAttempts(previousBudgetUsage = TaskBudgetUsage(0, 0.02))
        val unusedProvider = ScriptedProvider()
        worker(
            FakeState(priorCostTask), priorCostAttempts, unusedProvider, FakeWorkspace(temporary),
            maxEstimatedCostUsd = 0.01
        ).execute(priorCostTask.id)
        assertEquals(TaskStatus.FAILED, priorCostTask.status)
        assertTrue(priorCostAttempts.finishedReport!!.executionBudgetExceeded!!.contains("cost budget"))
        assertEquals(0, unusedProvider.calls)

        val priorTimeTask = task()
        val priorTimeAttempts = FakeAttempts(previousBudgetUsage = TaskBudgetUsage(1001, 0.0))
        worker(
            FakeState(priorTimeTask), priorTimeAttempts, ScriptedProvider(), FakeWorkspace(temporary),
            maxDurationSeconds = 1
        ).execute(priorTimeTask.id)
        assertEquals(TaskStatus.FAILED, priorTimeTask.status)
        assertTrue(priorTimeAttempts.finishedReport!!.executionBudgetExceeded!!.contains("time budget"))

        val unknownTimeTask = task()
        val unknownTimeAttempts = FakeAttempts(previousBudgetUsage = TaskBudgetUsage(null, null))
        worker(
            FakeState(unknownTimeTask), unknownTimeAttempts, ScriptedProvider(), FakeWorkspace(temporary)
        ).execute(unknownTimeTask.id)
        assertEquals(TaskStatus.FAILED, unknownTimeTask.status)
        assertTrue(unknownTimeAttempts.finishedReport!!.executionBudgetExceeded!!.contains("duration is unavailable"))

        val timeTask = task()
        val timeAttempts = FakeAttempts()
        val slowProvider = object : CodingModelProvider {
            override val modelName: String = "qwen3:8b"
            override fun chat(messages: List<ModelMessage>, tools: List<ModelToolDefinition>): ModelTurn {
                Thread.sleep(1100)
                return ModelTurn("No changes.", emptyList(), "stop", 1, 1)
            }
        }
        val timeWorker = TaskExecutionWorker(
            FakeState(timeTask), timeAttempts, TaskExecutionFinalizer(FakeState(timeTask), timeAttempts, ExecutionMetrics.NONE),
            slowProvider, object : RepositoryWorkspaceFactory {
                override fun open(repositoryUrl: String) = FakeWorkspace(temporary)
            }, tools(), FakePullRequestPublisher(), mapper, true, false, 5, 0.0, 0.0,
            maxDurationSeconds = 1
        )
        timeWorker.execute(timeTask.id)
        assertEquals(TaskStatus.FAILED, timeTask.status)
        assertTrue(timeAttempts.finishedReport!!.executionBudgetExceeded!!.contains("time budget"))
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
            TaskExecutionFinalizer(FakeState(badWorkspaceTask), badWorkspaceAttempts, ExecutionMetrics.NONE),
            ScriptedProvider(), object : RepositoryWorkspaceFactory {
                override fun open(repositoryUrl: String): RepositoryWorkspace =
                    throw IllegalArgumentException("invalid repository")
            }, tools(), FakePullRequestPublisher(), mapper, true, false, 2, 0.0, 0.0
        )
        badWorkspaceWorker.execute(badWorkspaceTask.id)
        assertEquals(TaskStatus.FAILED, badWorkspaceTask.status)
        assertTrue(badWorkspaceAttempts.finishedReport!!.implementation.contains("invalid repository"))
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionWorker(
                FakeState(null),
                FakeAttempts(),
                TaskExecutionFinalizer(FakeState(null), FakeAttempts(), ExecutionMetrics.NONE),
                ScriptedProvider(),
                object : RepositoryWorkspaceFactory {
                    override fun open(repositoryUrl: String) = FakeWorkspace(temporary)
                },
                tools(),
                FakePullRequestPublisher(),
                mapper,
                true,
                false,
                0,
                0.0,
                0.0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), inputCostPerMillionUsd = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), inputCostPerMillionUsd = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), outputCostPerMillionUsd = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), outputCostPerMillionUsd = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), maxDurationSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(
                FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
                maxDurationSeconds = Long.MAX_VALUE
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary), maxEstimatedCostUsd = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(
                FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
                maxEstimatedCostUsd = Double.NaN
            )
        }
    }

    @Test
    fun `poll handles empty queues disabled workers and claim failures`() {
        worker(FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary)).poll()
        worker(
            FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
            workerEnabled = false
        ).poll()

        val failedState = object : TaskExecutionState {
            override fun claimNextQueued(): CodingTask? = throw IllegalStateException("database unavailable")
            override fun claim(id: UUID): CodingTask? = null
            override fun findById(id: UUID): CodingTask? = null
            override fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String): CodingTask? = null
        }
        val attempts = FakeAttempts()
        TaskExecutionWorker(
            failedState,
            attempts,
            TaskExecutionFinalizer(failedState, attempts, ExecutionMetrics.NONE),
            ScriptedProvider(),
            object : RepositoryWorkspaceFactory {
                override fun open(repositoryUrl: String) = FakeWorkspace(temporary)
            },
            tools(),
            FakePullRequestPublisher(),
            mapper,
            true,
            false,
            5,
            0.0,
            0.0
        ).poll()
    }

    @Test
    fun `reports model failures even when workspace diagnostics and cleanup also fail`() {
        val task = task()
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(
            temporary,
            changedFilesFailure = IllegalStateException("git status failed"),
            patchDiffFailure = IllegalStateException("git diff failed"),
            closeFailure = IllegalStateException("workspace cleanup failed")
        )

        worker(FakeState(task), attempts, ScriptedProvider(failure = IllegalStateException()), workspace)
            .execute(task.id)

        assertEquals(TaskStatus.FAILED, task.status)
        assertTrue(attempts.finishedReport!!.implementation.contains("IllegalStateException"))
        assertTrue(attempts.finishedReport!!.changedFiles.isEmpty())
        assertNull(attempts.finishedReport!!.patchDiff)
        assertTrue(workspace.closed)
    }

    @Test
    fun `records when the stored patch was truncated`() {
        val criterion = "README is updated"
        val task = task(acceptanceCriteria = listOf(criterion))
        val attempts = FakeAttempts()
        val workspace = FakeWorkspace(temporary, listOf("README.md"), truncated = true)
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
            ModelTurn(
                null,
                listOf(call("apply_patch", mapper.writeValueAsString(mapOf("patch" to patch)))),
                "tool_calls",
                10,
                5
            ),
            ModelTurn(
                """{"summary":"Changed the README","implementation":"The README was updated.","acceptanceChecks":[{"criterion":"$criterion","outcome":"PASSED","evidence":"README now says updated project context."}]}""",
                emptyList(),
                "stop",
                10,
                5
            )
        )
        val worker = worker(FakeState(task), attempts, provider, workspace)

        assertTrue(worker.processNext())
        assertEquals(TaskStatus.COMPLETED, task.status)
        val report = attempts.finishedReport!!
        assertEquals("README.md", report.changedFiles.single())
        assertNotNull(report.patchDiff)
        assertTrue(report.patchDiffTruncated)
    }

    @Test
    fun `reaps an attempt orphaned by a stopped worker and fails the task`() {
        val task = task().also { it.status = TaskStatus.RUNNING }
        val state = FakeState(task)
        val orphan = TaskAttempt(
            taskId = task.id,
            attemptNumber = 1,
            status = AttemptStatus.RUNNING,
            startedAt = Instant.now().minusSeconds(600)
        )
        val attempts = FakeAttempts(runningAttempts = listOf(orphan))
        val worker = worker(
            state, attempts, ScriptedProvider(), FakeWorkspace(temporary),
            maxDurationSeconds = 30, staleAttemptTimeoutSeconds = "60"
        )

        assertEquals(1, worker.reapOrphanedAttempts())

        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(AttemptStatus.FAILED, attempts.finished?.status)
        assertTrue(attempts.finishedReport!!.summary.contains("worker stopped"))
        assertTrue(attempts.finishedReport!!.implementation.contains("outcome is unknown"))
    }

    @Test
    fun `leaves a running attempt alone until it exceeds the stale timeout`() {
        val task = task().also { it.status = TaskStatus.RUNNING }
        val state = FakeState(task)
        val running = TaskAttempt(
            taskId = task.id,
            attemptNumber = 1,
            status = AttemptStatus.RUNNING,
            startedAt = Instant.now()
        )
        val attempts = FakeAttempts(runningAttempts = listOf(running))
        val worker = worker(
            state, attempts, ScriptedProvider(), FakeWorkspace(temporary),
            maxDurationSeconds = 30, staleAttemptTimeoutSeconds = "60"
        )

        assertEquals(0, worker.reapOrphanedAttempts())

        assertEquals(TaskStatus.RUNNING, task.status)
        assertNull(attempts.finished)
    }

    @Test
    fun `rejects a stale attempt timeout that does not exceed the task duration`() {
        assertThrows(IllegalArgumentException::class.java) {
            worker(
                FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
                maxDurationSeconds = 1800, staleAttemptTimeoutSeconds = "900"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker(
                FakeState(null), FakeAttempts(), ScriptedProvider(), FakeWorkspace(temporary),
                staleAttemptTimeoutSeconds = "not-a-number"
            )
        }
    }

    @Test
    fun `prepends a ranked repository map to the task prompt`() {
        val task = task()
        val attempts = FakeAttempts()
        Files.writeString(
            temporary.resolve("Greeting.java"),
            "public class Greeting { public String message() { return \"hi\"; } }\n"
        )
        val provider = ScriptedProvider(
            ModelTurn(
                """{"summary":"No change","implementation":"The repository map was supplied."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )

        worker(FakeState(task), attempts, provider, FakeWorkspace(temporary)).execute(task.id)

        val prompt = provider.messages.first { it.role == "user" }.content
        assertTrue(prompt.contains("Repository map (ranked from the task text"))
        assertTrue(prompt.contains("Greeting.java"))
        val system = provider.messages.first { it.role == "system" }.content
        assertTrue(system.contains("read the enclosing declaration or block with read_file before you change it"))
        assertTrue(system.contains("a four-line window around a method's opening often stops before it"))
    }

    @Test
    fun `tells the model to update tests that assert the changed behavior`() {
        val task = task()
        val provider = ScriptedProvider(
            ModelTurn(
                """{"summary":"No change","implementation":"Prompt check."}""",
                emptyList(),
                "stop",
                null,
                null
            )
        )

        worker(FakeState(task), FakeAttempts(), provider, FakeWorkspace(temporary)).execute(task.id)

        val system = provider.messages.first { it.role == "system" }.content
        val user = provider.messages.first { it.role == "user" }.content
        assertTrue(
            system.contains("a behavior change is not finished while an existing test still asserts the old behavior"),
            system
        )
        assertTrue(system.contains("fix that test and rerun validation"), system)
        assertTrue(user.contains("updates any existing test asserting the old behavior"), user)
    }

    private fun worker(
        state: FakeState,
        attempts: FakeAttempts,
        provider: ScriptedProvider,
        workspace: FakeWorkspace,
        validation: ValidationCheck = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED),
        enabled: Boolean = true,
        maxToolCalls: Int = 5,
        inputCostPerMillionUsd: Double = 0.0,
        outputCostPerMillionUsd: Double = 0.0,
        validationRunner: ValidationRunner? = null,
        publisher: FakePullRequestPublisher = FakePullRequestPublisher(),
        publishPullRequests: Boolean = false,
        maxDurationSeconds: Long = 1800,
        maxEstimatedCostUsd: Double = 1.0,
        workerEnabled: Boolean = true,
        staleAttemptTimeoutSeconds: String = "disabled",
        maxHistoryCharacters: String = "24000"
    ): TaskExecutionWorker {
        val defaultValidationRunner = object : ValidationRunner {
            override fun validate(workspace: Path) = validation
        }
        val toolExecutor = WorkspaceToolExecutor(validationRunner ?: defaultValidationRunner, LocalCommandExecutor(), mapper, ExecutionMetrics.NONE)
        val workspaceFactory = object : RepositoryWorkspaceFactory {
            override fun open(repositoryUrl: String) = workspace
        }
        return TaskExecutionWorker(
            state, attempts, TaskExecutionFinalizer(state, attempts, ExecutionMetrics.NONE), provider, workspaceFactory,
            toolExecutor, publisher, mapper, enabled, publishPullRequests, maxToolCalls,
            inputCostPerMillionUsd, outputCostPerMillionUsd, maxDurationSeconds, maxEstimatedCostUsd, workerEnabled,
            staleAttemptTimeoutSeconds, maxHistoryCharacters
        )
    }

    private fun tools() = WorkspaceToolExecutor(object : ValidationRunner {
        override fun validate(workspace: Path) = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
    }, LocalCommandExecutor(), mapper, ExecutionMetrics.NONE)

    private fun task(
        repository: String? = "local-repo",
        acceptanceCriteria: List<String> = emptyList()
    ) = CodingTask(
        id = UUID.randomUUID(),
        title = "Add request validation",
        description = "Inspect the project and make the requested change.",
        acceptanceCriteria = acceptanceCriteria.toMutableList(),
        repositoryUrl = repository,
        status = TaskStatus.QUEUED,
        createdAt = Instant.now()
    )

    private fun call(name: String, arguments: String) = ModelToolCall("tool-id", name, mapper.readTree(arguments))

    private inner class ScriptedProvider(vararg turns: ModelTurn, private val failure: Exception? = null) :
        CodingModelProvider {
        override val modelName: String = "qwen3:8b"
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

    private class FakeAttempts(
        private val startFails: Boolean = false,
        private val previousBudgetUsage: TaskBudgetUsage = TaskBudgetUsage(),
        private val runningAttempts: List<TaskAttempt> = emptyList()
    ) : AttemptOperations {
        var started: TaskAttempt? = null
        var finished: TaskAttempt? = null
        var finishedReport: TaskExecutionReport? = null
        val persistedToolEvents = mutableListOf<ToolExecutionAudit>()
        override fun priorBudgetUsage(taskId: UUID) = previousBudgetUsage
        override fun runningAttemptsStartedBefore(startedBefore: Instant): List<TaskAttempt> =
            runningAttempts.filter { it.status == AttemptStatus.RUNNING && it.startedAt.isBefore(startedBefore) }
        override fun recordToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {
            persistedToolEvents += audit
        }
        override fun finishToolExecution(taskId: UUID, attemptId: UUID, audit: ToolExecutionAudit) {
            val index = persistedToolEvents.indexOfFirst { it.sequenceNumber == audit.sequenceNumber }
            check(index >= 0)
            persistedToolEvents[index] = audit
        }
        override fun toolExecutions(taskId: UUID, attemptId: UUID) = persistedToolEvents.toList()
        override fun start(taskId: UUID): TaskAttempt? {
            if (startFails) return null
            return TaskAttempt(taskId = taskId).also { started = it }
        }
        override fun finish(
            taskId: UUID, attemptId: UUID, status: AttemptStatus, report: TaskExecutionReport?, errorSummary: String?
        ): TaskAttempt? {
            val attempt = started?.takeIf { it.id == attemptId && it.taskId == taskId }
                ?: runningAttempts.firstOrNull { it.id == attemptId && it.taskId == taskId }
                ?: return null
            attempt.status = status
            attempt.errorSummary = errorSummary
            finished = attempt
            finishedReport = report
            return attempt
        }
    }

    private class FakePullRequestPublisher(private val failure: Exception? = null) : PullRequestPublisher {
        data class Request(
            val repositoryUrl: String,
            val branchName: String,
            val title: String,
            val body: String,
            val changedFiles: List<String>
        )

        val calls = mutableListOf<Request>()

        override fun createDraft(
            repositoryUrl: String,
            workspace: Path,
            branchName: String,
            title: String,
            body: String,
            changedFiles: List<String>
        ): PullRequestReference {
            calls += Request(repositoryUrl, branchName, title, body, changedFiles)
            failure?.let { throw it }
            return PullRequestReference(42, "https://github.com/example/repo/pull/42", title)
        }
    }

    private class FakeWorkspace(
        override val root: Path,
        private val files: List<String> = listOf("src/main/Example.kt"),
        private val changedFilesFailure: Exception? = null,
        private val patchDiffFailure: Exception? = null,
        private val closeFailure: Exception? = null,
        private val truncated: Boolean = false
    ) : RepositoryWorkspace {
        var closed = false
        override fun changedFiles(): List<String> {
            changedFilesFailure?.let { throw it }
            return files
        }
        override fun patchDiff(maxChars: Int): PatchDiff {
            patchDiffFailure?.let { throw it }
            return PatchDiff("diff --git a/src/main/Example.kt b/src/main/Example.kt".take(maxChars), truncated)
        }
        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
