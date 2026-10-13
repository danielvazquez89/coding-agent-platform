# Coding Agent Platform

A Kotlin + Quarkus project for a controlled software engineering agent. The intended product takes a GitHub issue, inspects one repository, proposes a patch, runs validation in isolation, and opens a pull request for a person to review.

The project is at the foundation stage: it accepts task descriptions over REST and persists them to PostgreSQL. It does not yet use an LLM, clone repositories, execute code, or talk to GitHub.

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

Create-task fields are `title`, `description`, `acceptanceCriteria` (optional list), and `repositoryUrl` (optional). A task has a UUID, creation timestamp, and status. Acceptance criteria retain their input order. Transitions are enforced by the service and recorded with sequence number, prior/new status, optional reason, actor, and timestamp. No background worker or retry attempts are implemented yet.

Allowed transitions:

```text
QUEUED → RUNNING → VALIDATING → COMPLETED
   │         │             ├──→ FAILED
   │         └──→ FAILED   └──→ CANCELLED
   └──→ CANCELLED
```

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal. Invalid and repeated transitions are rejected without changing task state or writing history.

### Code map

- `src/main/kotlin/dev/agentplatform/TaskModels.kt` — create-task request and status enum.
- `src/main/kotlin/dev/agentplatform/TaskResource.kt` — REST routes and basic input checks.
- `src/main/kotlin/dev/agentplatform/TaskService.kt` — task use cases and transactional persistence operations.
- `src/main/kotlin/dev/agentplatform/TaskLifecycle.kt` — allowed-transition policy and invalid-transition error.
- `src/main/kotlin/dev/agentplatform/TaskRepository.kt` — PostgreSQL repository backed by Hibernate ORM with Panache.
- `src/main/kotlin/dev/agentplatform/TaskStatusTransitionRepository.kt` — ordered task history persistence.
- `src/main/kotlin/dev/agentplatform/CodingTask.kt` — persisted task entity and ordered acceptance criteria.
- `src/main/kotlin/dev/agentplatform/TaskStatusTransition.kt` — append-only status history entity.
- `src/main/resources/application.properties` — app name and HTTP port (`8081`).
- `src/main/resources/db/migration/` — Flyway-managed PostgreSQL schema migrations.
- `src/test/kotlin/dev/agentplatform/` — lifecycle policy unit tests and API/database integration tests.
- `pom.xml` — Maven build, Kotlin compiler, Quarkus platform and extensions. The Quarkus Maven plugin is configured to disable its default dev-mode debugger; the project has had a conflict on debugger port `5005`.

Task data and status history are durable in PostgreSQL. Flyway creates and evolves the schema; Hibernate validates the mapped schema at startup rather than creating tables implicitly.

## Open in IntelliJ IDEA

1. Open `C:\Users\danie\IdeaProjects\coding-agent-platform` with **File → Open**.
2. Use the Maven wrapper in **Settings → Build Tools → Maven** and set the Maven Runner JRE to JDK 21 or newer. The installed IntelliJ JBR is suitable.
3. Reload the Maven project after changing `pom.xml` so IntelliJ picks up the Quarkus plugin configuration.

From PowerShell in the project directory, if `JAVA_HOME` is not already set to a JDK 21+ installation:

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd quarkus:dev
```

The app listens on <http://localhost:8081>. Quarkus Dev UI is at <http://localhost:8081/q/dev/>. Stop dev mode with `Ctrl+C`.

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

The suite includes exhaustive transition-policy checks plus REST tests for task creation, status changes, invalid transitions, history ordering, and not-found/validation responses.

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

Use a repository URL you control before adding any repository-cloning capability. The current API only stores the URL as text.

## Decisions and boundaries

- **Quarkus, not Spring Boot:** the original project brief recommended Kotlin/Spring Boot, but the user explicitly chose Quarkus. Continue with Kotlin + Quarkus unless the user changes that decision.
- **One application, one worker, one repository first:** keep modules separate in code, but avoid microservices and multi-agent orchestration until a measured need appears.
- **Persist before adding sophisticated orchestration:** task records now live in PostgreSQL. Add attempts, leases, and audit events as the workflow needs them. A database-backed worker with bounded claims is a smaller first reliability step than immediately introducing RabbitMQ or a workflow engine. Revisit a broker when multiple workers or throughput requirements justify it.
- **LLM provider:** the source brief uses Anthropic Claude. Keep the provider behind an interface so model selection and evaluation can change without rewriting the workflow. No provider or API key has been configured.
- **Human approval:** create draft/proposed pull requests only. Do not auto-merge or deploy.
- **Repository content is untrusted:** issue text and files can contain prompt injection or malicious code. Model tool requests are proposals; application policy must authorize and constrain each operation.

## Recommended roadmap

Do these as small vertical slices. Keep the app runnable after each slice and document the behavior and limits that actually exist.

### 1. Model task attempts and recovery

Task transitions are enforced and their history is persisted. Next, add per-attempt records with bounded failure summaries and retry metadata. Keep each attempt distinct from the task's current state. Add leases only when a background worker is introduced.

### 2. Add a single recoverable worker

Claim queued tasks durably; use leases/heartbeats, attempt limits, timeouts, cancellation, and backoff. Decide how a crashed worker's lease expires and how retries avoid duplicate side effects. Start with one local worker. Add a broker only when a concrete scaling or isolation requirement calls for it.

### 3. Add controlled repository tools and a model loop

Start with one model provider and one repository. Define narrowly scoped operations such as `list_files`, `search_code`, `read_file`, `apply_patch`, and `run_validation`. Validate paths, diff size, allowed commands, time, output size, model tokens, and spend in application code. Cap iterations. Preserve tool inputs/outputs and model usage as auditable records with sensitive values redacted.

### 4. Isolate code execution

Run clone/build/test steps in a disposable container per attempt. Use a non-root user, restricted filesystem mounts, CPU/memory/time limits, and no network egress by default. Never mount personal or production credentials. Treat sandbox escape and dependency-download requirements as explicit design decisions, not implicit permissions.

### 5. Integrate GitHub narrowly

Begin with a manually submitted task against an allow-listed repository. Then add GitHub issue webhooks, short-lived narrowly scoped installation credentials, branch creation, and draft pull-request creation. Verify webhook signatures and make repeated delivery idempotent. Include validation summaries and failure limitations in the pull request. Keep merging manual.

### 6. Build evaluation and observability

Create a small, versioned benchmark of well-scoped issues with known expected behavior, then grow it toward the proposed 30–50 tasks. Record model/prompt/tool versions, test outcomes, regressions, human acceptance, cost, and elapsed time. Publish sample sizes and limitations; do not infer improvements from a few hand-picked successes. Add structured logs, traces, and metrics for task/attempt lifecycle, model usage, tool calls, and validation results.

### 7. Polish after the workflow works

Only then add a simple status/log/diff/results view, deployment, and a demo. Keep architecture diagrams, setup steps, security assumptions, example runs, evaluation results, and known limitations current. Avoid Kubernetes, a vector database, multiple coding agents, and broad language support unless evidence shows they solve a real problem.

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
- **Which model/provider?** The brief names Anthropic Claude. Select the first provider and a spend ceiling together; keep the code behind a provider interface for later comparisons.
- **How should builds access dependencies?** A fully offline sandbox is safer, but many builds need dependency downloads. Decide on a controlled artifact cache/proxy or an explicit network allowlist before enabling execution.
- **How should GitHub authenticate?** Prefer a GitHub App with installation-scoped permissions and short-lived credentials. Keep repository allowlisting and draft PRs as defaults.
- **Which language is in scope?** Start with one Kotlin/JVM repository and its existing build/test commands. Add language support only when the first workflow is measurable.
- **What counts as success?** Define benchmark task selection, acceptance checks, regression checks, human review outcomes, and how cost/latency are calculated before publishing scores.

These choices can be discussed while modeling attempts and recovery; they do not need to block that next slice.
## Status and handoff for the next coding session

- The next useful implementation slice is **per-attempt records and retry metadata**, then a recoverable worker. Status transitions and history are implemented.
- Automated tests cover the complete transition matrix and HTTP/database behavior. Run `mvnw test` after loading `DB_PASSWORD` from `.env` into the process environment.
- PostgreSQL 18.6 databases `coding_agent_platform` and `coding_agent_platform_test` plus limited login `coding_agent_app` are configured locally. The app role password is in the ignored project `.env`; the administrator password is not used by the app.
- The app was built and manually exercised against PostgreSQL: a task with ordered acceptance criteria survived an application restart. The lifecycle suite passed against the isolated PostgreSQL test database (8 tests, 0 failures).
- A prior shell lookup resolved `java` to Oracle's `java8path` shim. The IntelliJ JBR at `C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr` ran Maven as Java 25, compiling for the project's Java 21 target. Configure IntelliJ's Maven Runner JRE explicitly.
- A previous debug-port collision was reported on `5005`. The Maven plugin now has `<debug>false</debug>` configured; reload the Maven project after pulling this change. If JDWP still appears, inspect IntelliJ's run configuration for an explicit `-agentlib:jdwp` VM option.

## Technology

- Kotlin 2.4.10
- Quarkus 3.40.1
- REST with Jackson
- Maven wrapper (Maven 3.9.16)
- Java bytecode target 21


