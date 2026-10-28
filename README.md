# Coding Agent Platform

[![CI](https://github.com/danielvazquez89/coding-agent-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/danielvazquez89/coding-agent-platform/actions/workflows/ci.yml)

A Kotlin + Quarkus project for a controlled software engineering agent. The intended product takes a GitHub issue, inspects one repository, proposes a patch, runs validation in isolation, and opens a pull request for a person to review.

The project runs a bounded, asynchronous coding-agent workflow: it accepts tasks over REST, persists task and attempt state to PostgreSQL, inspects an authorized repository, asks a configured model provider (local Ollama or any OpenAI-compatible endpoint) for changes through an allow-listed tool loop, applies a patch in a disposable workspace, and validates Maven projects in a separate Docker container. The included Compose deployment separates the API/control plane from a resource-limited worker container; the worker has no Docker socket, and validation requests go through an authenticated, workspace-scoped control-plane endpoint. A local review page shows reports, diffs, validation, tool-audit events, and draft-PR links; a completed attempt's diff can also be exported as an applicable patch, users can answer a `NEEDS_INPUT` question and queue a new attempt, a reviewer can confirm a validated attempt whose model report left criteria unverified, and a failed or cancelled task can be retried as a linked new task. Per-task time and estimated-cost limits are configurable. When explicitly enabled and configured for an allow-listed public GitHub repository, the worker can push a task branch and open a draft pull request after validation. GitHub issue ingestion and private-repository clone authentication are not implemented.

> **Repository guides.** For an agent-oriented operating guide — build/test commands, the layer and
> port/adapter map, conventions, invariants, and known pitfalls — see [`AGENTS.md`](AGENTS.md). The
> architectural decision log is [`docs/architecture-decisions.md`](docs/architecture-decisions.md).
> Live evidence from real models and repositories is recorded per finding in
> [`docs/live-testing-findings.md`](docs/live-testing-findings.md), and evaluated against the
> specifications for a purely local deployment in [`docs/local-deployment.md`](docs/local-deployment.md).

## Product goals

The engineering challenge is making the workflow dependable and safe, not adding a chat window around a coding model. The eventual system should:

- keep durable task, attempt, state-transition, validation, and audit records;
- use a small allow-listed set of tools whose inputs and effects are checked by the application;
- run repository code in a disposable, resource-limited environment with no production secrets;
- bound model calls, tool calls, elapsed time, and spend;
- recover from duplicate work, worker crashes, and restarts without opening duplicate pull requests;
- report actual validation results and limitations, then leave merge approval to a human;
- measure success and failure against a repeatable benchmark instead of claiming unmeasured improvements.

## How this compares to other coding agents

For raw capability, mature tools win, and this project does not try to compete there. Aider, OpenHands,
and SWE-agent run stronger models, expose richer tool sets (shell, semantic search, LSP, subagents), and
are backed by large evaluation suites; the commercial CLI agents (Claude Code, Codex, Gemini CLI) are
more polished still. If the goal is the highest chance that an arbitrary repository change lands, use one
of those.

This project optimizes a different axis: **running an agent under policy you control and being able to
prove what it did.** It targets self-hosted, model-agnostic execution where the requirement is a
trustworthy, auditable outcome rather than the best possible patch.

| | Aider / OpenHands / SWE-agent | Commercial CLI agents | This project |
| --- | --- | --- | --- |
| Model | many, hosted or local | vendor models | any Ollama model or OpenAI-compatible endpoint behind one SPI |
| Tools | shell, search, editors, plugins | shell, search, MCP, subagents | nine allow-listed, path-checked tools, no shell |
| Isolation | varies; some sandboxing | vendor-managed | disposable workspace plus an offline, capability-dropped validation container |
| Audit trail | application logs | vendor logs | durable per-tool audit written **before** execution, plus task/attempt/state history |
| Failure honesty | reports what happened | reports what happened | will not mark a task complete without a workspace change, evidenced criteria, and passing validation |
| Network / data | often calls hosted APIs | hosted | no external egress required; local model by default |
| Deploy | local or self-hosted | SaaS | local or Compose; no hosted API keys |

The conservatism is deliberate:

- **No shell and no network tool.** Every action is one of `list_files`, `search_code`, `find_symbol`,
  `read_file`, `create_file`, `replace_text`, `related_tests`, `apply_patch`, or `run_validation`; the
  application validates paths, size, and changed-file count.
- **Honest completion.** A task reaches `COMPLETED` only when the workspace changed, every supplied
  acceptance criterion has an evidence-bearing passing assessment, and supported validation passed.
  Unknown outcomes stay `NEEDS_INPUT` or `FAILED`, never a pass. A final report that cannot be parsed as
  JSON is re-requested at most twice, because a formatting slip should not hide an otherwise validated
  change; if it still cannot be parsed, every criterion stays unverified. An earlier turn's report is
  reused only while no tool has run since it was produced: a later write or validation can change the
  facts it describes, so an empty turn after either is a missing report, not a reason to accept a stale
  `FAILED` report (W12). A report is also not accepted
  while the patched tree fails validation: the worker validates before evaluating the final answer and
  tells the model to fix the failing test and rerun validation, at most twice. The report stores the
  final run's check, while each earlier run remains in the persisted tool audit. A local run applied the
  correct enum change, saw `EnumTest` fail against the old expectation, and then reported that the tests
  were not updated; that is the case this nudge addresses. An unresolved patch failure also blocks
  automatic completion: if an `apply_patch` call was rejected and no later successful write touches
  every file it named, the task ends `NEEDS_INPUT` even when the final report claims every criterion
  passed and validation passed, because the unapplied change may be exactly the evidence the report
  asserts. The failure is recorded in the report. A rejected patch followed by a successful patch or
  exact text replacement that touches the files it named does not block completion. An unresolved
  `create_file` or `replace_text` failure is also a known negative: if the requested file was not
  created or the edit was rejected and no later write to that path succeeds, the task ends
  `NEEDS_INPUT` even when the report claims it exists. A later successful `create_file` or
  `replace_text` for that path, or an `apply_patch` that reports it, clears it. When the patch applied and
  validation passed but the report leaves a supplied criterion without an evidence-bearing assessment,
  the worker asks for
  the missing assessments at most twice, citing the change and the passing run already in the
  conversation; a changed test that calls the changed code and asserts the required result is valid
  evidence when that test ran in the passing validation, and the model must cite the test method and
  assertion. A test name alone proves only that the test exists, and Maven alone does not prove the
  behavior. A cited test method is checked against the machine test results of the last validation:
  the runner reads Maven Surefire's JUnit XML from the workspace and requires the named method to be
  reported `PASSED`. A method that is declared in a test-like file but did not run, was skipped, failed,
  or appeared only in build output is downgraded to `UNVERIFIED` with the rejection recorded, because a
  narrative claim is not evidence of a test. The report parser disables DTDs and external entities,
  ignores stale, oversized, and malformed files, and bounds the reports it reads; the workspace is
  untrusted repository content. Both bind-mounted and named-volume validation expose the report
  directory to the process that requested validation, so the check applies in the Compose topology as
  well as local runs. A declaration-only fallback remains for a validator that cannot expose machine
  results, but it can only weaken a claim. When a report marks a test-requiring
  criterion `PASSED` but no test file changed, the worker asks once for the missing edit before
  accepting the report; a cited method that already exists is left alone, and build output is not
  evidence. Criteria are matched after normalizing whitespace and quote style, so a report that copies
  a criterion with single quotes instead of the task's double quotes still counts. A blocking question
  from the model is accepted as a legitimate `NEEDS_INPUT` stop. A
  small-fixture run applied the correct null check and test and passed Docker validation, yet left one
  criterion `UNVERIFIED` and ended `NEEDS_INPUT`; that is the case this second nudge addresses. A failed
  supported validation is a known negative result, not an open question: unless the model asked a
  blocking question or validation could not run, an attempt that failed validation or produced no
  change ends `FAILED` even when the report left criteria `UNVERIFIED`, and the attempt summary names
  the failing test lines rather than Maven's banner.
- **Draft pull requests only.** Nothing merges, deploys, or writes to a default branch.

The honest trade-off: this conservatism caps capability. A bounded nine-tool loop driven by a small local
model will fail on tasks a shell-enabled agent with a frontier model completes. The value here is the
policy and verification substrate around the model. What has and has not been measured is in
[Repeatable coding benchmark](#repeatable-coding-benchmark).

## Current implementation

### API

| Method | Endpoint | Behavior |
| --- | --- | --- |
| `POST` | `/api/tasks` | Validate required title and description; create a task with `QUEUED` status; return `201` and a `Location` header. |
| `GET` | `/api/tasks` | List persisted tasks, newest first. |
| `GET` | `/api/tasks/{id}` | Return a task or `404`. |
| `PATCH` | `/api/tasks/{id}/status` | Apply a permitted status transition; return `409` for an invalid transition and `404` for an unknown task. |
| `POST` | `/api/tasks/{id}/answer` | Add a clarification to a `NEEDS_INPUT` task and queue another attempt; return `409` unless the task is awaiting input. |
| `POST` | `/api/tasks/{id}/retry` | Clone a `FAILED` or `CANCELLED` task into a new `QUEUED` task linked by `retryOf`; return `201` with a `Location` header, `409` for any other status, and `404` for an unknown task. |
| `GET` | `/api/tasks/{id}/history` | Return ordered, append-only status history, including the initial `QUEUED` entry. |
| `GET` | `/api/tasks/{id}/attempts` | Return attempts in attempt-number order, with status, report, and failure summary. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}` | Return one attempt and its structured execution report. |
| `POST` | `/api/tasks/{id}/attempts/{attemptId}/confirm` | Confirm a validated `NEEDS_INPUT` attempt as a human reviewer: record the reviewer and evidence, replace the acceptance checks with human evidence (preserving the model's original checks), complete the task and attempt, and enable patch export; return `400` for invalid input, `404` for an unknown task/attempt, and `409` when the machine checks do not permit confirmation. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}/patch` | Return the attempt's patch as `text/x-patch` when it is safe to apply locally; return `409` with the reason when it is not, and `404` for an unknown task or attempt. |
| `GET` | `/api/tasks/{id}/attempts/{attemptId}/tool-executions` | Return redacted, ordered tool audit events, including events recorded before an attempt finishes. |

Create-task fields are `title`, `description`, `acceptanceCriteria` (optional list of up to 30 items, each at most 1000 characters), and `repositoryUrl` (required for execution). A task has a UUID, creation timestamp, and status. Acceptance criteria retain their input order. A retry copies the source's title, description, criteria and repository URL into a new task; `retryOf` is assigned by the server and is null for the original. Transitions are enforced by the service and recorded with sequence number, prior/new status, optional reason, actor, and timestamp. The Quarkus scheduler polls every two seconds; each tick reconciles attempts orphaned by a stopped worker and then claims one queued task. In local development, the API process also runs the worker. In Compose, `control-plane` sets `AGENT_EXECUTION_WORKER_ENABLED=false` and the separate `worker` service enables it; database row locks prevent both processes from claiming the same task. Task creation returns `201` with `QUEUED`; the worker executes asynchronously after persistence. `AGENT_EXECUTION_ENABLED=false` disables execution, and Quarkus scheduler configuration can independently disable scheduled jobs. Every external setting is an environment variable with a default in `src/main/resources/application.properties`; optional values use the `disabled` sentinel.

`CodingModelProvider` defines a provider-independent chat/tool-call contract, and the active implementation is chosen at runtime by `AGENT_MODEL_PROVIDER` (`ollama`, the default, or `openai-compatible`). `OllamaCodingModelProvider` implements Ollama's `/api/chat` protocol, including structured assistant tool-call history and named tool-result messages, tool schemas, usage counts, timeouts, and bounded error messages. For models that return a JSON tool request in message content instead of Ollama's native `tool_calls`, it normalizes that response only when the requested tool was offered to the model. The adapter is configured with `OLLAMA_BASE_URL` (default `http://localhost:11434`), `OLLAMA_MODEL` (default `qwen3:8b`), `OLLAMA_TIMEOUT_SECONDS` (default `1200`), and optionally `OLLAMA_NUM_CTX` (default `disabled`), which is sent as `options.num_ctx`; Ollama's default window is often 4096 tokens and a real agent conversation outgrows it, so `disabled` leaves the server default while a non-integer value fails startup rather than silently shrinking the task's working set. It does not download models. Local models emit malformed tool-call JSON often enough that Ollama answers HTTP 500 (`error parsing tool call`) instead of a turn, so transient responses (HTTP 429 and 5xx) are retried with bounded exponential backoff and jitter, controlled by `OLLAMA_RETRY_MAX_ATTEMPTS` (default `5`), `OLLAMA_RETRY_INITIAL_BACKOFF_MILLIS` (default `1000`), and `OLLAMA_RETRY_MAX_BACKOFF_MILLIS` (default `60000`); the retry reason (`rate_limited`, `tool_call_parse_failed`, `server_error`) is reported to metrics. Both adapters share one backoff policy (`ProviderRetryBackoff`) while each decides what is retryable: a malformed tool call is a generation failure worth a fresh sample, not a bad request.

`OpenAiCompatibleCodingModelProvider` targets any server that implements the OpenAI `/chat/completions` contract: OpenAI, OpenRouter, Groq, an Azure-style gateway, or a local runtime such as vLLM or llama.cpp. It sends and receives the standard `messages`/`tools`/`tool_calls` shapes (including the `tool_call_id` that links a tool result to its call) and reuses the same content-based tool-request recovery as the Ollama adapter. Configure `AGENT_OPENAI_BASE_URL` (the base includes the version segment, for example `https://api.openai.com/v1`), `AGENT_OPENAI_MODEL`, an optional `AGENT_OPENAI_API_KEY` (leave unset for a local server that needs no key), and `AGENT_OPENAI_TIMEOUT_SECONDS` (default `1200`). Both endpoint and model use the `disabled` sentinel until configured; selecting this provider without them fails the attempt with an explicit reason rather than making a half-configured call. Only the selected provider is exercised, so an unused adapter cannot fail a run, and the reported model name always comes from the active provider.

Transient provider responses (HTTP 429 and 5xx) are retried with exponential backoff and jitter, honoring a numeric `Retry-After` header when the provider sends one, and controlled by `AGENT_OPENAI_RETRY_MAX_ATTEMPTS` (default `5`), `AGENT_OPENAI_RETRY_INITIAL_BACKOFF_MILLIS` (default `1000`), and `AGENT_OPENAI_RETRY_MAX_BACKOFF_MILLIS` (default `60000`). Non-transient errors such as `401` or a malformed-request `400` are never retried. One HTTP 400 is retried deliberately: when a provider rejects a valid request because its own constrained decoder could not parse the model's output (Groq's `output_parse_failed`, and `tool_use_failed` for a call to a tool that was not offered), a fresh sample usually decodes, so the adapter backs off and resends it and only reports the failure if the retry budget is exhausted. This matters on hosted free tiers, whose per-minute token budgets are small enough that an unmodified agent loop fails mid-task: on Groq's free tier, four throttled calls in one run were absorbed by 23-32 second waits and the task still completed. The final response is reported as-is, so an exhausted retry budget still surfaces the provider's own error text.

Pacing complements retrying. One shared budget spans every turn of every task: `AGENT_RATE_LIMIT_REQUESTS_PER_MINUTE` and `AGENT_RATE_LIMIT_TOKENS_PER_MINUTE` (both default to `disabled`) reserve a sliding one-minute window, and a request whose reservation would exceed it waits for the oldest one to age out. Tokens are estimated as characters divided by four, the same rough convention as the prompt budget. A reservation is made before the call and held for the full minute, a `429` retry keeps its reservation because the provider counted that attempt, and the wait is bounded by `AGENT_RATE_LIMIT_MAX_WAIT_MILLIS` (default `60000`): after that the request is sent anyway with a warning, because the retry loop — not the budget — remains the final defence. Setting the Groq free-tier limits (for example `8000` tokens per minute) turns a burst of retries into a slower, steadier run. Only the OpenAI-compatible adapter consults the budget; a local Ollama server has no quota to protect.

Pacing cannot help when a single request is already too large, because providers cap one request as well as the rate: Groq's free tier answers a request above its per-minute token limit with HTTP 413 rather than throttling it. The loop appends one tool result per call, so over a real repository the conversation grows until every request is rejected — the first live task against a third-party Maven repository failed exactly there, after thirteen tool calls. `AGENT_MAX_HISTORY_CHARACTERS` (default `24000`, `disabled` to keep everything) bounds the tool history a request may carry. The system prompt and the task prompt (which holds the repository map) are never dropped and are not counted against the budget, because they are bounded separately and a large repository map must not shrink the set of files the model can hold at once. When the budget is exceeded the oldest tool exchanges are removed and one note is inserted that names the calls whose results were dropped (for example `read_file(src/main/.../EnumTest.java)`) and says to re-read only the lines still needed; naming them matters because a live run re-read the same test file up to five times after it had been dropped, and the old note told the model not to re-read content it no longer had. A tool result is never separated from the assistant call it answers, because an orphaned `tool` message is invalid for OpenAI-compatible APIs. The distinction between prompt and history is not academic: with a smaller budget the two files this task needed could not both fit, the model re-read whichever file had just been dropped, and it exhausted its tool-call limit without ever proposing a patch. Set the budget so a task's working set fits at once while prompt plus history stays under the provider's per-request cap; the rate-limit figure above is in tokens, so at roughly four characters per token Groq's 8000-token tier leaves room for about `24000` characters of history beside a bounded prompt. Truncation is reported on the worker log, not silently hidden.

Models also differ in how they mark up changes, so `apply_patch` accepts both a complete git unified diff and the Codex envelope (`*** Begin Patch`, `*** Update File:` / `*** Add File:` / `*** Delete File:`, `*** End Patch`) that OpenAI's open-weight `gpt-oss` family emits. The envelope is normalized to a unified diff before anything runs, and normalization is deliberately strict: every hunk must match the file on disk exactly, the normalized result still passes the same path, size and file-count checks, and a hunk that cannot be placed is reported back to the model rather than guessed at. The first update hunk may start directly with content because the Codex grammar makes its `@@` marker optional; exact-match placement still decides. `*** Move to:` is rejected (express a move as an add plus a delete). With Groq, `qwen/qwen3.8-27b` applied a two-file change with a single unified-diff patch and passed Docker validation; `openai/gpt-oss-120b` made the same change by sending two Codex envelopes, both normalized and applied with zero patch failures, and also passed validation. Both runs used the same small local fixture, so neither is evidence for a large third-party repository; a first real task against `stleary/JSON-java` (issue #838) has not completed on the free tier, where both hosted models exhausted their tool budget exploring rather than patching. The platform reported every one of those failures honestly, and the findings and the fixes they produced are recorded in [docs/live-testing-findings.md](docs/live-testing-findings.md).

The current local default is Ollama with `qwen3:8b`. If needed, install it with `ollama pull qwen3:8b`, then check it appears in `ollama list`. Ollama must be running at the configured base URL. Setting `AGENT_MODEL_PROVIDER=openai-compatible` switches to the hosted/OpenAI-compatible adapter instead; see the example in `.env.example`.

The worker's tool allowlist is `list_files`, `search_code`, `find_symbol`, `read_file`, `create_file`, `replace_text`, `related_tests`, `apply_patch`, and `run_validation`; it offers no shell, arbitrary command, network, credential, or GitHub-write tool. Paths, patch size, and edit size are checked by the application. A successful `create_file` or `replace_text` counts as a workspace change just like an applied patch: it suppresses the no-change nudge, invalidates an earlier validation run, and requires validation before a final report is accepted. A failed write is tracked by its repository path and keeps the task out of `COMPLETED` until a later successful write to that path. `replace_text` is the preferred tool for a localized edit to an existing file: it requires an exact, contiguous `oldText` copied from `read_file`, requires that it occur exactly once, accepts an empty `newText` to delete, preserves the file's line endings, and is bounded to 64,000 characters for each text and a 2 MB file and result. A missing or ambiguous snippet changes nothing and the refusal tells the model to re-read and retry; multi-file or multi-hunk changes still use `apply_patch`. Reads are bounded (whole files to 50 KB, any single result to 12,000 characters), `read_file` takes a 1-based line range, and `search_code`, `find_symbol` and `list_files` accept a glob, a depth, and paginated limits so a large repository can be navigated without dumping it into the prompt, and both search tools accept a file or a directory path because a model naturally scopes the search to the file it already suspects. Validation output is trimmed differently: Maven prints the failing tests and the build verdict last, so the runner captures up to 48,000 characters and keeps the start and the end within 12,000 instead of cutting the verdict off. The tools refuse with a next step rather than a dead end, because a live local model exposed how much that matters: a whole-file read of a large file names the file's size in lines so the model can pick a range instead of repeating the refusal, a pattern pasted into `path` is answered with the `glob` argument that takes it, and a patch that `git apply` rejects quotes the numbered lines that are actually at the failed hunk's location - `git apply` itself reports only a path and a line number, and a live 14B guessed twice in a row (3171, then 3173, while the branch it wanted was at 3185). A patch that `git apply` calls corrupt names the offending **patch line** and the rule it broke, because `<stdin>:34` cannot be mapped back to the model's own text and a live 14B put an `index 1234567..89abcde` placeholder inside a hunk. A byte-identical resend of a patch already rejected in the same attempt is refused before `git apply` runs again, because the same live run sent one 1,582-character patch three times in a row; the refusal now quotes the earlier rejection's first line, because the reason can be a missing path or a malformed line, not hunk context. When a patch names a path that does not exist, the refusal looks for an existing file with the same suffix and names it: a live 14B dropped the `src/` prefix, git answered only `main/java/...: No such file or directory`, and the model resent the identical patch instead of fixing the header. `find_symbol` answers "where is this declared?" with `path:line` for declarations before other occurrences, a bounded line scan rather than a compiler, so a model can jump to a method deep inside a large file instead of reading it from the top; a method or function hit also reports the heuristic line where its block ends, because three local runs read only the declaration's first ten lines and patched the method's opening instead of the branch further down. Local repository access is disabled unless `AGENT_ALLOWED_LOCAL_ROOT` is configured; public GitHub HTTPS URLs are also accepted. A task report includes the observed outcome, model-provided narrative (explicitly marked as unverified), changed files, diff (with a truncation flag), validation outcomes, patch failures/recovery, acceptance checks, model token usage, estimated model cost, elapsed time, configured budgets, draft-PR reference/error, and per-tool audit summaries. Before an allowed tool runs, its invocation is committed to a separate audit-event table; after it returns, the event is updated with its outcome and duration. Events remain readable if the process stops before finalizing the attempt report. A `STARTED` event left after a crash means the operation's final outcome is unknown, not proof that it did or did not take effect. Audit records retain tool name, a redacted argument summary, outcome, duration, and a short result summary; they deliberately omit source contents, full patches, and tool outputs. A tool's deliberate refusal of an invalid or disallowed request (a path outside the repository, a whole-file read of a large file, a patch hunk that does not match) is recorded as `REJECTED` with the same actionable message the model received; `FAILED` means the tool raised an unexpected error. Keeping the two apart lets an audit reader tell a model probing a boundary from a broken adapter, and the model gets a next step instead of an opaque failure.

`AGENT_MAX_TASK_DURATION_SECONDS` defaults to `1800`; `AGENT_MAX_TASK_COST_USD` defaults to `1.0`. Budgets are cumulative across completed attempts for one task, including clarification/resume attempts; queue waiting time is excluded. Time is checked between model/tool operations and after each model response; cost is checked against cumulative reported token usage after each response. These are operation-boundary guardrails, not hard preemptive ceilings: an in-progress operation is not forcibly interrupted when a limit is reached. Cost enforcement requires non-zero input/output rates (`AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD`) and reported token counts; if rates are set but current or prior token usage is unavailable, the attempt fails closed. Time enforcement also fails closed when prior attempt duration is unavailable. With the default zero rates, estimated cost is zero and the cost ceiling cannot represent actual inference costs. A worker that stops mid-attempt is reconciled rather than resumed: an attempt left `RUNNING` by a crashed or restarted worker is failed once it is older than `AGENT_STALE_ATTEMPT_TIMEOUT_SECONDS` (default: twice `AGENT_MAX_TASK_DURATION_SECONDS`), recording an explicit "outcome unknown" report so a task is never stuck in `RUNNING` and is never reported as a pass; retrying means creating a new task - `POST /api/tasks/{id}/retry` clones a `FAILED` or `CANCELLED` task and links the clone with `retryOf`.

`NEEDS_INPUT` tasks can be answered through the review page or API. The answer is prepended to the task prompt as a labeled clarification so it remains in the prompt when the original description is long; a status-history entry records the requeue, and a fresh attempt runs against a new workspace. The previous report remains available in attempt history.

When acceptance criteria are supplied, the agent must return one evidence-bearing assessment per criterion. Missing or evidence-free checks prevent completion; failed criteria fail the task, and unverified criteria require input. This is a model assessment, not independent proof: Maven tests, Surefire test-case results, and benchmark-specific expected-diff checks provide separate machine-verifiable evidence. A task is completed only when the workspace changed, all supplied criteria are reported as passed, supported validation passed, and no patch or file-write failure remains unresolved. That precedence is a pure domain policy, `CompletionPolicy`, so the decision table (blocking question and skipped validation first, then known negatives, then unresolved work, then unverified criteria, and only then completion) is tested directly instead of being inferred from the worker's orchestration code. A failed patch, file creation, or text replacement must never be treated as applied just because the final narrative says so. A cited test method is checked against the last validation's Surefire results: the method must be reported `PASSED`, not merely declared in a test file. If the runner cannot expose machine results, the worker falls back to scanning test-like files in the final workspace, skipping VCS, build-output, dependency, and IDE directories; an absent method downgrades the check to `UNVERIFIED`. Both checks only ever weaken a claim, so a fabricated or unexecuted test cannot satisfy completion.

#### Draft pull-request publishing

Pull-request publishing is disabled by default. Set `AGENT_GITHUB_PULL_REQUESTS_ENABLED=true`, `AGENT_GITHUB_TOKEN` to a fine-grained token with **Contents: Read and write** and **Pull requests: Read and write**, and `AGENT_GITHUB_ALLOWED_REPOSITORIES=owner/repository` before starting the app. Keep the token outside source control. The task must use the matching HTTPS GitHub repository URL, and the repository must be publicly cloneable. Publishing commits only the changed paths, pushes a task-specific branch, and creates a draft PR against the repository default branch. If publication is retried after a PR was created, it records the existing PR rather than opening a duplicate; the report includes whether that PR is still open and in draft. The PR contains task context and reported checks; acceptance checks are explicitly labeled as model-assessed. The token is not sent to the model or test container. Pull requests are never merged automatically.

If branch push or PR creation fails, the task is marked `FAILED` with `pullRequestError` in its persisted attempt report; a successfully pushed branch may remain and require manual cleanup. The local review page at `/` displays reports and draft-PR links but does not embed GitHub's review interface, approve changes, or merge. The UI and API are unauthenticated and must remain on localhost or a trusted network. This first slice does not yet provide GitHub issue webhooks, GitHub App installation tokens, or durable retry/recovery after partial publication.

Validation currently supports Maven projects only and runs `mvn -B -o test` in a disposable Linux Docker container with resource limits, a private internal network, a temporary PostgreSQL 18 service, and a read-only Maven cache volume. In Compose, the trusted control-plane container alone receives the Docker socket. The worker calls its authenticated validation endpoint; the control plane rejects workspace paths outside the shared workspace root, and the validator mounts only that attempt's named-volume subdirectory. Test and bounded dependency-fetch commands execute without external network access. After the run, the runner reads Surefire's `target/surefire-reports/TEST-*.xml` from the workspace so a cited test can be checked against what actually executed; that directory is visible through both a bind mount and the shared named volume. The parser is deliberately narrow and hostile-input safe: no DTDs or external entities, a fresh-report cutoff, bounded file count and size, and malformed reports are ignored rather than trusted. Gradle, other build systems, and network-enabled builds are not supported.

The worker container runs non-root with a read-only root filesystem, dropped Linux capabilities, no-new-privileges, a process limit, and CPU/memory limits. It has no Docker socket. The shared workspace and database are reachable by both services; the worker still has the application's database credentials, GitHub credentials when publishing is enabled, and outbound network access needed for GitHub and the configured model endpoint. Compose networking does not enforce a strict egress allowlist, and this deployment is not a hostile multi-tenant boundary. Keep it on a trusted host/network, use a narrowly scoped GitHub token, and do not grant production database access. The control-plane container runs as root to access the Docker socket, has Docker-daemon-equivalent privileges, and is bound to localhost by default; treat it as host-root.

### Containerized worker deployment

The local `quarkus:dev` flow remains available. To run the separated worker/control-plane topology, use Docker Compose with a Linux Docker Engine (API 1.45+ for per-attempt volume subdirectory mounts) and a model endpoint reachable from the worker:

```powershell
Copy-Item .env.example .env
# Edit .env and replace both placeholder secrets with long random values.
.\mvnw.cmd package -DskipTests
docker compose up --build
```

The Compose file creates PostgreSQL, the control plane, and a separate worker. The control plane is available at <http://localhost:8081>; only it mounts `/var/run/docker.sock`. Both services share named workspace and Maven-cache volumes. `AGENT_CONTROL_PLANE_TOKEN` authenticates worker-to-control-plane validation requests. The app defaults it to a `disabled` sentinel so a local run needs no token, and configuring a control-plane URL without a real token fails closed at startup; Compose requires a real value; `AGENT_GITHUB_TOKEN` is configured only in the worker environment (never the API or validation containers) and is used only when publishing is enabled. The worker uses `AGENT_MODEL_PROVIDER` (default `ollama`) and `OLLAMA_BASE_URL` (default `http://host.docker.internal:11434`); to use a hosted model instead, set `AGENT_MODEL_PROVIDER=openai-compatible` plus `AGENT_OPENAI_BASE_URL`, `AGENT_OPENAI_MODEL`, and `AGENT_OPENAI_API_KEY` (all passed through by `compose.yaml`). If Ollama is on the host, configure it to listen on an address reachable from Docker and use host firewall rules to limit access. Set `AGENT_GITHUB_PULL_REQUESTS_ENABLED=true` and configure an allowlisted repository/token in `.env` only when draft-PR publishing is intended. Do not commit `.env`.

For this deployment, use public GitHub HTTPS repository URLs; local host paths are intentionally unavailable because no host source tree is mounted into the worker. `docker compose down` stops services but retains database and workspace volumes; `docker compose down -v` deletes that persisted data.

### Local review page

Open <http://localhost:8081/> to list tasks and inspect the latest attempt, report, diff, validation checks, persisted tool-audit events, open questions, and draft-PR link. Tool events are loaded from their own endpoint rather than only from the final report, so events from an unfinished attempt remain visible. For a `NEEDS_INPUT` task, submit a clarification there; the task is requeued and the worker starts another attempt. A reviewer can instead confirm the existing attempt when its diff and validation are sound: the page asks for reviewer and evidence, and the server enforces the machine checks before completing the task and enabling patch export. A completed attempt with an untruncated diff offers a patch download. This is a local operator view, not an embedded GitHub review client. Protect the app from untrusted network access because the API and page are unauthenticated.

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
NEEDS_INPUT ──(confirm acceptance)──→ COMPLETED
```

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal. `NEEDS_INPUT` can transition to `QUEUED` only through the answer endpoint, which prepends the clarification to the task prompt and starts another attempt. The supported path from `NEEDS_INPUT` to `COMPLETED` is the attempt confirmation endpoint, which records human evidence and requires the attempt to have a changed workspace, passing supported validation, a complete diff, no failed criterion, and no blocking model question. The generic administrative `PATCH /api/tasks/{id}/status` can change a task's status directly, but it does not update the attempt or make a patch exportable, so it cannot stand in for confirmation. Other invalid and repeated transitions are rejected without changing task state or writing history.

### From task to change: the execution flow

Every task follows the same bounded pipeline. The workspace is disposable — it is deleted when the attempt ends — so the change survives only in the durable attempt report and, when publishing is enabled, the draft pull request.

```text
POST /api/tasks ──► QUEUED (persisted in PostgreSQL)
                        │
                        ▼
      worker poll (every 2s): reap orphaned attempts, then claim one task
                        │  database row lock · one RUNNING attempt per task
                        ▼
      attempt RUNNING ──► disposable workspace
                        │  (allow-listed local root, or a public GitHub HTTPS URL)
                        ▼
      bounded model/tool loop ─────────────────────────────────────────────────┐
        prompt: ranked repository map (BM25 + PageRank, token-budgeted)        │
        model proposes ─► application authorizes ─► audit ─► execute           │
        tools: list_files · search_code · find_symbol · read_file ·          │
               create_file · replace_text · related_tests · apply_patch ·    │
               run_validation (Docker, offline `mvn -B -o test`)             │
        limits: tool calls · elapsed time · estimated cost                     │
        ▲──────────────────────────────────────────────────────────────────────┘
                        ▼
      evaluate completion ──► VALIDATING ──► COMPLETED | FAILED | NEEDS_INPUT
                        ▼
      durable attempt report (PostgreSQL): changed files, diff + truncation flag,
      validation checks, acceptance checks, tool audit, token usage, cost, duration
                        ▼
      COMPLETED and publishing enabled ──► draft pull request (never merged)
```

`COMPLETED` means "a patch was produced with an honest report", not "applied" or "approved".

Before the first model turn the worker seeds the prompt with a bounded repository map so the model does not have to discover a whole tree one listing at a time. Candidate files are ranked lexically: BM25 over identifier tokens (camelCase and snake_case are split) measures how well a file matches the task title, description and acceptance criteria, while PageRank over "file A names the type declared in file B" edges lifts structurally central files the task text never names. Entries carry up to six declared symbols, and a greedy packer fits them into a token budget estimated as characters divided by four. Every walk is capped by file count, per-file bytes and total bytes; build output, dependencies and binary files are skipped. The map is labelled in the prompt as a heuristic, not an index of truth, and the model is told to confirm content with `read_file` before changing it. Set `AGENT_REPO_MAP_ENABLED=false` (or `agent.context.repo-map.enabled=false`) to omit it, and tune `AGENT_REPO_MAP_MAX_TOKENS`, `AGENT_REPO_MAP_MAX_ENTRIES`, `AGENT_REPO_MAP_MAX_INDEXED_FILES` and `AGENT_REPO_MAP_MAX_FILE_BYTES` to change its bounds. If building the map fails, the task continues without it rather than failing.

After a patch, `related_tests` answers "what else does this touch?" without a build. It indexes declared type names and the capitalized identifiers each file mentions, then walks a bounded reference graph from the given paths (or from `git status` when none are given): a test that mentions a changed type is selected, and a non-test file joins the next hop so `FooService` → `FooController` → `FooControllerTest` is still found. Depth, scanned files, per-file bytes and result count are capped, deleted files fall back to their file stem, and the result is a reading list only — validation still runs the supported suite.

### Handoff: what to do with a completed change

```text
attempt COMPLETED (workspace changed AND supported validation passed)?
│
├─ no ─► FAILED       ─► open a new task (the report explains why)
│         NEEDS_INPUT ─┬► POST /api/tasks/{id}/answer ─► QUEUED ─► new attempt
│                      └► POST …/attempts/{attemptId}/confirm ─► COMPLETED
│                            only when the stored diff and validation are sound;
│                            the patch becomes exportable through route B
│
└─ yes ─► choose a route
          │
          ├─ A · draft pull request  (primary handoff)
          │     branch agent/task-<id> ─► DRAFT PR against the default branch
          │     a person reviews and merges · never auto-merged
          │
          ├─ B · patch export, applied locally  (fallback)
          │     GET /api/tasks/{id}/attempts/{attemptId}/patch ─► text/x-patch
          │     git apply on YOUR clean worktree (opt-in) · no commit, push, or PR
          │
          └─ C · read the report  (audit / manual port)
                GET /api/tasks/{id}/attempts/{attemptId} ─► report.patchDiff,
                changedFiles, validation, acceptance checks, tool audit
```

| Route | Source | Requires | Who acts |
| --- | --- | --- | --- |
| A · draft PR | `GitHubPullRequestPublisher` | publishing enabled, allow-listed public repo, task `COMPLETED` | a person, on GitHub |
| B · patch | `GET …/patch` | task **and** attempt `COMPLETED`, all validation passed, one matching evidenced passing check per criterion, not truncated | a person, `git apply` locally |
| C · report | `GET …/attempts/{attemptId}` | none | a person, reading or porting |

The export policy lives in `TaskAttemptService.exportablePatch`, so no client re-implements it. When the stored diff is too large it is flagged `patchDiffTruncated`, and Route B refuses it (`409`) rather than return text `git apply` would reject; for such a change, Route A is the reproducible path.

#### Confirming a validated attempt that ended NEEDS_INPUT

A run can produce the correct, validated change and still end `NEEDS_INPUT` because the model left one or more acceptance criteria unevidenced. If you have reviewed the stored diff and validation, confirm the existing attempt instead of starting over:

```powershell
$taskId = '<task-id>'
$attemptId = '<attempt-id>'
$confirmation = @{
  reviewer = 'Daniel'
  evidence = 'Reviewed the diff: the null guard returns Hello guest and the new test asserts it; Maven tests passed.'
} | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/tasks/$taskId/attempts/$attemptId/confirm" `
  -ContentType 'application/json' -Body $confirmation
```

The endpoint is not a bypass. It returns `409` unless the task and attempt are `NEEDS_INPUT`, the attempt changed files, every supported validation check passed, the stored diff is present and untruncated, no criterion was reported `FAILED`, and no blocking model question remains. It records the reviewer, evidence, and timestamp in `report.review`, replaces `acceptanceChecks` with the human-confirmed evidence, preserves the model's original checks under `modelAcceptanceChecks`, removes the synthetic "Acceptance criteria were not verified" question, and writes a `REVIEWER` status transition. The patch endpoint then works under Route B.

Manual confirmation does not publish a draft pull request: the worker has already finished, so the platform does not push a branch. The change is yours to export and review. The generic `PATCH /api/tasks/{id}/status` remains an administrative status override; it does not update the attempt or make a patch exportable.

#### Applying a completed attempt's patch (Route B)

The patch is a normal unified diff against the revision the attempt started from, so the target worktree must be at that revision and clean. The API must be running (`.\mvnw.cmd quarkus:dev`).

1. Find a completed task and its attempt:

   ```powershell
   $base = 'http://localhost:8081'
   $task = Invoke-RestMethod "$base/api/tasks" | Where-Object status -eq 'COMPLETED' | Select-Object -First 1
   $attempt = Invoke-RestMethod "$base/api/tasks/$($task.id)/attempts" |
              Sort-Object attemptNumber -Descending | Select-Object -First 1
   $task.id; $attempt.id; $attempt.status
   ```

   A recorded example from this project's database:

   ```text
   e30b7410-1ffb-461a-9292-694f6e496d5b
   e95bcc2b-959a-465d-afb0-b1b4bcbd378f
   COMPLETED
   ```

2. Preview the patch (served as `text/x-patch`):

   ```powershell
   Invoke-RestMethod "$base/api/tasks/$($task.id)/attempts/$($attempt.id)/patch" |
     Out-String -Width 200
   ```

   For the attempt above the result is 886 characters:

   ```diff
   diff --git a/src/main/java/example/Greeting.java b/src/main/java/example/Greeting.java
   index 2b58eb3..264559b 100644
   --- a/src/main/java/example/Greeting.java
   +++ b/src/main/java/example/Greeting.java
   @@ -6,4 +6,8 @@ public final class Greeting {
        public static String message() {
            return "Hello";
        }
   +
   +    public static String greeting(String name) {
   +        return "Hello, " + name + "!";
   +    }
    }
   diff --git a/src/test/java/example/GreetingTest.java b/src/test/java/example/GreetingTest.java
   index 6543aad..36b7c99 100644
   --- a/src/test/java/example/GreetingTest.java
   +++ b/src/test/java/example/GreetingTest.java
   @@ -9,4 +9,9 @@ class GreetingTest {
        void returnsTheDefaultGreeting() {
            assertEquals("Hello", Greeting.message());
        }
   +
   +    @Test
   +    void greetsByName() {
   +        assertEquals("Hello, Ada!", Greeting.greeting("Ada"));
   +    }
    }
   ```

   A non-exportable attempt answers `409` with the reason, for example:

   ```text
   HTTP 409 {"message":"Task d7034f9c-3820-4427-87e8-ea9b175f41d4 is FAILED; only a COMPLETED task has a patch that can be applied locally"}
   ```

   An unknown task or attempt answers `404`.

3. Export and check the patch without changing your files:

   ```powershell
   .\scripts\Apply-TaskPatch.ps1 `
     -TaskId $task.id `
     -AttemptId $attempt.id `
     -RepositoryPath 'C:\path\to\your\clone'
   ```

   ```text
   Patch exported and checked against 'C:\path\to\your\clone': 'C:\...\e30b7410-....patch'. No repository files were changed. Re-run with -Apply to apply it.
   ```

   The helper requires a clean worktree and a successful `git apply --check`, writes the patch under the system temporary directory, and surfaces the server's `409` reason instead of re-implementing the export policy.

4. Apply it, then review and test:

   ```powershell
   .\scripts\Apply-TaskPatch.ps1 `
     -TaskId $task.id `
     -AttemptId $attempt.id `
     -RepositoryPath 'C:\path\to\your\clone' `
     -Apply
   ```

   ```text
   Patch applied to 'C:\path\to\your\clone'. Review the changes and run the repository's tests.
   ```

   For the recorded attempt this changes exactly two files:

   ```powershell
   git -C 'C:\path\to\your\clone' status --porcelain
   ```

   ```text
    M src/main/java/example/Greeting.java
    M src/test/java/example/GreetingTest.java
   ```

5. Commit, branch, and push yourself. Route B never commits, creates a branch, or opens a pull request.

   On Windows, if PowerShell reports that running scripts is disabled, use a process-scoped policy (it does not change the machine policy):

   ```powershell
   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
   ```

#### Reading the report instead (Route C)

Raw retrieval of the stored report works for any attempt, including one that is not exportable:

```powershell
$taskId = 'e30b7410-1ffb-461a-9292-694f6e496d5b'
$attempts = Invoke-RestMethod -Uri "http://localhost:8081/api/tasks/$taskId/attempts"
$attempt = $attempts | Sort-Object attemptNumber -Descending | Select-Object -First 1
$attempt.report.patchDiff
```

Treat `report.patchDiff` as review material, not as a guaranteed-appliable patch: a large diff is stored truncated and flagged (`patchDiffTruncated`), and Route B refuses it for that reason. A task with no acceptance criteria has no criterion checks to verify.

### Architecture and code map

The codebase follows a **hexagonal (ports-and-adapters) architecture** with four layers. Dependencies point inward: `api` depends on `application` and `domain`; `application` depends on `domain`; `infrastructure` implements `domain` ports. `ArchitectureTest` enforces those directions with ArchUnit and fails the build on a new reverse edge. Two couplings are explicit, documented debt rather than hidden drift: application services use the Panache repositories directly, and they map API request/response DTOs; the provider SPI also uses Jackson's `JsonNode` in the domain. ADR-0014 records why and what would remove them.

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
│   Persistence, model HTTP, Git workspaces, Docker,       │
│   process execution, tool execution                      │
└──────────────────────────────────────────────────────────┘
```

#### `dev.agentplatform.api` — Inbound adapters (REST)

- `api/TaskResource.kt` — REST routes, input validation, HTTP status codes.
- `api/InternalExecutionResource.kt` — bearer-authenticated validation gateway restricted to the shared workspace root.
- `api/dto/TaskDtos.kt` — API data transfer objects: `CreateTaskRequest`, `TransitionTaskRequest`, `AnswerTaskRequest`, `ConfirmAttemptRequest`, `TaskStatusTransitionResponse`, `TaskAttemptResponse`, `ApiError`.
- `src/main/resources/META-INF/resources/index.html` — local task/PR review page (stored diff, truncation label, patch download), `NEEDS_INPUT` answer form, and human acceptance-confirmation form.

#### `dev.agentplatform.domain` — Core business logic (no framework dependencies)

- `domain/model/CodingTask.kt` — Task entity with UUID, timestamps, and ordered acceptance criteria.
- `domain/model/TaskStatus.kt` — `TaskStatus` enum (`QUEUED`, `RUNNING`, `VALIDATING`, `COMPLETED`, `FAILED`, `NEEDS_INPUT`, `CANCELLED`).
- `domain/model/TaskStatusTransition.kt` — Append-only status history entity.
- `domain/model/TaskLifecycle.kt` — State machine policy: allowed transitions and `InvalidTaskTransitionException`.
- `domain/model/CompletionPolicy.kt` — Pure terminal-status precedence from validation, workspace, acceptance, and unresolved-failure evidence.
- `domain/model/TaskAttempt.kt` — Attempt entity and `AttemptStatus` enum.
- `domain/model/TaskExecutionReport.kt` — Value objects: `TaskExecutionReport`, `HumanReview`, `ToolExecutionAudit`, `ToolExecutionOutcome`, `AgentDecision`, `AgentQuestion`, `AcceptanceCheck`, `ModelUsage`, `PullRequestReference`, `ValidationCheck`, `CheckOutcome`; `review` and `modelAcceptanceChecks` preserve human-confirmation provenance.
- `domain/model/ToolExecutionEvent.kt` — Durable per-call audit entity linked to its task and attempt.
- `domain/port/CodingModelProvider.kt` — Provider-neutral LLM chat/tool-calling SPI (messages, tool definitions, tool calls, turns).
- `domain/port/WorkspaceTools.kt` — The application's boundary to repository inspection/editing: tool definitions, prompt repository map, and execution with JSON-text arguments. The adapter, not the caller, owns the allow-list and path checks.
- `domain/port/ExecutionPorts.kt` — Outbound port interfaces: `TaskExecutionState`, `AttemptOperations`, `RepositoryWorkspaceFactory`, `RepositoryWorkspace`, `CommandExecutor`, `CommandResult`, `ValidationRunner`, `PullRequestPublisher`, `ToolExecutionResult`.

#### `dev.agentplatform.application` — Use cases and orchestration

- `application/service/TaskService.kt` — Task CRUD and transition use cases, including the reviewer completion transition; implements `TaskExecutionState` port.
- `application/service/TaskAttemptService.kt` — Attempt lifecycle, report serialization, human acceptance confirmation, and patch-export policy; implements `AttemptOperations` port.
- `application/worker/TaskExecutionWorker.kt` — Scheduled task claim, bounded model/tool reasoning loop, report generation.
- `application/worker/TaskExecutionFinalizer.kt` — Terminal attempt and task state updates after execution.

#### `dev.agentplatform.infrastructure` — Outbound adapters (frameworks, I/O)

- `infrastructure/persistence/TaskRepository.kt` — Panache repository for `CodingTask`.
- `infrastructure/persistence/TaskAttemptRepository.kt` — Panache repository for `TaskAttempt`.
- `infrastructure/persistence/ToolExecutionEventRepository.kt` — Ordered durable tool-event queries.
- `infrastructure/persistence/TaskStatusTransitionRepository.kt` — Panache repository for ordered status history.
- `infrastructure/ollama/OllamaCodingModelProvider.kt` — Ollama `/api/chat` HTTP adapter; implements `CodingModelProvider`.
- `infrastructure/openai/OpenAiCompatibleCodingModelProvider.kt` — OpenAI `/chat/completions` HTTP adapter for hosted or local OpenAI-compatible endpoints; implements `CodingModelProvider`.
- `infrastructure/model/SelectingCodingModelProvider.kt` — Runtime selection of the active provider from `agent.model.provider`, plus the CDI qualifiers that keep the concrete adapters off the plain `CodingModelProvider` type, and the shared content-based tool-request extractor.
- `infrastructure/github/GitHubPullRequestPublisher.kt` — allowlisted branch push and GitHub draft-PR creation; implements `PullRequestPublisher`.
- `infrastructure/workspace/GitRepositoryWorkspaceFactory.kt` — Authorized local/GitHub repository cloning into isolated workspaces; implements `RepositoryWorkspaceFactory`.
- `infrastructure/tool/WorkspaceToolExecutor.kt` — Path-checked, allowlisted tool execution for the model loop; implements `WorkspaceTools`.
- `infrastructure/docker/DockerValidationRunner.kt` — Disposable Docker Maven/PostgreSQL validation runtime; implements `ValidationRunner`.
- `infrastructure/ExecutionControlHttp.kt` — Worker HTTP client for the authenticated validation gateway.
- `infrastructure/process/LocalCommandExecutor.kt` — Argument-vector process runner with bounded output and timeouts; implements `CommandExecutor`.

#### Resources, tooling, and build

- `src/main/resources/application.properties` — App name, HTTP port (`8081`), database configuration, and every agent setting (model, budgets, workspace roots, Docker images/volumes, control plane, GitHub); optional values use the `disabled` sentinel.
- `benchmarks/v1/cases.json` and `benchmarks/v1/Invoke-Benchmark.ps1` — Versioned coding benchmark cases and API-driven evaluator; also runs `benchmarks/v2/cases.json` via `-ManifestPath`.
- `scripts/Apply-TaskPatch.ps1` — Export a completed attempt's patch and optionally apply it to a clean local Git worktree.
- `compose.yaml` and `.env.example` — Separate worker/control-plane deployment with named workspace/cache volumes and no Docker socket in the worker.
- `src/main/resources/db/migration/` — Flyway-managed PostgreSQL schema migrations (V1 tasks, V2 status history, V3 attempts, V4 one-running-attempt constraint, V5 tool execution events).
- `pom.xml` — Maven build, Kotlin compiler, Quarkus platform and extensions, ArchUnit boundary tests, and the JaCoCo coverage gate (95% line / 85% branch).

#### Tests (mirror the main source structure under `src/test/kotlin/dev/agentplatform/`)

- `AgentWorkflowIntegrationTest.kt` — end-to-end REST submission through workspace creation, the scripted model/tool loop, validation, and the stored report.
- `ArchitectureTest.kt` — ArchUnit rules pinning outward-inward layer dependencies and documenting the known exceptions.
- `AgentWorkflowTestSupport.kt` — shared fixture: scripted Ollama HTTP server and isolated workspace/repository roots.
- `api/TaskResourceTest.kt` — Quarkus REST and PostgreSQL integration tests, including patch-export responses.
- `api/InternalExecutionResourceTest.kt` — Validation-gateway authentication, workspace-root, invalid-path, and unavailable-configuration tests.
- `domain/model/TaskLifecycleTest.kt` — State machine transition-policy unit tests.
- `domain/model/CompletionPolicyTest.kt` — Decision-table and precedence tests for the terminal-status policy.
- `application/service/TaskServiceTest.kt` — Task service integration tests, including reviewer completion.
- `application/service/TaskAttemptTest.kt` — Attempt lifecycle, human-confirmation guards, export-policy, and orphan-recovery query tests.
- `application/worker/TaskExecutionWorkerTest.kt` — Worker, model/tool loop, budget, and orphan-reaping tests.
- `application/worker/TaskExecutionFinalizerTest.kt` — Finalizer state-update and orphan-failure tests.
- `infrastructure/ollama/OllamaCodingModelProviderTest.kt` — Ollama adapter mock-HTTP tests.
- `infrastructure/openai/OpenAiCompatibleCodingModelProviderTest.kt` — OpenAI-compatible adapter mock-HTTP tests (tool calls, `tool_call_id` threading, content fallback, auth header, fail-closed configuration).
- `infrastructure/model/SelectingCodingModelProviderTest.kt` — Provider-selection routing and unknown-provider rejection tests.
- `infrastructure/ExecutionControlHttpTest.kt` — Worker-to-control-plane HTTP contract and failure tests.
- `infrastructure/workspace/GitRepositoryWorkspaceFactoryTest.kt` — Workspace cloning, safety, build-output exclusion, and diff-truncation tests.
- `infrastructure/tool/WorkspaceToolExecutorTest.kt` — Path and patch safety tests.
- `infrastructure/docker/DockerValidationRunnerTest.kt` — Docker runner tests, including bind-mount and named-volume Surefire result evidence.
- `infrastructure/docker/SurefireReportsTest.kt` — Secure Surefire XML parsing, stale/oversized/malformed rejection, and result bounds.
- `infrastructure/github/GitHubPullRequestPublisherTest.kt` — Allow-list, branch-push, and draft-PR publishing tests.
- `infrastructure/process/LocalCommandExecutorTest.kt` — Process execution tests.
- `infrastructure/process/CommandFixture.kt` — Test helper for command execution.

Task data, status history, attempts, and attempt reports are durable in PostgreSQL. Flyway creates and evolves the schema; Hibernate validates the mapped schema at startup rather than creating tables implicitly. Reports are stored as JSON text and include the generated patch diff (and whether it was truncated), validation details, failure and patch-recovery details, acceptance assessments, model usage, and any draft-PR reference/error. Estimated costs use optional configured per-million-token rates; they default to zero for a local model and are estimates, not provider invoices.

## Open in IntelliJ IDEA

1. Open the repository root with **File → Open**.
2. Use the Maven wrapper in **Settings → Build Tools → Maven** and set the Maven Runner JRE to JDK 21 or newer. The installed IntelliJ JBR is suitable.
3. Reload the Maven project after changing `pom.xml` so IntelliJ picks up the Quarkus plugin configuration.

From PowerShell in the project directory, set `JAVA_HOME` to a JDK 21+ installation (IntelliJ's bundled JBR works):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
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

The suite includes transition-policy and REST/database tests, ArchUnit layer-boundary rules, provider protocol tests, worker/report lifecycle tests, path and patch safety tests, repository workspace tests, command execution tests, and Docker-runner tests. `api/InternalExecutionResourceTest.kt` directly covers the validation gateway's authentication, workspace-root checks, invalid paths, and unavailable configuration; `infrastructure/ExecutionControlHttpTest.kt` covers the worker-to-control-plane HTTP contract and failures. `application/AgentWorkflowIntegrationTest.kt` exercises REST submission through real workspace creation, scripted Ollama tool calls, durable audit retrieval, validation, draft-PR reporting, `NEEDS_INPUT` answer/resume, failed validation, unapproved-tool rejection, and the authenticated control-plane path restriction. It uses a local Git fixture and a scripted model server; PostgreSQL remains the test database. Run these focused suites with `.\mvnw.cmd "-Dtest=InternalExecutionResourceTest,ExecutionControlHttpTest,AgentWorkflowIntegrationTest" test`. `mvnw verify` enforces a coverage gate of **95% lines and 85% branches** for measured application code. A literal 100% branch ratio is deliberately not required: Kotlin emits synthetic default-argument dispatch branches onto data holders (entities, DTOs, value objects), and a few branches are environment-specific (the Windows-only Java argument-file path, JVM interrupt handling), so hitting 100% would require contrived tests rather than meaningful verification. The current ratios are reported under `target/site/jacoco`.

### Repeatable coding benchmark

`benchmarks/v1/cases.json` is a small, versioned benchmark of three deterministic Java/Maven coding tasks against a common fixture. The PowerShell runner creates a Git baseline for the fixture on first use, submits each task through the API, and evaluates task status, Maven validation, reported acceptance checks, expected changed files, expected diff content, elapsed time, token usage, and estimated cost. Each task runs from a fresh workspace copy of that same committed fixture. Results are written to `benchmarks/v1/results-v1.json`.

Before running it:

1. Set `AGENT_ALLOWED_LOCAL_ROOT` to a parent directory containing `benchmarks/v1/fixture`, and set `AGENT_WORKSPACE_ROOT` to a separate workspace directory.
2. Ensure the host Maven cache contains the fixture's Maven/JUnit dependencies. The tests themselves execute offline in Docker.
3. Start the API and Ollama model, then run `.\benchmarks\v1\Invoke-Benchmark.ps1`. For paid model comparisons, set `AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD` to the current provider rates before starting the API.

The first run initializes and commits the benchmark fixture locally; later runs require that fixture to be clean. Estimated token costs are zero by default for Ollama. Set `AGENT_MODEL_INPUT_COST_PER_MILLION_USD` and `AGENT_MODEL_OUTPUT_COST_PER_MILLION_USD` to the chosen model's rates when comparing paid models. The benchmark is only a smoke-sized baseline (three cases), not a statistically meaningful model-quality claim; report the sample size and benchmark version with any success rate.

`benchmarks/v2/cases.json` (version **v2.2**) retains those three cases, adds a no-change clarification control, makes `greeting-name`'s unit-test requirement an explicit acceptance criterion, and adds a second fixture domain (`Slugifier`) with a string-processing task. Run it against the shared fixture with:

```powershell
.\benchmarks\v1\Invoke-Benchmark.ps1 `
  -ManifestPath .\benchmarks\v2\cases.json `
  -RepositoryPath .\benchmarks\v1\fixture `
  -OutputPath .\benchmarks\v2\results-v2.json
```

The scorecard uses the **last** validation result, requires exactly one evidence-bearing passing assessment for each supplied criterion, can require an exact changed-file set, and checks that a clarification case produced neither changed files nor a diff. It reports false-success count/rate for expected non-completion cases, per-case patch failures, elapsed time, model usage, and validation outcomes. The diff/file checks are lightweight benchmark oracles, not proof of behavior; acceptance assessments remain model-authored. The prompt version is currently marked `unversioned`, and five cases are still only a smoke suite. Expand the suite with independently tested outcomes and diverse maintenance tasks before making quality claims.

The first measured v2 baseline (2026-10-25, local `qwen2.5-coder:14b`, jar `e97a440`) ran all four cases: **2/4 successes, 0 false successes**, with both completed cases passing Docker validation and exactly the expected two-file diff. One completion case failed after four rejected patches and three identical `replace_text` retries, finishing unchanged after 40 minutes; the clarification control ended `FAILED` because the model never asked a blocking question. The sample is four cases and one run, so this is a smoke baseline, not a model-quality claim.

The follow-up added a bounded `replace_text` hint that shows the actual numbered lines around the closest matching snippet line, plus the v2.1 unit-test criterion for `greeting-name`. An earlier v2 run had completed with only the source change because the acceptance contract did not require the test; v2.1 makes the contract and the expected-file oracle agree. Two v2.1 `greeting-name` reruns both failed honestly (**0/2, 0 false successes**): one changed both files but left a test that used `assertThrows` without importing it, even after the new hint fired twice; the other sent 13 rejected patches (9 byte-identical resends refused by the duplicate guard), broke the source, and exhausted the 24-tool-call budget. The hint works as designed but does not guarantee recovery; the existing patch-failure nudge and duplicate refusals did not change the model's behavior in the second run, so more feedback text is not the next move.

The v2.2 slice added the `Slugifier` fixture domain and a fifth case. Its first run exposed a real cumulative-patch bug: a rejected two-file Codex patch was corrected by two separate `replace_text` calls, but the worker required one later call to write every path the patch named, so a correct source-and-test change ended `NEEDS_INPUT` with "A patch failed and was not corrected". `ExecutionTrace.resolvePatchSuccess` now subtracts the paths written by each successful call and clears the failure only when none remain; a worker regression test covers the exact two-file sequence and `mvnw verify` passes 301 tests with the coverage gate met. A post-fix rerun did not repeat that sequence: the model narrated the change four times without calling a write tool, validation passed on the unchanged tree, and the unchanged-task rule failed the attempt (**0 false successes**). The slugifier case is therefore **0/2** with two distinct outcomes, and the fix is verified by the regression test rather than by a live completion.

#### What the numbers do and do not say

There is **no published model-quality success rate yet**, and that is a deliberate limitation rather
than an oversight: five smoke cases and a few targeted reruns are not enough evidence to claim the
agent is useful on real repositories. The first v2 baseline (2/4, 0 false successes) is a smoke
measurement, not that number.

- **Measured today:** task status, the last Maven validation outcome, evidence-bearing acceptance
  assessments (exactly one per criterion), expected changed-file sets and diff fragments, elapsed time,
  token usage, estimated cost, and — for the v2 negative control — the **false-success rate** (a case
  that should have stopped for input but reported success instead).
- **Not measured:** generalization to unfamiliar repositories, multi-file reasoning, languages other than
  Java/Maven, or long-horizon tasks. No SWE-bench-style or real-repository evaluation has been run.

To publish a credible number: expand to at least 20–30 independently specified maintenance tasks across
more than one repository, freeze the prompt and model version, run each case N times, and report the pass
rate **together with** the false-success rate and a failure taxonomy. Until that exists, treat any claim
about model quality as unproven. The scaffolding for this is in `benchmarks/`; the missing part is cases
and sample size, not tooling.

### Metrics

`quarkus-micrometer-registry-prometheus` exposes the standard scrape endpoint at `/q/metrics`, so a local Prometheus (or `curl localhost:8081/q/metrics`) can show how an agent run behaves over time. The meters are deliberately low-cardinality: task ids, repository paths and prompt text never become tags.

| Meter | Tags | Recorded when |
| --- | --- | --- |
| `agent.model.requests` | `provider`, `outcome=success\|failure` | one model call finishes or throws |
| `agent.model.retries` | `provider`, `reason=rate_limited\|server_error\|tool_call_parse_failed\|provider_generation_error` | a transient response, or a valid one the provider could not decode, is retried |
| `agent.model.rate.limit.wait` | `provider` | a request waits on the pacing budget (timer) |
| `agent.tool.calls` | `tool`, `outcome=success\|rejected\|failed` | an allow-listed tool call returns or throws |
| `agent.tasks.finished` | `status=COMPLETED\|FAILED\|NEEDS_INPUT` | a terminal task status is persisted |

`rejected` is a policy failure (unknown tool, path escape, size or file-count limit); `failed` is an execution error. Both matter: a rising rejection count means the model is probing the boundary, which is a prompt or tool-description problem rather than an infrastructure one. The endpoint is unauthenticated like the API, so keep it on localhost or a trusted network.

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
- **Persist before adding sophisticated orchestration:** tasks, attempts, status history, tool-audit events, and reports live in PostgreSQL. Add leases/heartbeats, bounded retries, and cancellation as the workflow needs them. A database-backed worker with bounded claims is a smaller first reliability step than immediately introducing RabbitMQ or a workflow engine. Revisit a broker when multiple workers or throughput requirements justify it.
- **LLM provider:** the source brief uses Anthropic Claude. Keep the provider behind an interface so model selection and evaluation can change without rewriting the workflow. No provider or API key has been configured.
- **Human approval:** create draft/proposed pull requests only. Do not auto-merge or deploy.
- **Repository content is untrusted:** issue text and files can contain prompt injection or malicious code. Model tool requests are proposals; application policy must authorize and constrain each operation.

## Architecture evolution: synchronous MVP to async workers

The current implementation is a **Quarkus** REST API with an in-process worker for local development and a container-separated worker option backed by PostgreSQL. The API stores a task as `QUEUED`; the worker claims it, creates an attempt, opens a workspace, runs a bounded model/tool loop, requests isolated validation from the control plane, and persists the report and terminal state. This remains an early single-worker slice: there are no leases, heartbeats, automatic retries, or execution cancellation, and in-flight work is not resumed after a crash. Instead, an attempt abandoned by a stopped worker is reconciled by failing it once it exceeds a configurable stale timeout, recording an explicit unknown outcome rather than resuming or retrying it.

When execution becomes asynchronous and needs to survive API restarts or scale independently, evolve toward this flow:

```text
Client → Quarkus API → PostgreSQL (task + attempt)
                           ↓
                    RabbitMQ job message
                           ↓
                    Worker process → model-provider interface → Ollama/OpenAI-compatible / controlled tools
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
2. **Execution model (implemented first slice):** attempt records, database-backed claims, and local or container-separated worker. Orphaned attempts are now failed after a stale timeout; next add leases/heartbeats, bounded retries, cancellation, and stronger idempotency.
3. **Asynchronous API (implemented first slice):** task creation persists and returns `201 Created` with `QUEUED`; expose task/attempt progress while a worker executes asynchronously. Consider `202 Accepted` if the API contract is revised.
4. **Broker-backed dispatch:** add RabbitMQ only when independent worker scaling or durable dispatch requirements justify it. Introduce an outbox with broker publication and idempotent consumption as part of reliable dispatch.
5. **Operational hardening:** add recovery for expired leases, retry/backoff limits, dead-letter handling, concurrency and provider-rate limits, and observability before increasing worker count.

Treat these as architecture guidance, not commitments to add every component now. Prefer the simplest stage that meets the current reliability and scaling needs.

### Execution definition of done

Given an authorized repository and a task with acceptance criteria, the agent should inspect relevant code, identify related tests, make a focused patch in an isolated working copy, update the tests that assert the behavior it changes (and add a regression test when none covers it), run relevant validation, and return a reviewable report. The report explains what changed, why important implementation choices were made, alternatives considered where useful, unresolved questions, changed files, and the actual validation outcomes. If an unresolved question blocks safe progress, the attempt should finish as `NEEDS_INPUT` with the question and its impact; it must not claim completion. A failed or unavailable check must be reported as such, never as a pass.

"Completed" means execution produced a patch and an honest validation report, or a reviewer explicitly confirmed a validated attempt that ended `NEEDS_INPUT` because its report left criteria unevidenced. It does not mean the patch was approved, merged, or deployed. Keep human review as the gate for later pull-request or release actions.

## Recommended roadmap

Do these as small vertical slices. Keep the app runnable after each slice and document the behavior and limits that actually exist.

### 1. Make one narrow task class reliable

Target a small Java/Maven bug fix on a disposable repository: locate the exact code, change it, update the test that asserts the old behavior, and pass Docker validation. `replace_text` removes the unified-diff hunk bottleneck for a localized edit while every write remains path-checked and every unresolved write keeps blocking completion. Use the local `qwen2.5-coder:14b` fixture runs to reproduce one complete attempt before widening the task class. **Exit gate:** one reproducible task completes with a changed file, an updated test, passing validation, and evidenced criteria, with no false success on the negative controls. W9 exercised `replace_text` for the first time: the model fixed the source with one exact edit and passed Docker validation, but never wrote the test it claimed and left the source criterion unverified. W10 and W11, on the cited-method check and missing-test nudge, both changed the source and the test, passed Docker validation 3/3, completed with evidenced criteria, and produced exported patches that passed on a fresh clone (a single patch in W10; `replace_text` plus a test patch in W11). The narrow task class is reproducible at 2/2 on this 30-line fixture; that is not a benchmark result or evidence for a larger repository. A second task class (W12-W15: change the named prefix and update the existing test) produced correct two-file diffs with passing 3/3 validation in two of three reruns on the stale-report fix (W13, W15); both evidenced all four criteria and their exported patches passed on a fresh clone. The third run (W14) made no tool call at all and failed honestly as an unchanged task. The second class is therefore 2/3, not yet the 2/2 of the null-handling class; W12 remains an unconfirmed `NEEDS_INPUT`. The next slice was the measurable baseline, and it is now underway.

### 2. Establish a measurable baseline

Run the v2 smoke suite and expand it to 15–30 varied Kotlin/JVM maintenance tasks. Include independently checked bug fixes and regressions, no-op and malformed patches, ambiguous requests, and explicit refusal/clarification controls. Keep evaluator oracles outside the workspace the agent can edit. Version the benchmark, system prompt, tool policy, and model; record final validation, changed files, false-successes, patch retries, human acceptance, cost, and elapsed time. **Exit gate:** reproducible runs and no false successes in the known negative controls. The four-case v2 suite was only a first slice of this phase. The first run is 2/4 with no false successes (finding 42); after the bounded `replace_text` hint and the v2.1 contract fix, two `greeting-name` reruns both failed honestly (finding 43) with 0 false successes. v2.2 adds a second fixture domain (`Slugifier`) and a fifth case; its first run exposed a cumulative multi-file patch-repair bug that is now fixed and covered by a worker regression test, and its post-fix rerun failed honestly when the model made no tool call (finding 44). Do not add more feedback text without evidence; keep expanding the fixture and suite with independently checked outcomes.

### 3. Harden completion and worker reliability

The worker already requires a non-empty patch and successful supported validation, but acceptance criteria are still model-authored assessments, not independent verification. A human confirmation can resolve a validated attempt that ended `NEEDS_INPUT`, but it is an explicit reviewer assessment with recorded evidence, not machine proof. Preserve that distinction in API/report wording and never treat arbitrary model evidence as machine proof. Add tests for no-op patches, failed final validation, missing/duplicate criterion results, unsupported validation, and malformed patches. Attempts left running by a crashed worker are now failed after a stale timeout; next add leases/heartbeats so recovery is faster and safe with several workers, plus bounded task retries/backoff, cancellation, and concurrency controls. **Exit gate:** none of those failure cases can reach `COMPLETED`, and recovery cannot execute duplicate work. Keep worker logic separate from REST resources; do not add RabbitMQ until a concrete scaling need justifies it.

### 4. Make execution reviewable and close isolation gaps

Tool calls are durably audited before execution and finalized afterward; task time/cost guardrails are checked at operation boundaries and do not preemptively cancel an in-flight operation. The Compose worker/control-plane split removes Docker-daemon access from orchestration, while Maven tests run in a separate resource-limited container. It is a process/container isolation layer, not a complete hostile-code security boundary: the worker has database credentials and outbound network access, and the control plane retains Docker-daemon privileges. A stricter deployment still needs least-privilege database roles, network egress controls, and operational tests on the target Docker host. **Exit gate:** a reviewer can reconstruct attempts, budget behavior is explicit, and the execution boundary and limitations are documented.

### 5. Add GitHub issue intake and harden PR publishing

Opt-in draft-PR publishing now works for manually submitted tasks against allow-listed public GitHub repositories. Next add issue intake: start with one configured repository and manually supplied issue URL or an allow-listed label, then add signed webhook verification, idempotent delivery, and short-lived GitHub App credentials. Add durable publication state/retry handling for cases where a branch push succeeds but PR creation fails. Include the diff, validation evidence, and limitations in the draft PR. Keep merge approval human-controlled. **Exit gate:** duplicate events cannot create duplicate work/PRs, credentials are scoped and short-lived, partial publication can be recovered safely, and no path auto-merges.

### 6. Demo and polish

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
- **Which model/provider?** Local Ollama remains the default, and a hosted OpenAI-compatible adapter is available behind the same provider interface, selected with `AGENT_MODEL_PROVIDER`. Select the local model after checking available RAM/GPU and test tool-call quality on a small benchmark; use a hosted model only with an explicit usage budget and the per-million-token rates configured.
- **How should builds access dependencies?** A fully offline sandbox is safer, but many builds need dependency downloads. Decide on a controlled artifact cache/proxy or an explicit network allowlist before enabling execution.
- **How should GitHub authenticate?** The current opt-in publisher uses a fine-grained token restricted to an allow-listed repository. Prefer a GitHub App with installation-scoped permissions and short-lived credentials before offering webhook intake or a hosted service.
- **Which language is in scope?** Start with one Kotlin/JVM repository and its existing build/test commands. Add language support only when the first workflow is measurable.
- **What counts as success?** Define benchmark task selection, acceptance checks, regression checks, human review outcomes, and how cost/latency are calculated before publishing scores.

These choices can be discussed while implementing the worker; they do not need to block the attempt/report foundation.

## Status and handoff for the next coding session

- The model provider is runtime-selectable: local Ollama (default) or any OpenAI-compatible endpoint, both behind one SPI with mock-HTTP coverage. Run the versioned benchmark before making model-quality claims.
- The scheduled worker, fail-closed local repository-root configuration, tool allowlist, disposable repository workspace, Docker Maven/PostgreSQL validator, acceptance checks, structured attempt reports, server-side patch export with truncation refusal, answer-and-requeue flow, human confirmation for validated `NEEDS_INPUT` attempts, local review page, durable per-tool audit events, cumulative task time/cost guardrails, bounded reconciliation of attempts orphaned by a crashed worker, container-separated worker/control-plane deployment, opt-in allowlisted GitHub draft-PR publishing for public repositories, and benchmark runner are implemented. Benchmark v2.2 adds a no-change clarification control, an explicit unit-test criterion for `greeting-name`, a second fixture domain (`Slugifier`), stricter scorecard checks, and false-success reporting, but remains a five-case smoke suite. GitHub issue intake, private-repository clone authentication, strict egress controls, least-privilege worker database roles, and durable recovery from partial publication are not implemented. The Compose topology still needs execution and containment testing on the deployment host.
- Run `mvnw verify` after loading `DB_PASSWORD` from `.env` into the process environment. It runs tests and enforces a 95% line / 85% branch coverage gate for measured application code (see `pom.xml` for why a literal 100% is not required); the HTML report is generated under `target/site/jacoco`.
- Local development expects PostgreSQL 18 with databases `coding_agent_platform` and `coding_agent_platform_test`, a limited login `coding_agent_app`, and the app role password in the ignored project `.env` (the app never uses administrator credentials).

## Technology

- Kotlin 2.4.10
- Quarkus 3.40.1
- REST with Jackson
- Model providers: local Ollama (`/api/chat`) or any OpenAI-compatible endpoint (`/chat/completions`), selected at runtime
- Maven wrapper (Maven 3.9.16)
- Java bytecode target 21
