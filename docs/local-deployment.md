# Running it locally: intended functionality against the specifications, and where the limit is

This is an **evaluation**, not a changelog. The chronological evidence (task ids, raw provider
responses, per-run outcomes) lives in [`live-testing-findings.md`](live-testing-findings.md) and
[`docs/architecture-decisions.md`](architecture-decisions.md). This document answers one question:

> When the whole platform runs locally — a model on the operator's own machine through Ollama, no
> hosted provider, no API key, no quota — does it do what it is *specified* to do, and where does it
> stop?

Everything below comes from runs on one Windows machine (RTX 2060, **6 GB VRAM**, 16 GB RAM) against
the real `stleary/JSON-java` task: change `JSONObject.writeValue` to use an enum's `toString()`
instead of `name()`, update `EnumTest`, and keep the full Maven suite green. The repository is a
green-from-a-fresh-clone candidate (824 tests, cache warmed, no `target/`) — the same task used for
every hosted run, so the local results are comparable.

## 1. The specifications, and what they look like locally

The specifications are the invariants in [`AGENTS.md`](../AGENTS.md) and the "Decisions and
boundaries" list in [`README.md`](../README.md). The point of the table is to check each one against
what a *local* run actually did, not against intent.

| Specification | What it means under a local model | What the local runs show |
| --- | --- | --- |
| **Model-agnostic; no hosted key required** | the provider is one SPI behind `agent.model.provider`; a local Ollama model is a first-class choice | every run (J–M) used `AGENT_MODEL_PROVIDER=ollama` and completed with no API key and no external egress |
| **Tool requests are proposals, not commands** | the application authorizes each call; the model can never run a shell or reach the network | every call across all runs was one of the 8 allow-listed tools; a `search_code(path="**/*.java")` in run K was **refused**, not silently reinterpreted (finding 14) |
| **Repository content and task text are untrusted data** | nothing a file or the issue says can change policy | library code and issue text flowed through the loop unchanged; the tool allow-list, path checks and patch-size checks were identical throughout |
| **Never report a failed, unavailable or unparsable check as a pass** | a failing suite stays failing; an unverifiable criterion stays unverified | run M's suite failed (4 `EnumTest` failures) and the attempt is `NEEDS_INPUT` with all three criteria `UNVERIFIED`, **even though the code change is correct** (finding 16) |
| **`COMPLETED` requires a workspace change + evidenced criteria + passing validation** | all three together, or it is not complete | no local run of the real task reached `COMPLETED`, and none of them should have — every one either proposed no patch or failed validation |
| **Bounded execution** | call, time and spend caps a runaway loop actually hits | run K stopped on the 24-tool-call limit; run M stopped because the model chose to report; the two-hour wall clock never had to fire |
| **Isolated validation** | repository code runs in a separate, offline, capability-dropped container with no Docker socket | runs L and M executed JSON-java's full 824-test suite inside `maven:3.9.13-eclipse-temurin-21-noble` with no network |
| **Draft changes only; a person is the gate** | nothing merges or is applied server-side; export is gated | no local run produced an exportable patch (criteria unverified), and none attempted a pull request |
| **Optional config uses the `disabled` sentinel and fails closed** | a feature that is off is off; a half-configured one fails at startup | the local runs deliberately disabled the rate limiter (`AGENT_RATE_LIMIT_TOKENS_PER_MINUTE=disabled`) and set `AGENT_ALLOWED_LOCAL_ROOT` |
| **Everything is auditable** | a durable per-tool audit written before execution, plus an honest report | each run exposes its full trace at `GET /api/tasks/{id}/attempts/{attemptId}/tool-executions` and a full report; the trace and patch below are reproduced from run M |

**Verdict on the specifications:** the policy substrate held under a purely local model. There were
no false successes. If anything, the platform was conservative: run M's production change is exactly
the one the issue asks for, and the platform still refused to call the task done, because the
repository's own tests said otherwise.

## 2. What a successful local step actually looks like (run M)

Run M is the local run that got furthest. Task `5affe250-67a8-4407-9572-b9f8cfe1d3b6`, attempt
`00c4401b-84e6-4ecb-a62b-ebe7bc35d1bf`, model `qwen2.5-coder:14b`. Six tool calls in ~10 minutes:

| # | Tool | Arguments | Outcome |
| --- | --- | --- | --- |
| 1 | `find_symbol` | `name=writeValue, path=src/main/java/org/json/JSONObject.java` | scoped to the suspect **file**, on the first turn, and succeeded |
| 2 | `read_file` | the same file | 626 characters (a ranged slice, not the 3,684-line whole file) |
| 3 | `find_symbol` | the same file | succeeded |
| 4 | `read_file` | the same file | 1,138 characters |
| 5 | `apply_patch` | 636-character diff | applied cleanly |
| 6 | `run_validation` | (no arguments) | `mvn -B -o test` in Docker → `824 tests, 4 failures` |

The patch is the intended production change, in full:

```diff
@@ -3183,7 +3183,7 @@ public class JSONObject {
         } else if (value instanceof Boolean) {
             writer.write(value.toString());
         } else if (value instanceof Enum<?>) {
-            writer.write(quote(((Enum<?>)value).name()));
+            writer.write(quote(((Enum<?>)value).toString()));
         } else if (value instanceof JSONObject) {
```

Validation then told the truth. All four failures are in `EnumTest`
(`enumToString`, `jsonObjectFromEnum`, `verifyEnumConsistency`, `wrap`) — the assertions that still
encode `name()`, and that the task required be updated. The model changed the production code but
**not the test file**, so the suite is genuinely red. The platform recorded:

- task `NEEDS_INPUT`, `errorSummary = "Acceptance criteria could not be verified"`;
- `changedFiles = [src/main/java/org/json/JSONObject.java]`;
- all three acceptance criteria `UNVERIFIED`, with no fabricated evidence;
- the stored diff intact (`patchDiffTruncated = false`) but **refused for export**, because
  `exportablePatch` requires one passing, evidenced acceptance check per criterion.

That is the whole product thesis in one local run: a correct change, a real test result, and an
honest refusal to promote it.

## 3. Where the local limit actually is

### 3.1 The model's family matters more than its size

The decisive local result is a comparison, not a single run. Same machine, same platform, same
prompt, same limits — only the model differs:

| Model | Shape | On disk | Local runs | What it did |
| --- | --- | --- | --- | --- |
| `gpt-oss:20b` | 20B MoE, 3.6B active | 13 GB | J, J2, K, L | never reached a patch: 15–25 tool calls, repeated search/list, never called `find_symbol` reliably; one run ended by answering the report request with prose instead of the JSON report |
| `qwen2.5-coder:14b` | 14B dense, code-specialized | 9.0 GB | M | **correct production patch in 6 tool calls**, then an honest failing-suite result |

A **smaller** model, on the same 6 GB card, reached the patch the larger general-purpose model never
did. At this scale the differentiator is the model *family* (code-specialized weights and instruction
following) rather than the parameter count. The loop did not change between the runs; only the model
did.

### 3.2 The machine is the throughput limit — and the model does not fit

`qwen2.5-coder:14b` is a 9 GB model on a 6 GB card. At Ollama's default context
(`ollama ps`: **4,096 tokens**) it still does not fit, so it ran **57% CPU / 43% GPU**:

```text
NAME                ID              SIZE      PROCESSOR          CONTEXT
qwen2.5-coder:14b   9ec8897f747e    10.0 GB   57%/43% CPU/GPU    4096
```

Consequences, all measured:

- **Time.** Run M spent 503.6 s in model generation across 8 calls (≈63 s each) and 596.4 s of wall
  clock for the attempt. The MoE `gpt-oss:20b` was faster per token (3.6B active) but needed three to
  four times as many calls, so it spent its budget exploring instead.
- **Context.** The Ollama server's own context (4,096 tokens here) is a *separate* limit from the
  application's `AGENT_MAX_HISTORY_CHARACTERS` (48,000 characters ≈ 12,000 tokens on these runs). A
  local operator doing repository-scale work should raise the server's `num_ctx`; the application's
  budget can only be as large as the server will accept. Run R left `OLLAMA_NUM_CTX` unset, loaded
  4,096 tokens, and repeated a rejected patch whose corrected lines it had just read; that result
  cannot be used to judge the prompt because Ollama truncates an over-window conversation. The local
  recipe now sets `OLLAMA_NUM_CTX=8192`; confirm it with `ollama ps` before starting a run.
- **Fit.** Offloading is what makes a 14B "work" at all on 6 GB; it is also why a local run is minutes,
  not seconds. This is a hardware statement, not a platform one.

### 3.3 The task shape is the last gap

Run M shows the remaining local gap precisely: it is **task-level completeness**, not localization.
The model found the right method and made the right source change; it did not take the second step of
updating the test that the change breaks (and, in a fuller version of the task, adding the regression
test). So "a local model drives a multi-file change to a green suite" is **not** yet demonstrated. It
is a model-capability gap — the loop offered every tool it needed (`related_tests`, `read_file`,
`apply_patch` were all available and within budget) and the model stopped one edit short.

### 3.4 What is *not* a local limit

Worth stating plainly, because these are the parts a reader might expect to be the problem:

- **Validation is real, local and quota-free.** `run_validation` ran JSON-java's entire suite in the
  hardened container — `824 tests` in ~79 s (run M) and ~98 s (run L) — with no network and no Docker
  socket. The isolated-validation promise is not a hosted-only feature.
- **There is no rate limit or daily cap locally.** The two hosted blockers from the earlier runs (the
  per-minute token cap that produced HTTP 413, and Groq's 200,000-token daily cap) simply do not
  exist here. The local constraint is capability and speed, never quota.
- **The safety and audit loop is identical.** Allow-list, path checks, patch-size checks, bounded
  history, the pre-execution tool audit, the report re-ask, and the honest terminal states all ran
  against the local model exactly as against a hosted one.

## 4. Reproducing a local run

Prerequisite: Docker Desktop running (for validation), a warm `~/.m2/repository` for the target
project, and the repository green on its own terms before the agent touches it.

```powershell
# provider + model
$env:AGENT_MODEL_PROVIDER              = "ollama"
$env:OLLAMA_MODEL                      = "qwen2.5-coder:14b"
# Required for repository-scale work: Ollama's server default is often 4096 tokens, smaller than the
# system prompt + task + repository map + one whole-file read (run R loaded 4096 and could not be
# trusted as a test of the prompt). Raise it before judging a local model's behavior.
$env:OLLAMA_NUM_CTX                    = "8192"

# local-only relaxations: no quota to pace, a bigger working set, a long wall clock
$env:AGENT_RATE_LIMIT_TOKENS_PER_MINUTE = "disabled"
$env:AGENT_MAX_HISTORY_CHARACTERS       = "48000"
$env:AGENT_REPO_MAP_MAX_TOKENS          = "1200"
$env:AGENT_MAX_TASK_DURATION_SECONDS    = "7200"

# the workspace root the worker may open
$env:AGENT_ALLOWED_LOCAL_ROOT          = "C:\Users\<you>\AppData\Local\Temp\opencode\repos"
$env:DB_PASSWORD                       = (Get-Content .env -Raw | ConvertFrom-StringData).DB_PASSWORD

java -jar target/quarkus-app/quarkus-run.jar
```

Then create the task and watch it:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/tasks" -ContentType application/json -Body (@{
  title = "Use an enum's toString() when serializing a JSONObject"
  description = "In org.json.JSONObject.writeValue, ..."
  acceptanceCriteria = @("...", "...", "The full Maven test suite passes.")
  repositoryUrl = "C:\Users\<you>\AppData\Local\Temp\opencode\repos\JSON-java"
} | ConvertTo-Json) | Select-Object id, status
```

The full report (patch, validation log, acceptance checks, model usage) and the tool audit are at
`GET /api/tasks/{id}/attempts/{attemptId}` and `.../tool-executions`.

> A packaged jar does **not** read `.env`; every setting above must be exported into the process
> environment. The API and review page are unauthenticated — keep them on localhost.

## 5. Open items

- **A second local model to confirm the family effect.** Run M is one model on one task. The claim
  "family beats size at this scale" wants a second code-specialized model (for example a 7B coder) and
  a second general model to rule out a lucky run.
- **`num_ctx`.** Have the local adapter or the documented setup raise Ollama's context so the server
  limit stops being narrower than the application's history budget.
- **The test-update step.** The single missing edit in run M — update the test the change breaks — is
  the next thing the prompt or flow could make explicit, without weakening the completion rule.
- **An explicit, auditable plan step** (target file + symbol + intended change before the first patch)
  remains an open decision from finding 9, unchanged by the local runs.
