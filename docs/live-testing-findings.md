# Live-testing findings

An honest log of what running this agent against real models and real repositories actually exposed, and
the change each finding produced. Unit tests use a scripted model, so they cannot prove the loop behaves
against a provider whose prompts, tool-call markup, rate limits and request caps are real; this file is
the evidence trail for the capability claims in [README.md](../README.md).

Each entry names its evidence (task id, model, observed response) so the claim can be re-checked. This is
a committed document; the day-to-day roadmap and open questions live in the uncommitted `NEXT-STEPS.md`.

## How a live run is set up

- Model choice lives in `.env` (gitignored; the API key is only ever there). The packaged jar does **not**
  load `.env`, so every setting is exported into the process environment before `java -jar`.
- A run needs `AGENT_ALLOWED_LOCAL_ROOT` (or a public GitHub URL), the provider base URL/model/key, and
  `DB_PASSWORD`.
- A candidate repository is only worth testing if it is green on its own terms: clone it, run
  `mvn -B test` once to warm `~/.m2/repository`, delete the generated `target/` (root-owned files break
  the validation container's uid), then confirm `mvn -B -o test` passes inside the hardened container.
  Without that gate, a red validation could be the repository's fault rather than the agent's.

## Findings

### 1. `gpt-oss` emits a Codex envelope, not a git diff

- **Symptom.** The model proposed the correct change but `apply_patch` rejected the payload: `gpt-oss`
  marks changes with `*** Begin Patch` / `*** Update File:` instead of `--- a/... +++ b/...`.
- **Evidence.** Task `14123290-0f36-4f59-ab7e-ce91e44b9e72` (`openai/gpt-oss-120b` via Groq) applied the
  same change as two envelopes (603 and 336 characters), both normalized and applied with zero patch
  failures, then passed Docker validation.
- **Decision.** Normalize the envelope to a unified diff (`CodexPatchNormalizer`) _before_ the existing
  path, size and file-count checks, so the safety checks still see exactly what will run. Placement stays
  strict: every hunk must match the file on disk, a hunk that cannot be placed fails honestly, and
  `*** Move to:` is rejected. Normalization is logged at INFO.

### 2. A config key glued to a comment silently disabled local runs

- **Symptom.** Every task against a local repository behaved as if the feature were switched off.
- **Root cause.** `agent.execution.allowed-local-root=...` had been appended to the end of a comment line.
  `application.properties` is line-based, so the key was inert and the adapter's `defaultValue = "disabled"`
  quietly won.
- **Decision.** Put the key on its own line; add `ConfigurationCoverageTest` so an injected key with no
  entry in the file fails the build; add the two Docker readiness keys. The test cannot see a differently
  named key sharing a comment line, so "config keys start their own line" is now also a written rule in
  `AGENTS.md`.

### 3. A final report that is not parseable JSON

- **Symptom.** A run that had done real work ended with prose instead of the report object, losing the
  outcome.
- **Decision.** Re-ask with a bounded retry (max 2) when the final message does not parse as the expected
  JSON. Never repair or infer the JSON, and if it still fails, fail honestly rather than invent a summary.

### 4. Rate limiting on a hosted free tier

- **Symptom.** Groq's free tier returned HTTP 429 mid-task.
- **Evidence.** Four throttled calls in one run were absorbed by 23-32 second waits; the task still
  completed. On a later run the budget warned that an estimated 9524 tokens exceeded the 8000-token
  allowance and sent the request anyway.
- **Decision.** A shared sliding-minute budget (`ModelRateLimiter` → `RateLimitBudget`), consulted only by
  the OpenAI-compatible adapter. Reservations precede the call, a 429 retry keeps its reservation because
  the provider counted that attempt, and the wait is bounded (`AGENT_RATE_LIMIT_MAX_WAIT_MILLIS`): after
  that the request is sent anyway, because the retry loop — not the budget — is the correctness boundary.
  A local Ollama server has no quota, so it does not consult the budget.

### 5. A long run outgrew the provider's per-request cap (HTTP 413)

- **Symptom.** A real task against a third-party Maven repository failed after 13 tool calls / 7.5 minutes
  with `HTTP 413 ... Limit 8000, Requested 8205`. Pacing did not help: the **single request** was already
  over the tier's per-minute token limit.
- **Evidence.** Task `83ed833d-76d7-445e-a6f1-65a21c3cf502`: the budget warned "sending anyway", the
  provider retried twice on 429, then the provider answered 413, which is surfaced as terminal.
- **Root cause.** The tool loop appended every tool result and never bounded the conversation. Tool output
  from a real repository (whole files, search results) is large, so the request grew monotonically until
  no request could be valid.
- **Decision.** `TaskExecutionWorker.boundedHistory` keeps a request's tool history within
  `AGENT_MAX_HISTORY_CHARACTERS` (default `24000`; `disabled` keeps everything). It never drops the system
  prompt or the task prompt (which carries the repository map) and does not count them against the budget,
  replaces the dropped exchanges with one note that a re-read is allowed, and never separates a `tool`
  message from the assistant call it answers (an orphaned `tool` message is invalid for OpenAI-compatible
  APIs). Truncation is logged, not hidden.

### 6. The first window size made the model loop instead of fixing the bug

- **Symptom.** With the window at `16000` characters counted over the whole request, a run of the same
  JSON-java task read `JSONObject.java` and `EnumTest.java`, lost whichever it had read first, read it
  again, and repeated that until it died on the 24-tool-call limit — no patch, no validation.
- **Evidence.** Task `7d1505a3-1cd3-4c1b-a74f-7d0e8b98054f`: 25 tool calls, all `list_files`/`read_file`/
  `search_code`, with the log showing "dropped 9…20 older tool exchange(s)" on almost every turn and
  `changedFiles=[]`. The two files total roughly 19,000 characters, so a whole-request budget of 16,000
  could never hold both at once, and the repository map shrank the usable window further.
- **Decision.** Count only the droppable tool history against the budget; the fixed prompts are bounded
  separately and never eat the working set. Raise the default to `24000` so a realistic two-file working
  set fits, while prompt plus history stays under Groq's 8000-token per-minute cap. The drop note now asks
  the model not to re-read a file it has already seen and to read only the lines it needs.

### 7. A valid request rejected because the provider could not parse the model's output

- **Symptom.** A fresh run of the JSON-java task against `openai/gpt-oss-120b` failed with
  `HTTP 400 ... code: "output_parse_failed", "failed_generation": "We need to find writeValue method."`
  after eight model calls. The request was well-formed; the model emitted a plain-text fragment where
  Groq's constrained tool-call decoder required its own format.
- **Evidence.** Task `0d6ca02b-8efd-4a31-968b-4661ccafecc1`, attempt
  `a91b0292-bc59-4787-bbb5-9ce7d94aba08`: accepted as terminal because the adapter classified all
  `400`s as non-transient, wasting the attempt.
- **Decision.** Retry a `400` only when the provider's error code marks a generation/decoding failure
  (`output_parse_failed`, `tool_use_failed`), using the existing bounded backoff, and report the
  provider error honestly if the retries are exhausted. A malformed request carries neither code and
  still fails immediately. The retry keeps its own metrics reason (`provider_generation_error`).

### 8. Observability

- Micrometer meters for model turns, retries, rate-limit waits, tool calls and task outcomes are exposed at
  `/q/metrics` through the framework-free `ExecutionMetrics` port, and were observed on a live run. Tags
  stay low-cardinality: no task ids, paths, tool output or prompt text.

## Repository selection (the gate, in practice)

Every candidate is green-from-a-fresh-clone or it is disqualified, because a red validation would otherwise
be ambiguous between the agent and the repository.

| Repository | Verdict | Why |
| --- | --- | --- |
| `stleary/JSON-java` | selected | `mvn -B -o test` green (824 tests, ~65 s), cache warm, no `target/` |
| `apache/commons-text` | rejected | offline run hits DNS/URL-dependent tests |
| `apache/commons-csv` | rejected | three baseline test failures before the agent touches anything |
| `google/gson` | rejected | JPMS module needs a prior `mvn install` to resolve offline |
| `junit-team/junit4` | rejected | uncached `animal-sniffer` plugin plus a flaky temp-folder test |

## What a "real" claim has to show

A capability claim is only supported when the run records the model and provider, the task id, the
observed outcome, and the evidence for it (patch length, `git apply --check`, validation result). Both live
models so far — `qwen/qwen3.8-27b` and `openai/gpt-oss-120b` — completed the same small fixture task and
passed Docker validation; the same small-fixture caveat is recorded next to the claim in the README, so
neither is presented as proof for a large third-party repository.

The hosted OpenAI API models (`gpt-4o`, `gpt-4.1`, the o-series) remain untested here: no API key is
available, so the only hosted evidence is open-weight models served by Groq.
