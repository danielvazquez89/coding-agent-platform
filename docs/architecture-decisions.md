# Architecture decision log

A lightweight, append-only record of the decisions that shape this codebase. Each entry is an
ADR-style note: the forces at play, the choice, and the consequences someone should be aware of
before "improving" it. For product scope and behavior see [README.md](../README.md); for the
agent-facing operating guide see [AGENTS.md](../AGENTS.md).

Adding a decision: append `ADR-NNNN` with **Status / Context / Decision / Consequences**. If a
decision changes, add a new ADR that supersedes the old one rather than rewriting history.

---

## ADR-0001 — Kotlin + Quarkus as the application stack

**Status:** Accepted.

**Context:** The original brief suggested Kotlin/Spring Boot; the project owner chose Quarkus.

**Decision:** Build the service on Kotlin 2.4 + Quarkus 3.40 with REST (Jackson), Panache, Flyway,
and the Quarkus scheduler. Target Java 21 bytecode.

**Consequences:** Fast startup and a small footprint; Quarkus-specific behaviors (config expression
semantics, dev-mode `.env` loading, `@ConfigProperty` defaults) are load-bearing and documented in
`AGENTS.md`. Continue on Quarkus unless the owner revisits the choice.

---

## ADR-0002 — Hexagonal (ports-and-adapters) architecture

**Status:** Accepted.

**Context:** The domain must stay testable and independent of the model provider, database, Docker,
and HTTP frameworks, because those are the parts most likely to change.

**Decision:** Four layers with dependencies pointing inward: `api` (inbound adapters) and
`infrastructure` (outbound adapters) depend on `application` and `domain`; `application` depends on
`domain`; `domain` depends on nothing outward. `domain/port` defines the SPI interfaces that
`infrastructure` implements.

**Consequences:** The model provider, workspace, command executor, validator, and publisher can be
swapped or mocked (`QuarkusMock.installMockForType`) without touching the worker. Reviews must reject
framework imports into `domain`.

---

## ADR-0003 — PostgreSQL as the system of record; scheduled polling worker, no broker yet

**Status:** Accepted (revisit at the broker trigger).

**Context:** The workflow is asynchronous and must survive restarts. A message broker adds
operational cost, and the current scale is a single worker.

**Decision:** Persist tasks, attempts, status history, tool-audit events, and attempt reports in
PostgreSQL. A Quarkus scheduler polls every two seconds and claims one eligible task with a database
row lock (`V4` enforces a single running attempt per task). Compose can run a separate worker
container, but it shares the same database and the same claiming mechanism.

**Consequences:** No broker is needed to run or scale the worker horizontally (row locks prevent
double-claiming). There are still no leases, heartbeats, automatic retries, or cancellation, and a crash
does not resume in-flight work: instead, `TaskExecutionWorker.reapOrphanedAttempts` fails an attempt left
`RUNNING` once it is older than `AGENT_STALE_ATTEMPT_TIMEOUT_SECONDS` (default: twice the maximum task
duration), recording an explicit "outcome unknown" report so a task is never stuck in `RUNNING`. This
keeps state consistent but is deliberately coarse (a slow task in a multi-worker deployment is protected
only by the timeout, not a real lease) and does not restore the lost work — retry means a new task. When
independent scaling or durable dispatch is genuinely needed, introduce a broker behind a **transactional
outbox**, add real leases/heartbeats, and make consumers idempotent (see README "Architecture evolution").
Do not assume a PostgreSQL commit and a broker publish are atomic.

---

## ADR-0004 — Provider-neutral model SPI; Ollama first

**Status:** Accepted.

**Context:** Model choice drives evaluation and cost, but must not reshape the workflow.

**Decision:** Define `domain/port/CodingModelProvider` (messages, tool definitions, tool calls,
turns). Implement Ollama's `/api/chat` in `OllamaCodingModelProvider`, including native `tool_calls`
and a normalization path for models that return a JSON tool request in message content. Configure via
`OLLAMA_BASE_URL` / `OLLAMA_MODEL` / `OLLAMA_TIMEOUT_SECONDS`.

**Consequences:** Swapping or adding a provider does not touch the worker or tools. The adapter does
not download models. Token usage and estimated cost are reported per attempt; with default zero rates
the cost ceiling cannot represent real inference spend.

---

## ADR-0005 — Allow-listed tools; repository content is untrusted

**Status:** Accepted.

**Context:** Task text and repository files can carry prompt injection or hostile code, and the model
proposes actions that could be destructive if taken literally.

**Decision:** The model may only request a tool from a fixed, code-owned allow-list — currently
`list_files`, `search_code`, `find_symbol`, `read_file`, `create_file`, `related_tests`, `apply_patch`,
and `run_validation` (the README holds the authoritative list). The application authorizes every call:
path checks keep reads and writes inside the workspace, patch size is bounded, and there is no shell,
network, credential, or GitHub-write tool. The system prompt states the same policy, but policy is
enforced in code, not by the prompt.

**Consequences:** The agent cannot do anything the application did not explicitly allow. New
capabilities require a new allow-listed tool plus its authorizer and tests — never a generic "run
command" escape hatch.

---

## ADR-0006 — Isolated, offline Docker validation; the orchestrator holds the Docker socket

**Status:** Accepted.

**Context:** Repository code must be built and tested without exposing the host or production
secrets, and without granting orchestration code unnecessary privileges.

**Decision:** Validation runs `mvn -B -o test` in a disposable, resource-limited container on a
private internal network with a temporary PostgreSQL service and a read-only Maven cache. The
dependency-fetch fallback is bounded. In the Compose topology, only the trusted `control-plane`
service mounts the Docker socket; the `worker` calls an authenticated, workspace-scoped control-plane
endpoint (`InternalExecutionResource`) and runs non-root with a read-only root filesystem, dropped
capabilities, and no Docker socket.

**Consequences:** Gradle, other build systems, and network-enabled builds are unsupported. The
workspace is mutated by validation (Maven writes `target/`); those build outputs are deliberately
excluded from `changedFiles()`/`patchDiff()` so they never reach reports or pull requests. The control
plane is Docker-daemon-equivalent and must be treated as host-root.

---

## ADR-0007 — Draft pull requests only; humans gate merges

**Status:** Accepted.

**Context:** The system proposes changes; it cannot be trusted to make final integration decisions.

**Decision:** When explicitly enabled and configured for an allow-listed public repository, the worker
pushes a task branch and opens a **draft** pull request against the default branch. Publication is
idempotent (a retry records the existing PR rather than opening a duplicate). Nothing merges,
deploys, or writes to a protected branch. `COMPLETED` means "patch produced with an honest report",
not "approved".

**Consequences:** Partial failures (branch pushed, PR creation failed) mark the task `FAILED` with
`pullRequestError`; a pushed branch may need manual cleanup. Durable retry/recovery after partial
publication is not implemented.

---

## ADR-0008 — Optional configuration uses a `disabled` sentinel, never an empty value

**Status:** Accepted.

**Context:** Quarkus treats an **empty property-file value** (for example `key=${ENV:}`) as missing and
fails startup, while an empty **environment** variable is treated as unset. Optional features need a
default that means "off" and still lets injection points be non-empty.

**Decision:** Optional settings default to the sentinel `disabled` (case-insensitive) in
`application.properties`, and adapters treat blank or `disabled` as unconfigured via an
`isConfigured(value)` helper. Features that are configured but incomplete fail closed: e.g. a
control-plane URL without a real token throws in the adapter's `init`.

**Consequences:** The app starts with no optional feature configured. Do not "simplify" a sentinel back
to an empty default; it reintroduces a startup failure. This pattern is applied to
`AGENT_ALLOWED_LOCAL_ROOT`, the control-plane URL/token, the Docker workspace/cache volumes, the
GitHub token/repository allow-list, and the stale-attempt timeout (there `disabled` means "derive twice
the maximum task duration", not "feature off").

---

## ADR-0009 — Budgets are checked at operation boundaries, not preemptively

**Status:** Accepted.

**Context:** Hard preemption of an in-flight model call, tool call, or container build is complex and
can corrupt state; the goal is to bound work, not to guarantee an exact ceiling.

**Decision:** Enforce cumulative task time and estimated cost **between** operations and after each
model response. Time and cost enforcement fail closed when prior usage is unavailable. Cost
enforcement requires non-zero input/output rates.

**Consequences:** A single in-progress operation may exceed a limit before the next check stops the
task. With the default zero rates, estimated cost is zero and the cost ceiling cannot represent actual
spend. Treat these as guardrails, not hard caps.

---

## ADR-0010 — Durable per-tool audit written before execution

**Status:** Accepted.

**Context:** If the process stops mid-attempt, a reviewer must still be able to reconstruct what the
agent attempted and with what outcome.

**Decision:** Before an allow-listed tool runs, commit an audit event (`tool_execution_events`) with
the tool name and a redacted argument summary; after it returns, update the same event with outcome,
duration, and a short result summary. Store source contents, full patches, and raw outputs **only** in
the attempt report, never in the audit trail. A `STARTED` event left after a crash means the outcome is
unknown, not that the operation succeeded or failed.

**Consequences:** Events are readable even for unfinished attempts, via a dedicated endpoint. The
audit trail is intentionally lossy (no source/output) and must not be treated as a full transcript.

---

## ADR-0011 — The patch is an artifact with an explicit, server-side export policy

**Status:** Accepted.

**Context:** A task's change has to reach a human somewhere. The workspace is disposable and deleted
when the attempt ends, so the durable artifacts are the attempt report (evidence) and, optionally, a
draft pull request. The raw diff is stored as text in `report.patchDiff` and is capped, because reports
are JSON rows and must stay bounded.

**Decision:** Treat the report as the durable record and the draft pull request as the primary human
handoff; the stored diff is a convenience, not the delivery mechanism. Serve it as an applicable patch
from `GET /api/tasks/{id}/attempts/{attemptId}/patch` (`text/x-patch`) and keep the "safe to export"
rules in the application service, not in clients: task and attempt `COMPLETED`, every validation check
passed, and exactly one matching, in-order, passing, evidenced acceptance check per criterion. When the
real diff exceeds the storage limit the workspace reports truncation, the report records
`patchDiffTruncated`, and the endpoint refuses to serve it (`409`) rather than return text `git apply`
would reject.

**Consequences:** No client (the PowerShell helper, curl, the review page) re-implements the policy, and
a truncated diff can never be mistaken for an appliable one. A change too large to store is only
reproducible through the draft pull request. Automatically committing, pushing, or applying to a user's
working tree is deliberately **not** provided; `COMPLETED` still means "patch produced with an honest
report", not "applied anywhere". The three handoff routes — draft PR (primary), patch export (fallback),
stored report (audit) — plus the execution flow and a verified Route B walkthrough are documented in
[README](../README.md) under "From task to change" and "Handoff".

---

## ADR-0012 — Model provider is runtime-selectable; add an OpenAI-compatible adapter

**Status:** Accepted (extends ADR-0004).

**Context:** ADR-0004 kept model choice behind an SPI but shipped a single local backend. Using a
stronger hosted model, or a local runtime that speaks the OpenAI contract, should not require a
rebuild or a change to the worker.

**Decision:** Keep `CodingModelProvider` provider-neutral and add
`OpenAiCompatibleCodingModelProvider` (`infrastructure/openai`) for any server implementing OpenAI's
`/chat/completions` contract (`messages` / `tools` / `tool_calls`, including the `tool_call_id` that
links a tool result to its call). Select the active adapter at runtime from `agent.model.provider`
(`ollama` or `openai-compatible`) through `SelectingCodingModelProvider`. The concrete adapters carry
CDI qualifiers so the selector is the only bean a caller resolves through the plain interface. Both
adapters share one content-based tool-request extractor. The reported model name comes from the active
provider rather than a separate setting.

**Consequences:** Switching between a local and a hosted model is a configuration change, and only the
selected adapter is exercised. The unused adapter is inert, so an unconfigured endpoint cannot fail a
run that uses the other provider; the selected adapter fails closed with an explicit reason when its
endpoint or model is unset, and an unknown `agent.model.provider` value fails the deployment instead of
silently defaulting. Hosted models add network egress and real spend: leave the adapter unselected for
local-only operation, and set the per-million-token rates when a hosted model is used.

---

## ADR-0013 — Cited tests are verified against machine test results

**Status:** Accepted (tightens the acceptance rule in README and AGENTS).

**Context:** The worker previously treated a cited test method as credible when the method was declared
in a test-like file. That proves a test exists, not that it ran or passed. A model can add a test that
Maven never discovers, claim it covers a criterion, and still have the run end `COMPLETED`; W9 showed a
related failure where a test was claimed but never written. The acceptance decision must prefer
machine evidence over source-code shape.

**Decision:** `ValidationRunner` returns `ValidationCheck.testResults` with
`testResultsAvailable=true` when it could observe test execution. `DockerValidationRunner` reads
Maven Surefire's JUnit XML after validation, in both bind-mounted and named-volume modes, and
`ExecutionControlHttp` round-trips the results from the control plane. `TaskExecutionWorker` requires
a cited method to be reported `PASSED` in the last validation. A missing, skipped, or failed case
downgrades the acceptance check to `UNVERIFIED`. A declaration-only fallback remains for a validator
that cannot expose results, and it can only weaken a claim.

The parser (`SurefireReports`) treats the workspace as untrusted: DTDs and external entities are
disabled, files are filtered by modification time against the validation start, file count/bytes and
result count are bounded, and malformed reports yield no evidence rather than a pass.

**Consequences:** A fabricated or unexecuted test can no longer satisfy completion on the standard
Maven path. A repository that configures Surefire to write reports somewhere other than
`target/surefire-reports`, or that uses another test framework, will not produce evidence and its
cited tests will end `UNVERIFIED`; that is an honest limit, not a reason to relax the check. The
control plane must return the new fields, and an older control plane that omits them causes the worker
to fall back to declaration scanning until both services are upgraded.

---

## ADR-0014 — Layer boundaries are enforced by ArchUnit, with named exceptions

**Status:** Accepted.

**Context:** The repository claimed hexagonal boundaries, but the worker imported the concrete
`WorkspaceToolExecutor`, so the application layer depended on an outbound adapter. Nothing failed when
a new import crossed a layer. The prose rule was also broader than the code: domain entities have
always carried JPA mapping annotations, the provider SPI uses Jackson's `JsonNode`, and application
services use Panache repositories and API DTOs directly. Treating those as invisible exceptions made
the architecture claim hard to trust.

**Decision:** Add the `WorkspaceTools` domain port (`definitions`, `repositoryMap`, `execute` with
JSON-text arguments) and implement it in `WorkspaceToolExecutor`; the worker depends only on the port.
`ArchitectureTest` uses ArchUnit to enforce:

- domain does not depend on `api`, `application`, or `infrastructure`;
- the worker does not depend on `infrastructure`;
- `api` does not depend on `infrastructure`;
- `application` does not depend on `infrastructure` except its Panache repositories;
- `application` uses only the `api.dto` package from the inbound adapter;
- `infrastructure` does not depend on `api` or `application`;
- domain depends only on the JDK/Kotlin, JPA annotations, and Jackson's databind package.

The exceptions are deliberate and documented here. A new dependency outside those packages fails the
test instead of becoming hidden drift.

**Consequences:** Moving a use case or adding an adapter now has a mechanical guard. The remaining
exceptions are debt with a clear removal path: repository ports would remove the Panache dependency,
application-owned commands/responses would remove the DTO dependency, and a JSON value type in the
provider contract would remove Jackson from the domain. Until then, the test keeps the exception list
small enough to review. ArchUnit runs on the compiled production classes and is excluded from the
coverage gate like the rest of the test sources.
