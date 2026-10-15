# Coding Agent Platform

A Kotlin + Quarkus project for a controlled software engineering agent. The intended product takes a GitHub issue, inspects one repository, proposes a patch, runs validation in isolation, and opens a pull request for a person to review.

The project now runs a bounded, asynchronous coding-agent workflow: it accepts tasks over REST, persists task and attempt state to PostgreSQL, inspects an authorized repository, asks local Ollama for changes through an allow-listed tool loop, applies a patch in a disposable workspace, and runs Maven tests in an isolated Docker environment. When explicitly enabled and configured for an allow-listed public GitHub repository, it can push a task branch and open a draft pull request after validation. It returns a report with changed files, a diff, validation results, decisions, open questions, and (when enabled) the pull-request URL. GitHub issue ingestion and private-repository clone authentication are not implemented.

## Product goals

The engineering challenge is making the workflow dependable and safe, not adding a chat window around a coding model. The eventual system should:

- keep durable task, attempt, state-transition, validation, and audit records;
- use a small allow-listed set of tools whose inputs and effects are checked by the application;
- run repository code in a disposable, resource-limited environment with no production secrets;
- bound model calls, tool calls, elapsed time, and spend;
- recover from duplicate work, worker crashes, and restarts without opening duplicate pull requests;
- report actual validation results and limitations, then leave merge approval to a human;
- measure success and failure against a repeatable benchmark instead of claiming unmeasured improvements.

## Current implementation

### API

| Method | Endpoint | Behavior |
| --- | --- | --- |
| `POST` | `/api/tasks` | Validate required title and description; create a task with `QUEUED` status; return `201` and a `Location` header. |
| `GET` | `/api/tasks` | List persisted tasks, newest first. |
| `GET` | `/api/tasks/{id}` | Return a task or `404`. |
| `PATCH` | `/api/tasks/{id}/status` | Apply a permitted status transition; return `409` for an invalid transition and `404` for an unknown task. |
| `GET` | `/api/tasks/{id}/history` | Return ordered, append-only status history, including the initial `QUEUED` entry. |
| `GET` | `/api/tasks/{id}/attempts` | Return attempts in attempt-number order, with status, report, and failure summary. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}` | Return one attempt and its structured execution report. |

Create-task fields are `title`, `description`, `acceptanceCriteria` (optional list of up to 30 items, each at most 1000 characters), and `repositoryUrl` (required for execution). A task has a UUID, creation timestamp, and status. Acceptance criteria retain their input order. Transitions are enforced by the service and recorded with sequence number, prior/new status, optional reason, actor, and timestamp. The Quarkus scheduler invokes a single-process worker every two seconds; startup, poll ticks, and successful claims are logged, including whether agent execution is enabled. The worker claims queued tasks and creates a durable attempt. Task creation currently returns `201` with `QUEUED`; the worker executes asynchronously after persistence. `AGENT_EXECUTION_ENABLED=false` leaves polling active but skips task execution; Quarkus scheduler configuration can independently disable all scheduled jobs.

`CodingModelProvider` defines a provider-independent chat/tool-call contract. `OllamaCodingModelProvider` implements Ollama's `/api/chat` protocol, including structured assistant tool-call history and named tool-result messages, tool schemas, usage counts, timeouts, and bounded error messages. For models that return a JSON tool request in message content instead of Ollama's native `tool_calls`, it normalizes that response only when the requested tool was offered to the model. The adapter is configured with `OLLAMA_BASE_URL` (default `http://localhost:11434`), `OLLAMA_MODEL` (default `qwen3:8b`), and `OLLAMA_TIMEOUT_SECONDS` (default `1200`). It does not download models.

The current local default is `qwen3:8b`. If needed, install it with `ollama pull qwen3:8b`, then check it appears in `ollama list`. Ollama must be running at the configured base URL.

The worker's tool allowlist is `list_files`, `search_code`, `read_file`, `apply_patch`, and `run_validation`; it offers no shell, arbitrary command, network, credential, or GitHub-write tool. Paths and patch size are checked by the application. Local repository access is disabled unless `AGENT_ALLOWED_LOCAL_ROOT` is configured; public GitHub HTTPS URLs are also accepted. A task report includes the observed outcome, model-provided narrative (explicitly marked as unverified), changed files, diff, validation outcomes, patch failures/recovery, acceptance checks, model token usage, estimated model cost, and elapsed time. Attempts can finish as `COMPLETED`, `FAILED`, or `NEEDS_INPUT`; failures retain a bounded summary and the report. Patch failures receive up to three bounded correction attempts and are retained even when a later patch succeeds. A task that makes no repository change is `FAILED` unless it needs a genuine blocking decision.

When acceptance criteria are supplied, the agent must return one evidence-bearing assessment per criterion. Missing or evidence-free checks prevent completion; failed criteria fail the task, and unverified criteria require input. This is a model assessment, not independent proof: Maven tests and benchmark-specific expected-diff checks provide separate machine-verifiable evidence. A task is completed only when the workspace changed, all supplied criteria are reported as passed, and supported validation passed.

#### Draft pull-request publishing

Pull-request publishing is disabled by default. Set `AGENT_GITHUB_PULL_REQUESTS_ENABLED=true`, `AGENT_GITHUB_TOKEN` to a fine-grained token with **Contents: Read and write** and **Pull requests: Read and write**, and `AGENT_GITHUB_ALLOWED_REPOSITORIES=owner/repository` before starting the app. Keep the token outside source control. The task must use the matching HTTPS GitHub repository URL, and the repository must be publicly cloneable. Publishing commits only the changed paths, pushes a task-specific branch, and creates a draft PR against the repository default branch. If publication is retried after a PR was created, it records the existing PR rather than opening a duplicate; the report includes whether that PR is still open and in draft. The PR contains task context and reported checks; acceptance checks are explicitly labeled as model-assessed. The token is not sent to the model or test container. Pull requests are never merged automatically.

If branch push or PR creation fails, the task is marked `FAILED` with `pullRequestError` in its persisted attempt report; a successfully pushed branch may remain and require manual cleanup. This first slice does not yet provide GitHub issue webhooks, GitHub App installation tokens, durable retry/recovery after partial publication, or a UI for reviewing the PR from the task page.

Validation currently supports Maven projects only and runs `mvn -B -o test` in a disposable Linux Docker container with resource limits, a private internal network, a temporary PostgreSQL 18 service, and the host Maven artifact cache mounted read-only. Repository tests run without external network access. If required artifacts are missing, a separate bounded dependency-fetch container may download only Maven coordinates parsed from the missing-artifact diagnostics into the host cache before the test container is retried offline. Gradle, other build systems, and network-enabled builds are not supported.

Allowed transitions:

```text
QUEUED ──→ RUNNING ──→ VALIDATING ──→ COMPLETED
   │          │              ├──→ FAILED
   │          │              ├──→ NEEDS_INPUT
   │          │              └──→ CANCELLED
   │          ├──→ FAILED
   │          ├──→ NEEDS_INPUT
   │          └──→ CANCELLED
   └──→ CANCELLED
```

`COMPLETED`, `FAILED`, `NEEDS_INPUT`, and `CANCELLED` are terminal. Invalid and repeated transitions are rejected without changing task state or writing history.

### Architecture and code map

The codebase follows a **hexagonal (ports-and-adapters) architecture** with four layers. Dependencies point inward: infrastructure and API depend on domain and application, but domain has no outward dependencies.

```text
┌──────────────────────────────────────────────────────────┐
│                        api                               │
│   REST resources, DTOs, input validation                 │
├──────────────────────────────────────────────────────────┤
│                    application                           │
│   Use-case services, orchestration, worker loop          │
├──────────────────────────────────────────────────────────┤
│                      domain                              │
│   Entities, value objects, lifecycle policy, ports (SPI) │
├──────────────────────────────────────────────────────────┤
│                   infrastructure                         │
│   Persistence, Ollama HTTP, Git workspaces, Docker,      │
│   process execution, tool execution                      │
└──────────────────────────────────────────────────────────┘
```

#### `dev.agentplatform.api` — Inbound adapters (REST)

- `api/TaskResource.kt` — REST routes, input validation, HTTP status codes.
- `api/dto/TaskDtos.kt` — API data transfer objects: `CreateTaskRequest`, `TransitionTaskRequest`, `TaskStatusTransitionResponse`, `TaskAttemptResponse`, `ApiError`.

#### `dev.agentplatform.domain` — Core business logic (no framework dependencies)

- `domain/model/CodingTask.kt` — Task entity with UUID, timestamps, and ordered acceptance criteria.
- `domain/model/TaskStatus.kt` — `TaskStatus` enum (`QUEUED`, `RUNNING`, `VALIDATING`, `COMPLETED`, `FAILED`, `NEEDS_INPUT`, `CANCELLED`).
- `domain/model/TaskStatusTransition.kt` — Append-only status history entity.
- `domain/model/TaskLifecycle.kt` — State machine policy: allowed transitions and `InvalidTaskTransitionException`.
- `domain/model/TaskAttempt.kt` — Attempt entity and `AttemptStatus` enum.
- `domain/model/TaskExecutionReport.kt` — Value objects: `TaskExecutionReport`, `AgentDecision`, `AgentQuestion`, `AcceptanceCheck`, `ModelUsage`, `PullRequestReference`, `ValidationCheck`, `CheckOutcome`.
- `domain/port/CodingModelProvider.kt` — Provider-neutral LLM chat/tool-calling SPI (messages, tool definitions, tool calls, turns).
- `domain/port/ExecutionPorts.kt` — Outbound port interfaces: `TaskExecutionState`, `AttemptOperations`, `RepositoryWorkspaceFactory`, `RepositoryWorkspace`, `CommandExecutor`, `CommandResult`, `ValidationRunner`, `PullRequestPublisher`, `ToolExecutionResult`.

#### `dev.agentplatform.application` — Use cases and orchestration

- `application/service/TaskService.kt` — Task CRUD and transition use cases; implements `TaskExecutionState` port.
- `application/service/TaskAttemptService.kt` — Attempt lifecycle, report serialization; implements `AttemptOperations` port.
- `application/worker/TaskExecutionWorker.kt` — Scheduled task claim, bounded model/tool reasoning loop, report generation.
- `application/worker/TaskExecutionFinalizer.kt` — Terminal attempt and task state updates after execution.

#### `dev.agentplatform.infrastructure` — Outbound adapters (frameworks, I/O)

- `infrastructure/persistence/TaskRepository.kt` — Panache repository for `CodingTask`.
- `infrastructure/persistence/TaskAttemptRepository.kt` — Panache repository for `TaskAttempt`.
- `infrastructure/persistence/TaskStatusTransitionRepository.kt` — Panache repository for ordered status history.
- `infrastructure/ollama/OllamaCodingModelProvider.kt` — Ollama `/api/chat` HTTP adapter; implements `CodingModelProvider`.
- `infrastructure/github/GitHubPullRequestPublisher.kt` — allowlisted branch push and GitHub draft-PR creation; implements `PullRequestPublisher`.
- `infrastructure/workspace/GitRepositoryWorkspaceFactory.kt` — Authorized local/GitHub repository cloning into isolated workspaces; implements `RepositoryWorkspaceFactory`.
- `infrastructure/tool/WorkspaceToolExecutor.kt` — Path-checked, allowlisted tool execution for the model loop.
- `infrastructure/docker/DockerValidationRunner.kt` — Disposable Docker Maven/PostgreSQL validation runtime; implements `ValidationRunner`.
- `infrastructure/process/LocalCommandExecutor.kt` — Argument-vector process runner with bounded output and timeouts; implements `CommandExecutor`.
- `benchmarks/v1/cases.json` and `benchmarks/v1/Invoke-Benchmark.ps1` — Versioned coding benchmark cases and API-driven evaluator.
- `scripts/Apply-TaskPatch.ps1` — Export a completed attempt's patch and optionally apply it to a clean local Git worktree.

#### Resources and build

- `src/main/resources/application.properties` — App name, HTTP port (`8081`), database configuration.
- `src/main/resources/db/migration/` — Flyway-managed PostgreSQL schema migrations (V1 tasks, V2 status history, V3 attempts, V4 one-running-attempt constraint).
- `pom.xml` — Maven build, Kotlin compiler, Quarkus platform and extensions. The Quarkus Maven plugin is configured to disable its default dev-mode debugger; the project has had a conflict on debugger port `5005`.

#### Tests (mirror the main source structure under `src/test/kotlin/dev/agentplatform/`)

- `api/TaskResourceTest.kt` — Quarkus REST and PostgreSQL integration tests.
- `domain/model/TaskLifecycleTest.kt` — State machine transition-policy unit tests.
- `application/service/TaskServiceTest.kt` — Task service integration tests.
- `application/service/TaskAttemptTest.kt` — Attempt lifecycle tests.
- `application/worker/TaskExecutionWorkerTest.kt` — Worker and model/tool loop tests.
- `application/worker/TaskExecutionFinalizerTest.kt` — Finalizer state-update tests.
- `infrastructure/ollama/OllamaCodingModelProviderTest.kt` — Ollama adapter mock-HTTP tests.
- `infrastructure/workspace/GitRepositoryWorkspaceFactoryTest.kt` — Workspace cloning and safety tests.
- `infrastructure/tool/WorkspaceToolExecutorTest.kt` — Path and patch safety tests.
- `infrastructure/docker/DockerValidationRunnerTest.kt` — Docker runner tests.
- `infrastructure/process/LocalCommandExecutorTest.kt` — Process execution tests.
- `infrastructure/process/CommandFixture.kt` — Test helper for command execution.

Task data, status history, attempts, and attempt reports are durable in PostgreSQL. Flyway creates and evolves the schema; Hibernate validates the mapped schema at startup rather than creating tables implicitly. Reports are stored as JSON text and include the generated patch diff, validation details, failure and patch-recovery details, acceptance assessments, and model usage. Estimated costs use optional configured per-million-token rates; they default to zero for local Ollama and are estimates, not provider invoices.

## Open in IntelliJ IDEA

1. Open `C:\\Users\\danie\\IdeaProjects\\coding-agent-platform` with **File → Open**.
2. Use the Maven wrapper in **Settings → Build Tools → Maven** and set the Maven Runner JRE to JDK 21 or newer. The installed IntelliJ JBR is suitable.
3. Reload the Maven project after changing `pom.xml` so IntelliJ picks up the Quarkus plugin configuration.

From PowerShell in the project directory, if `JAVA_HOME` is not already set to a JDK 21+ installation:

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd quarkus:dev
```

The app listens on <http://localhost:8081>. Quarkus Dev UI is at <http://localhost:8081/q/dev/>. Stop dev mode with `Ctrl+C`.

### Reviewing and applying a completed patch

The generated diff is stored in PostgreSQL as `report.patchDiff` on the attempt. Retrieve the latest attempt and display its patch with:

```powershell
$taskId = '00000000-0000-0000-0000-000000000000'
$attempts = Invoke-RestMethod -Uri "http://localhost:8081/api/tasks/$taskId/attempts"
$attempt = $attempts | Sort-Object attemptNumber -Descending | Select-Object -First 1
$attempt.report.patchDiff
```

To export a selected completed attempt to a patch file and check it against a clean target worktree without changing repository files:

```powershell
.\scripts\Apply-TaskPatch.ps1 -TaskId $taskId -AttemptId $attempt.id -RepositoryPath 'C:\path\to\your\repository'
```

Add `-Apply` to apply the patch after the same checks pass. The helper requires a completed task and attempt, all validation checks passed, matching evidence-bearing passing acceptance checks, a clean Git worktree, and a successful `git apply --check`. It exports the patch under the system temporary directory by default. It does not commit, push, create a branch, or open a pull request; inspect and test the applied changes yourself. If the task had no acceptance criteria, there are no criterion checks to verify.

### PostgreSQL persistence

The development database is `coding_agent_platform` on `localhost:5432`; tests use the separate `coding_agent_platform_test` database. Both use the limited `coding_agent_app` role. A local `.env` file (ignored by Git) supplies `DB_PASSWORD`; Quarkus dev mode loads this file automatically. Flyway applies migrations from `src/main/resources/db/migration` at startup, and Hibernate validates the resulting schema.

For a fresh local setup, connect to PostgreSQL as an administrator and create the limited application role and database (replace the sample password):

```sql
CREATE ROLE coding_agent_app LOGIN PASSWORD 'choose-a-local-password';
CREATE DATABASE coding_agent_platform OWNER coding_agent_app;
CREATE DATABASE coding_agent_platform_test OWNER coding_agent_app;
```

Then place the same role password in the ignored `.env` file at the project root:

```properties
DB_PASSWORD=your-local-app-role-password
```

The PostgreSQL role needs access only to these project databases. Do not put administrator credentials in the app configuration. Existing tasks remain available after restarting the application because they are stored in PostgreSQL. If you run the packaged JAR instead of `quarkus:dev`, set `DB_PASSWORD` in that process's environment; Quarkus reads the local `.env` file automatically in dev mode.

Run the automated tests from PowerShell by loading the app password into the environment (Quarkus tests use the configured PostgreSQL database):

```powershell
$env:DB_PASSWORD = (Get-Content .env | ConvertFrom-StringData).DB_PASSWORD
.\mvnw.cmd test
Remove-Item Env:DB_PASSWORD
```

The suite includes transition-policy and REST/database tests, provider protocol tests, worker/report lifecycle tests, path and patch safety tests, repository workspace tests, command execution tests, and Docker-runner tests. `mvnw verify` enforces 100% line and branch coverage for measured application code.

### Repeatable coding benchmark

`benchmarks/v1/cases.json` is a small, versioned benchmark of three deterministic Java/Maven coding tasks against a common fixture. The PowerShell runner creates a Git baseline for the fixture on first use, submits each task through the API, and evaluates task status, Maven validation, reported acceptance checks, expected changed files, expected diff content, elapsed time, token usage, and estimated cost. Each task runs from a fresh workspace copy of that same committed fixture. Results are written to `benchmarks/v1/results-v1.json`.

Before running it:

1. Set `AGENT_ALLOWED_LOCAL_ROOT` to a parent directory containing `benchmarks/v1/fixture`, and set `AGENT_WORKSPACE_ROOT` to a separate workspace directory.
2. Ensure the host Maven cache contains the fixture's Maven/JUnit dependencies. The tests themselves execute offline in Docker.
3. Start the API and Ollama model, then run `.\benchmarks\v1\Invoke-Benchmark.ps1`. For paid model comparisons, set `AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD` to the current provider rates before starting the API.

The first run initializes and commits the benchmark fixture locally; later runs require that fixture to be clean. Estimated token costs are zero by default for Ollama. Set `AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD` to the chosen model's rates when comparing paid models. The benchmark is only a smoke-sized baseline (three cases), not a statistically meaningful model-quality claim; report the sample size and benchmark version with any success rate.

`benchmarks/v2/cases.json` retains those three cases and adds a no-change clarification control. Run it against the shared fixture with:

```powershell
.\benchmarks\v1\Invoke-Benchmark.ps1 `
  -ManifestPath .\benchmarks\v2\cases.json `
  -RepositoryPath .\benchmarks\v1\fixture `
  -OutputPath .\benchmarks\v2\results-v2.json
```

The scorecard uses the **last** validation result, requires exactly one evidence-bearing passing assessment for each supplied criterion, can require an exact changed-file set, and checks that a clarification case produced neither changed files nor a diff. It reports false-success count/rate for expected non-completion cases, per-case patch failures, elapsed time, model usage, and validation outcomes. The diff/file checks are lightweight benchmark oracles, not proof of behavior; acceptance assessments remain model-authored. The prompt version is currently marked `unversioned`, and four cases are still only a smoke suite. Expand the suite with independently tested outcomes and diverse maintenance tasks before making quality claims.

### Try the API

```powershell
$body = @{
  title = 'Add customer search pagination'
  description = 'Add pagination while preserving existing API behavior.'
  acceptanceCriteria = @('Existing callers remain compatible', 'Pagination behavior is covered')
  repositoryUrl = 'https://github.com/example/customer-service'
} | ConvertTo-Json

$task = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/tasks `
  -ContentType 'application/json' -Body $body

Invoke-RestMethod -Uri "http://localhost:8081/api/tasks/$($task.id)"
Invoke-RestMethod -Uri http://localhost:8081/api/tasks

$transition = @{ status = 'RUNNING'; reason = 'Worker claimed the task' } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "http://localhost:8081/api/tasks/$($task.id)/status" `
  -ContentType 'application/json' -Body $transition
Invoke-RestMethod -Uri "http://localhost:8081/api/tasks/$($task.id)/history"
```

Create a task against a repository under the configured allowlisted local root, or against a public GitHub HTTPS URL. For local access, configure `AGENT_ALLOWED_LOCAL_ROOT` to a parent directory containing only repositories you intend the agent to access. Keep `AGENT_WORKSPACE_ROOT` separate. Do not point the agent at directories containing credentials or production data.

## Decisions and boundaries

- **Quarkus, not Spring Boot:** the original project brief recommended Kotlin/Spring Boot, but the user explicitly chose Quarkus. Continue with Kotlin + Quarkus unless the user changes that decision.
- **One application, one worker, one repository first:** keep modules separate in code, but avoid microservices and multi-agent orchestration until a measured need appears.
- **Persist before adding sophisticated orchestration:** task records now live in PostgreSQL. Add attempts, leases, and audit events as the workflow needs them. A database-backed worker with bounded claims is a smaller first reliability step than immediately introducing RabbitMQ or a workflow engine. Revisit a broker when multiple workers or throughput requirements justify it.
- **LLM provider:** the source brief uses Anthropic Claude. Keep the provider behind an interface so model selection and evaluation can change without rewriting the workflow. No provider or API key has been configured.
- **Human approval:** create draft/proposed pull requests only. Do not auto-merge or deploy.
- **Repository content is untrusted:** issue text and files can contain prompt injection or malicious code. Model tool requests are proposals; application policy must authorize and constrain each operation.

## Architecture evolution: synchronous MVP to async workers

The current implementation is a **Quarkus** REST API and one in-process scheduled worker backed by PostgreSQL. The API stores a task as `QUEUED`; the worker claims it, creates an attempt, opens an isolated workspace, runs a bounded model/tool loop, validates changes in Docker, and persists the report and terminal state. This is still an early single-worker slice: there are no leases, heartbeats, retries, crash recovery, cancellation during execution, broker, GitHub write integration, or independent worker process.

When execution becomes asynchronous and needs to survive API restarts or scale independently, evolve toward this flow:

```text
Client → Quarkus API → PostgreSQL (task + attempt)
                           ↓
                    RabbitMQ job message
                           ↓
                    Worker process → model-provider interface → Ollama / controlled tools
                           ↓
                    PostgreSQL (progress, result, errors)
```

The broker carries a job reference (for example, task ID and attempt ID) from the API to a worker. It does **not** communicate with the model; the worker calls the model provider and tools. PostgreSQL remains the source of truth for task state, attempt history, results, and recovery. The model provider stays behind an application interface so changing providers does not change queue responsibilities.

### Broker choice

RabbitMQ is the initial broker to consider when the requirement is durable work distribution to workers: it has queue-oriented delivery, acknowledgements, and retry/dead-letter patterns. Kafka is a strong choice for durable event streams, replay, and multiple independent consumers, but would add design and operational complexity for a first worker queue. Revisit Kafka if replayable event history or high-throughput multi-consumer streaming becomes a real requirement. Neither broker is needed merely to call Claude.

### Database and broker consistency

Do not assume a PostgreSQL transaction can atomically commit a RabbitMQ publish. If the API commits a task but fails to publish its job, work can be stranded; publishing first can create a message for a task whose transaction later rolls back. Once broker-backed dispatch is introduced, use a **transactional outbox**: insert/update the task and insert an outbox event in the same PostgreSQL transaction. A separate publisher sends pending outbox rows to RabbitMQ and marks them published. The publisher can crash after sending but before marking, so duplicate delivery is expected. Workers must be idempotent: use stable task/attempt identifiers and atomically verify/claim eligible work in PostgreSQL before executing it.

For long-running agent work, avoid holding a RabbitMQ delivery unacknowledged for the entire model/tool/test run without checking the consequences of consumer timeouts and redelivery. A practical design is to durably claim the task/attempt, then acknowledge the dispatch message and track execution leases/heartbeats in PostgreSQL. Recovery logic can requeue or retry work whose lease expires. The exact acknowledgement point, lease duration, retry/backoff policy, and dead-letter behavior should be specified and tested when the worker is implemented.

### Incremental path

1. **Current MVP:** Quarkus API, PostgreSQL task persistence, and audited status transitions.
2. **Execution model:** add attempt records and a single database-backed worker; define claims, leases, bounded retries, timeouts, cancellation, and idempotency.
3. **Asynchronous API:** return `202 Accepted` for submitted work and expose task/attempt progress while a worker executes independently.
4. **Broker-backed dispatch:** add RabbitMQ when independent worker processes, scaling, or isolation make it useful. Introduce an outbox with broker publication and idempotent consumption as part of reliable dispatch.
5. **Operational hardening:** add recovery for expired leases, retry/backoff limits, dead-letter handling, concurrency and provider-rate limits, and observability before increasing worker count.

Treat these as architecture guidance, not commitments to add every component now. Prefer the simplest stage that meets the current reliability and scaling needs.

### Execution definition of done

Given an authorized repository and a task with acceptance criteria, the agent should inspect relevant code, identify related tests, make a focused patch in an isolated working copy, add or update tests when needed, run relevant validation, and return a reviewable report. The report explains what changed, why important implementation choices were made, alternatives considered where useful, unresolved questions, changed files, and the actual validation outcomes. If an unresolved question blocks safe progress, the attempt should finish as `NEEDS_INPUT` with the question and its impact; it must not claim completion. A failed or unavailable check must be reported as such, never as a pass.

"Completed" means execution produced a patch and an honest validation report. It does not mean the patch was approved, merged, or deployed. Keep human review as the gate for later pull-request or release actions.

## Recommended roadmap

Do these as small vertical slices. Keep the app runnable after each slice and document the behavior and limits that actually exist.

### 1. Establish a measurable baseline

Run the v2 smoke suite and expand it to 15–30 varied Kotlin/JVM maintenance tasks. Include independently checked bug fixes and regressions, no-op and malformed patches, ambiguous requests, and explicit refusal/clarification controls. Keep evaluator oracles outside the workspace the agent can edit. Version the benchmark, system prompt, tool policy, and model; record final validation, changed files, false-successes, patch retries, human acceptance, cost, and elapsed time. **Exit gate:** reproducible runs and no false successes in the known negative controls. The four-case v2 suite is only a first slice of this phase.

### 2. Harden completion and worker reliability

The worker already requires a non-empty patch and successful supported validation, but acceptance criteria are still model-authored assessments, not independent verification. Preserve that distinction in API/report wording and never treat arbitrary model evidence as machine proof. Add tests for no-op patches, failed final validation, missing/duplicate criterion results, unsupported validation, and malformed patches. Then add leases/heartbeats, recovery of attempts left running after a crash, bounded task retries/backoff, cancellation, and concurrency controls. **Exit gate:** none of those failure cases can reach `COMPLETED`, and recovery cannot execute duplicate work. Keep worker logic separate from REST resources; do not add RabbitMQ until a concrete scaling need justifies it.

### 3. Make execution reviewable and close isolation gaps

The attempt report already includes diffs, validation results, patch failures, model usage, and duration. Add appropriately redacted per-tool audit records and explicit task-level time/cost budgets. Maven tests run in a disposable resource-limited Docker container, but model/tool orchestration runs in the application process; review this boundary before describing the whole agent as sandboxed. Define a controlled dependency-cache/proxy policy before permitting broader build support or network access. **Exit gate:** a reviewer can reconstruct what ran and what changed, and the execution boundary and limitations are documented.

### 4. Add GitHub issue intake and harden PR publishing

Opt-in draft-PR publishing now works for manually submitted tasks against allow-listed public GitHub repositories. Next add issue intake: start with one configured repository and manually supplied issue URL or an allow-listed label, then add signed webhook verification, idempotent delivery, and short-lived GitHub App credentials. Add durable publication state/retry handling for cases where a branch push succeeds but PR creation fails. Include the diff, validation evidence, and limitations in the draft PR. Keep merge approval human-controlled. **Exit gate:** duplicate events cannot create duplicate work/PRs, credentials are scoped and short-lived, partial publication can be recovered safely, and no path auto-merges.

### 5. Demo and polish

Only after the workflow passes its gates, add a small status/diff/results view, deployment, and a demo. Publish measured results with sample sizes and known limitations; avoid claiming superiority to IDE coding assistants. Defer Kubernetes, a vector database, multi-agent orchestration, and broad language support unless evidence shows they solve a real problem.

## Suggested data model (to refine during implementation)

- `Task`: user request, repository identity, status, created/updated timestamps, cancellation request, terminal summary.
- `Attempt`: attempt number, worker/lease identity, start/end times, result, retry reason, resource usage.
- `StateTransition`: prior/new state, actor, timestamp, reason; append-only for auditability.
- `ToolExecution`: tool name, validated arguments or safe summary, outcome, duration, output reference.
- `ValidationResult`: command/check identifier, exit code, bounded output, timing.
- `PullRequest`: provider/repository/branch/PR identity and idempotency key.
- `ModelUsage`: provider/model, token counts, cost estimate, latency, stop reason.

Treat this as a starting point, not a schema to create all at once. Add entities when a real workflow needs them.

## Open decisions for discussion

The source brief gives a strong direction, but leaves choices that affect implementation. Suggested starting defaults are included so work can continue without treating them as settled requirements.

- **Who submits tasks?** Start with the local REST API and one operator. Add authentication/authorization before exposing it beyond localhost or a trusted development network.
- **Which repository is the first target?** Pick a small, disposable Kotlin/JVM sample repo with deterministic tests. Avoid proprietary or production repositories in the first evaluation set.
- **Which model/provider?** The first adapter targets local Ollama by user choice, behind a provider interface. Select the local model after checking available RAM/GPU and test tool-call quality on a small benchmark. Revisit hosted providers only with an explicit usage budget.
- **How should builds access dependencies?** A fully offline sandbox is safer, but many builds need dependency downloads. Decide on a controlled artifact cache/proxy or an explicit network allowlist before enabling execution.
- **How should GitHub authenticate?** The current opt-in publisher uses a fine-grained token restricted to an allow-listed repository. Prefer a GitHub App with installation-scoped permissions and short-lived credentials before offering webhook intake or a hosted service.
- **Which language is in scope?** Start with one Kotlin/JVM repository and its existing build/test commands. Add language support only when the first workflow is measurable.
- **What counts as success?** Define benchmark task selection, acceptance checks, regression checks, human review outcomes, and how cost/latency are calculated before publishing scores.

These choices can be discussed while implementing the worker; they do not need to block the attempt/report foundation.
## Status and handoff for the next coding session

- Ollama is the first model backend. The provider contract and HTTP adapter have mock-HTTP coverage; run the versioned benchmark before making model-quality claims.
- The scheduled single-process worker, fail-closed local repository-root configuration, tool allowlist, temporary repository workspace, Docker Maven/PostgreSQL validator, acceptance checks, structured attempt reports, opt-in allowlisted GitHub draft-PR publishing for public repositories, and benchmark runner are implemented. Benchmark v2 adds a no-change clarification control, stricter scorecard checks, and false-success reporting, but remains a four-case smoke suite. GitHub issue intake, private-repository clone authentication, and durable recovery from partial publication are not implemented. Next priorities are to validate publishing against a dedicated test repository, expand the benchmark, and implement issue intake/recovery.
- Run `mvnw verify` after loading `DB_PASSWORD` from `.env` into the process environment. It runs tests and enforces 100% line and branch coverage for measured application code; the HTML report is generated under `target/site/jacoco`.
- PostgreSQL 18.6 databases `coding_agent_platform` and `coding_agent_platform_test` plus limited login `coding_agent_app` are configured locally. The app role password is in the ignored project `.env`; the administrator password is not used by the app.
- The app was built and manually exercised against PostgreSQL: a task with ordered acceptance criteria survived an application restart. The isolated PostgreSQL test database is migrated through V4 for attempts.
- A prior shell lookup resolved `java` to Oracle's `java8path` shim. The IntelliJ JBR at `C:\\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr` ran Maven as Java 25, compiling for the project's Java 21 target. Configure IntelliJ's Maven Runner JRE explicitly.
- A previous debug-port collision was reported on `5005`. The Maven plugin now has `<debug>false</debug>` configured; reload the Maven project after pulling this change. If JDWP still appears, inspect IntelliJ's run configuration for an explicit `-agentlib:jdwp` VM option.

## Technology

- Kotlin 2.4.10
- Quarkus 3.40.1
- REST with Jackson
- Maven wrapper (Maven 3.9.16)
- Java bytecode target 21
