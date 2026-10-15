# Coding Agent Platform

A Kotlin + Quarkus project for a controlled software engineering agent. The intended product takes a GitHub issue, inspects one repository, proposes a patch, runs validation in isolation, and opens a pull request for a person to review.

The project runs a bounded, asynchronous coding-agent workflow: it accepts tasks over REST, persists task and attempt state to PostgreSQL, inspects an authorized repository, asks Ollama for changes through an allow-listed tool loop, applies a patch in a disposable workspace, and validates Maven projects in a separate Docker container. The included Compose deployment separates the API/control plane from a resource-limited worker container; the worker has no Docker socket, and validation requests go through an authenticated, workspace-scoped control-plane endpoint. A local review page shows reports, diffs, validation, tool-audit events, and draft-PR links; users can answer a `NEEDS_INPUT` question and queue a new attempt. Per-task time and estimated-cost limits are configurable. When explicitly enabled and configured for an allow-listed public GitHub repository, the worker can push a task branch and open a draft pull request after validation. GitHub issue ingestion and private-repository clone authentication are not implemented.

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
| `POST` | `/api/tasks/{id}/answer` | Add a clarification to a `NEEDS_INPUT` task and queue another attempt; return `409` unless the task is awaiting input. |
| `GET` | `/api/tasks/{id}/history` | Return ordered, append-only status history, including the initial `QUEUED` entry. |
| `GET` | `/api/tasks/{id}/attempts` | Return attempts in attempt-number order, with status, report, and failure summary. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}` | Return one attempt and its structured execution report. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}/tool-executions` | Return redacted, ordered tool audit events, including events recorded before an attempt finishes. |

Create-task fields are `title`, `description`, `acceptanceCriteria` (optional list of up to 30 items, each at most 1000 characters), and `repositoryUrl` (required for execution). A task has a UUID, creation timestamp, and status. Acceptance criteria retain their input order. Transitions are enforced by the service and recorded with sequence number, prior/new status, optional reason, actor, and timestamp. The Quarkus scheduler polls every two seconds. In local development, the API process also runs the worker. In Compose, `control-plane` sets `AGENT_EXECUTION_WORKER_ENABLED=false` and the separate `worker` service enables it; database row locks prevent both processes from claiming the same task. Task creation returns `201` with `QUEUED`; the worker executes asynchronously after persistence. `AGENT_EXECUTION_ENABLED=false` disables execution, and Quarkus scheduler configuration can independently disable scheduled jobs.

`CodingModelProvider` defines a provider-independent chat/tool-call contract. `OllamaCodingModelProvider` implements Ollama's `/api/chat` protocol, including structured assistant tool-call history and named tool-result messages, tool schemas, usage counts, timeouts, and bounded error messages. For models that return a JSON tool request in message content instead of Ollama's native `tool_calls`, it normalizes that response only when the requested tool was offered to the model. The adapter is configured with `OLLAMA_BASE_URL` (default `http://localhost:11434`), `OLLAMA_MODEL` (default `qwen3:8b`), and `OLLAMA_TIMEOUT_SECONDS` (default `1200`). It does not download models.

The current local default is `qwen3:8b`. If needed, install it with `ollama pull qwen3:8b`, then check it appears in `ollama list`. Ollama must be running at the configured base URL.

The worker's tool allowlist is `list_files`, `search_code`, `read_file`, `apply_patch`, and `run_validation`; it offers no shell, arbitrary command, network, credential, or GitHub-write tool. Paths and patch size are checked by the application. Local repository access is disabled unless `AGENT_ALLOWED_LOCAL_ROOT` is configured; public GitHub HTTPS URLs are also accepted. A task report includes the observed outcome, model-provided narrative (explicitly marked as unverified), changed files, diff, validation outcomes, patch failures/recovery, acceptance checks, model token usage, estimated model cost, elapsed time, configured budgets, and per-tool audit summaries. Before an allowed tool runs, its invocation is committed to a separate audit-event table; after it returns, the event is updated with its outcome and duration. Events remain readable if the process stops before finalizing the attempt report. A `STARTED` event left after a crash means the operation's final outcome is unknown, not proof that it did or did not take effect. Audit records retain tool name, a redacted argument summary, outcome, duration, and a short result summary; they deliberately omit source contents, full patches, and tool outputs.

`AGENT_MAX_TASK_DURATION_SECONDS` defaults to `1800`; `AGENT_MAX_TASK_COST_USD` defaults to `1.0`. Budgets are cumulative across completed attempts for one task, including clarification/resume attempts; queue waiting time is excluded. Time is checked between model/tool operations and after each model response; cost is checked against cumulative reported token usage after each response. These are operation-boundary guardrails, not hard preemptive ceilings: an in-progress operation is not forcibly interrupted when a limit is reached. Cost enforcement requires non-zero input/output rates (`AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD`) and reported token counts; if rates are set but current or prior token usage is unavailable, the attempt fails closed. Time enforcement also fails closed when prior attempt duration is unavailable. With the default zero rates, estimated cost is zero and the cost ceiling cannot represent actual inference costs.

`NEEDS_INPUT` tasks can be answered through the review page or API. The answer is prepended to the task prompt as a labeled clarification so it remains in the prompt when the original description is long; a status-history entry records the requeue, and a fresh attempt runs against a new workspace. The previous report remains available in attempt history.

When acceptance criteria are supplied, the agent must return one evidence-bearing assessment per criterion. Missing or evidence-free checks prevent completion; failed criteria fail the task, and unverified criteria require input. This is a model assessment, not independent proof: Maven tests and benchmark-specific expected-diff checks provide separate machine-verifiable evidence. A task is completed only when the workspace changed, all supplied criteria are reported as passed, and supported validation passed.

#### Draft pull-request publishing

Pull-request publishing is disabled by default. Set `AGENT_GITHUB_PULL_REQUESTS_ENABLED=true`, `AGENT_GITHUB_TOKEN` to a fine-grained token with **Contents: Read and write** and **Pull requests: Read and write**, and `AGENT_GITHUB_ALLOWED_REPOSITORIES=owner/repository` before starting the app. Keep the token outside source control. The task must use the matching HTTPS GitHub repository URL, and the repository must be publicly cloneable. Publishing commits only the changed paths, pushes a task-specific branch, and creates a draft PR against the repository default branch. If publication is retried after a PR was created, it records the existing PR rather than opening a duplicate; the report includes whether that PR is still open and in draft. The PR contains task context and reported checks; acceptance checks are explicitly labeled as model-assessed. The token is not sent to the model or test container. Pull requests are never merged automatically.

If branch push or PR creation fails, the task is marked `FAILED` with `pullRequestError` in its persisted attempt report; a successfully pushed branch may remain and require manual cleanup. The local review page at `/` displays reports and draft-PR links but does not embed GitHub's review interface, approve changes, or merge. The UI and API are unauthenticated and must remain on localhost or a trusted network. This first slice does not yet provide GitHub issue webhooks, GitHub App installation tokens, or durable retry/recovery after partial publication.

Validation currently supports Maven projects only and runs `mvn -B -o test` in a disposable Linux Docker container with resource limits, a private internal network, a temporary PostgreSQL 18 service, and a read-only Maven cache volume. In Compose, the trusted control-plane container alone receives the Docker socket. The worker calls its authenticated validation endpoint; the control plane rejects workspace paths outside the shared workspace root, and the validator mounts only that attempt's named-volume subdirectory. Test and bounded dependency-fetch commands execute without external network access. Gradle, other build systems, and network-enabled builds are not supported.

The worker container runs non-root with a read-only root filesystem, dropped Linux capabilities, no-new-privileges, a process limit, and CPU/memory limits. It has no Docker socket. The shared workspace and database are reachable by both services; the worker still has the application's database credentials, GitHub credentials when publishing is enabled, and outbound network access needed for GitHub/Ollama. Compose networking does not enforce a strict egress allowlist, and this deployment is not a hostile multi-tenant boundary. Keep it on a trusted host/network, use a narrowly scoped GitHub token, and do not grant production database access. The control-plane container runs as root to access the Docker socket, has Docker-daemon-equivalent privileges, and is bound to localhost by default; treat it as host-root.

### Containerized worker deployment

The local `quarkus:dev` flow remains available. To run the separated worker/control-plane topology, use Docker Compose with a Linux Docker Engine (API 1.45+ for per-attempt volume subdirectory mounts) and an Ollama endpoint reachable from the worker:

```powershell
Copy-Item .env.example .env
# Edit .env and replace both placeholder secrets with long random values.
.\mvnw.cmd package -DskipTests
docker compose up --build
```

The Compose file creates PostgreSQL, the control plane, and a separate worker. The control plane is available at <http://localhost:8081>; only it mounts `/var/run/docker.sock`. Both services share named workspace and Maven-cache volumes. `AGENT_CONTROL_PLANE_TOKEN` authenticates worker-to-control-plane validation requests; `AGENT_GITHUB_TOKEN` is configured only in the worker environment (never the API or validation containers) and is used only when publishing is enabled. The worker uses `OLLAMA_BASE_URL` (default `http://host.docker.internal:11434`). If Ollama is on the host, configure it to listen on an address reachable from Docker and use host firewall rules to limit access. Set `AGENT_GITHUB_PULL_REQUESTS_ENABLED=true` and configure an allowlisted repository/token in `.env` only when draft-PR publishing is intended. Do not commit `.env`.

For this deployment, use public GitHub HTTPS repository URLs; local host paths are intentionally unavailable because no host source tree is mounted into the worker. `docker compose down` stops services but retains database and workspace volumes; `docker compose down -v` deletes that persisted data.

### Local review page

Open <http://localhost:8081/> to list tasks and inspect the latest attempt, report, diff, validation checks, persisted tool-audit events, open questions, and draft-PR link. Tool events are loaded from their own endpoint rather than only from the final report, so events from an unfinished attempt remain visible. For a `NEEDS_INPUT` task, submit a clarification there; the task is requeued and the worker starts another attempt. This is a local operator view, not an embedded GitHub review client. Protect the app from untrusted network access because the API and page are unauthenticated.

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

NEEDS_INPUT ──(answer)──→ QUEUED
```

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal. `NEEDS_INPUT` can transition to `QUEUED` only through the answer endpoint, which prepends the clarification to the task prompt and starts another attempt. Other invalid and repeated transitions are rejected without changing task state or writing history.

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
- `api/InternalExecutionResource.kt` — bearer-authenticated validation gateway restricted to the shared workspace root.
- `api/dto/TaskDtos.kt` — API data transfer objects: `CreateTaskRequest`, `TransitionTaskRequest`, `TaskStatusTransitionResponse`, `TaskAttemptResponse`, `ApiError`.
- `src/main/resources/META-INF/resources/index.html` — local task/PR review page and `NEEDS_INPUT` answer form.

#### `dev.agentplatform.domain` — Core business logic (no framework dependencies)

- `domain/model/CodingTask.kt` — Task entity with UUID, timestamps, and ordered acceptance criteria.
- `domain/model/TaskStatus.kt` — `TaskStatus` enum (`QUEUED`, `RUNNING`, `VALIDATING`, `COMPLETED`, `FAILED`, `NEEDS_INPUT`, `CANCELLED`).
- `domain/model/TaskStatusTransition.kt` — Append-only status history entity.
- `domain/model/TaskLifecycle.kt` — State machine policy: allowed transitions and `InvalidTaskTransitionException`.
- `domain/model/TaskAttempt.kt` — Attempt entity and `AttemptStatus` enum.
- `domain/model/TaskExecutionReport.kt` — Value objects: `TaskExecutionReport`, `ToolExecutionAudit`, `ToolExecutionOutcome`, `AgentDecision`, `AgentQuestion`, `AcceptanceCheck`, `ModelUsage`, `PullRequestReference`, `ValidationCheck`, `CheckOutcome`.
- `domain/model/ToolExecutionEvent.kt` — Durable per-call audit entity linked to its task and attempt.
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
- `infrastructure/persistence/ToolExecutionEventRepository.kt` — Ordered durable tool-event queries.
- `infrastructure/persistence/TaskStatusTransitionRepository.kt` — Panache repository for ordered status history.
- `infrastructure/ollama/OllamaCodingModelProvider.kt` — Ollama `/api/chat` HTTP adapter; implements `CodingModelProvider`.
- `infrastructure/github/GitHubPullRequestPublisher.kt` — allowlisted branch push and GitHub draft-PR creation; implements `PullRequestPublisher`.
- `infrastructure/workspace/GitRepositoryWorkspaceFactory.kt` — Authorized local/GitHub repository cloning into isolated workspaces; implements `RepositoryWorkspaceFactory`.
- `infrastructure/tool/WorkspaceToolExecutor.kt` — Path-checked, allowlisted tool execution for the model loop.
- `infrastructure/docker/DockerValidationRunner.kt` — Disposable Docker Maven/PostgreSQL validation runtime; implements `ValidationRunner`.
- `infrastructure/ExecutionControlHttp.kt` — Worker HTTP client for the authenticated validation gateway.
- `infrastructure/process/LocalCommandExecutor.kt` — Argument-vector process runner with bounded output and timeouts; implements `CommandExecutor`.
- `benchmarks/v1/cases.json` and `benchmarks/v1/Invoke-Benchmark.ps1` — Versioned coding benchmark cases and API-driven evaluator.
- `scripts/Apply-TaskPatch.ps1` — Export a completed attempt's patch and optionally apply it to a clean local Git worktree.

#### Resources and build

- `src/main/resources/application.properties` — App name, HTTP port (`8081`), database configuration.
- `compose.yaml` and `.env.example` — Separate worker/control-plane deployment with named workspace/cache volumes and no Docker socket in the worker.
- `src/main/resources/db/migration/` — Flyway-managed PostgreSQL schema migrations (V1 tasks, V2 status history, V3 attempts, V4 one-running-attempt constraint, V5 tool execution events).
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

The suite includes transition-policy and REST/database tests, provider protocol tests, worker/report lifecycle tests, path and patch safety tests, repository workspace tests, command execution tests, and Docker-runner tests. `application/AgentWorkflowIntegrationTest.kt` additionally exercises REST submission through real workspace creation, scripted Ollama tool calls, durable audit retrieval, validation, draft-PR reporting, `NEEDS_INPUT` answer/resume, failed validation, unapproved-tool rejection, and the authenticated control-plane path restriction. It uses a local Git fixture and a scripted model server; PostgreSQL remains the test database. Run just this workflow suite with `.\mvnw.cmd "-Dtest=AgentWorkflowIntegrationTest" test`. `mvnw verify` enforces 100% line and branch coverage for measured application code.

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

To answer an attempt that finished as `NEEDS_INPUT`, send a clarification. It is prepended to the task prompt, which returns to `QUEUED`; the worker creates a fresh attempt while preserving earlier reports:

```powershell
$answer = @{ answer = 'Use cursor pagination and preserve the existing default page size.' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/tasks/$($task.id)/answer" `
  -ContentType 'application/json' -Body $answer
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

The current implementation is a **Quarkus** REST API with an in-process worker for local development and a container-separated worker option backed by PostgreSQL. The API stores a task as `QUEUED`; the worker claims it, creates an attempt, opens a workspace, runs a bounded model/tool loop, requests isolated validation from the control plane, and persists the report and terminal state. This remains an early single-worker slice: there are no leases, heartbeats, automatic retries, execution cancellation, or durable recovery of in-flight work after a crash.

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

1. **Initial MVP (implemented):** Quarkus API, PostgreSQL task persistence, and audited status transitions.
2. **Execution model (implemented first slice):** attempt records, database-backed claims, and local or container-separated worker. Next add leases, bounded retries, crash recovery, cancellation, and stronger idempotency.
3. **Asynchronous API (implemented first slice):** task creation persists and returns `201 Created` with `QUEUED`; expose task/attempt progress while a worker executes asynchronously. Consider `202 Accepted` if the API contract is revised.
4. **Broker-backed dispatch:** add RabbitMQ only when independent worker scaling or durable dispatch requirements justify it. Introduce an outbox with broker publication and idempotent consumption as part of reliable dispatch.
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

Tool calls are durably audited before execution and finalized afterward; task time/cost guardrails are checked at operation boundaries and do not preemptively cancel an in-flight operation. The Compose worker/control-plane split removes Docker-daemon access from orchestration, while Maven tests run in a separate resource-limited container. It is a process/container isolation layer, not a complete hostile-code security boundary: the worker has database credentials and outbound network access, and the control plane retains Docker-daemon privileges. A stricter deployment still needs least-privilege database roles, network egress controls, and operational tests on the target Docker host. **Exit gate:** a reviewer can reconstruct attempts, budget behavior is explicit, and the execution boundary and limitations are documented.

### 4. Add GitHub issue intake and harden PR publishing

Opt-in draft-PR publishing now works for manually submitted tasks against allow-listed public GitHub repositories. Next add issue intake: start with one configured repository and manually supplied issue URL or an allow-listed label, then add signed webhook verification, idempotent delivery, and short-lived GitHub App credentials. Add durable publication state/retry handling for cases where a branch push succeeds but PR creation fails. Include the diff, validation evidence, and limitations in the draft PR. Keep merge approval human-controlled. **Exit gate:** duplicate events cannot create duplicate work/PRs, credentials are scoped and short-lived, partial publication can be recovered safely, and no path auto-merges.

### 5. Demo and polish

Only after the workflow passes its gates, polish the local review page, then consider deployment and a demo. Add authentication before any non-local deployment. Publish measured results with sample sizes and known limitations; avoid claiming superiority to IDE coding assistants. Defer Kubernetes, a vector database, multi-agent orchestration, and broad language support unless evidence shows they solve a real problem.

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
- The scheduled worker, fail-closed local repository-root configuration, tool allowlist, disposable repository workspace, Docker Maven/PostgreSQL validator, acceptance checks, structured attempt reports, answer-and-requeue flow, local review page, durable per-tool audit events, cumulative task time/cost guardrails, container-separated worker/control-plane deployment, opt-in allowlisted GitHub draft-PR publishing for public repositories, and benchmark runner are implemented. Benchmark v2 adds a no-change clarification control, stricter scorecard checks, and false-success reporting, but remains a four-case smoke suite. GitHub issue intake, private-repository clone authentication, strict egress controls, least-privilege worker database roles, and durable recovery from partial publication are not implemented. The Compose topology still needs execution and containment testing on the deployment host.
- Run `mvnw verify` after loading `DB_PASSWORD` from `.env` into the process environment. It runs tests and enforces 100% line and branch coverage for measured application code; the HTML report is generated under `target/site/jacoco`.
- PostgreSQL 18.6 databases `coding_agent_platform` and `coding_agent_platform_test` plus limited login `coding_agent_app` are configured locally. The app role password is in the ignored project `.env`; the administrator password is not used by the app.
- The app was built and manually exercised against PostgreSQL: a task with ordered acceptance criteria survived an application restart. Flyway migrations through V5 add durable tool-execution events.
- A prior shell lookup resolved `java` to Oracle's `java8path` shim. The IntelliJ JBR at `C:\\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr` ran Maven as Java 25, compiling for the project's Java 21 target. Configure IntelliJ's Maven Runner JRE explicitly.
- A previous debug-port collision was reported on `5005`. The Maven plugin now has `<debug>false</debug>` configured; reload the Maven project after pulling this change. If JDWP still appears, inspect IntelliJ's run configuration for an explicit `-agentlib:jdwp` VM option.

## Technology

- Kotlin 2.4.10
- Quarkus 3.40.1
- REST with Jackson
- Maven wrapper (Maven 3.9.16)
- Java bytecode target 21
