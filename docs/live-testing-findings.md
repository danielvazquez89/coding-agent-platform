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

### 9. The models could not find the change site

- **Symptom.** Runs E and F burned the whole tool budget exploring a few-hundred-file repository and never
  proposed a patch. `writeValue` — the method the task is about — sits around line 3169 of
  `JSONObject.java`, a file larger than the 50 KB whole-file read limit, and nothing told the model that
  line.
- **Root cause.** The repository map ranks files and names their types, but not the line of a method
  declaration, and the system prompt told the model to start with `list_files` over the whole tree and
  then read files — an expensive way to locate a symbol in a real repository.
- **Decision.** Add the read-only `find_symbol` tool: a bounded line scan that returns `path:line` for
  declarations before other occurrences (source before tests), classified as type/function/method/field
  with everything else marked a reference, so the model can read only the surrounding lines. Change the
  system prompt to locate first (`find_symbol`/`search_code`), read only that range, then patch. This is a
  heuristic, labelled as such in the tool description, and it stays inside the existing allow-list, path
  checks and output caps.
- **Status.** Shipped. A further step — an explicit, auditable plan before the first patch — remains open.

### 10. The first patch was written from a declaration line, not from the file

- **Symptom.** With `find_symbol` in place (run G) the model went straight to the right method and
  proposed its first `apply_patch` — but the patch was rejected: the hunk's context had lost the file's
  leading whitespace, and the model had never called `read_file`. It then explored instead of correcting
  the hunk, and the run ended on Groq's **daily** token cap (TPD 200000), which pacing cannot help with.
- **Root cause.** `find_symbol` returns a trimmed declaration line as a hint, and nothing in the flow made
  "read the exact lines" a step the model actually took before writing the patch.
- **Decision.** Three small changes, no relaxation of the matching rule: `find_symbol` ends with an
  explicit next step and accepts a declaration phrase by searching its last identifier token (a model
  passed `static final Writer writeValue` three times and each failed on a format rule); the system prompt
  says to read a line with `read_file` before changing it; and a hunk that cannot be placed now returns
  the actual indented lines at the nearest match, so the model can fix the indentation in one step. The
  patch still fails and nothing is applied.
- **Status.** Shipped and unit-tested. Re-running the real task needs the daily quota to reset.

### 11. `find_symbol` rejected the single-file scope a model actually uses

- **Symptom.** In run H the model's very first call — and the best one it made — was
  `find_symbol(name=writeValue, path=src/main/java/org/json/JSONObject.java)`: it had already narrowed to
  the file and asked "is it declared here?". The call **failed**, because the new tool required a
  directory, while later calls that scanned a directory succeeded. Run I then showed the same instinct
  pointed at the real search: the model passed a **file** to `search_code` six times in one run, every one
  rejected, while its file-scoped `find_symbol` calls succeeded.
- **Root cause.** Both tools treated `path` as a directory. `search_code` has a `glob` for narrowing to
  files, but a model treating `path` as "where to look" consistently passes the file it suspects.
- **Decision.** Both `search_code` and `find_symbol` accept a file **or** a directory path through one
  shared, bounded `scanTargets` helper: a file scans exactly that file, a directory walks the tree, and
  anything else fails honestly. This replaces `search_code`'s previous directory-only contract (one
  existing test asserted the old rejection and was updated); the earlier fallback of an improved error
  message was not enough — the model kept repeating the call.
- **Status.** Shipped and unit-tested; the file-scoped `find_symbol` call is confirmed working live in
  run I.

### 12. A local model's malformed tool call arrives as HTTP 500, and the local adapter never retried

- **Symptom.** The first local runs of the real JSON-java task died after one or two tool calls with
  `ModelProviderException: Ollama returned HTTP 500: {"error":"error parsing tool call: raw='{...}', err=...}`.
  Ollama could not decode the model's own tool-call JSON, so it never returned a turn.
- **Evidence.** Task `b6d2afff-bda8-4372-8237-bbca86154a7b` ended on
  `raw='{"ignoreCase":true,...,"query":"MyEnumField","regex":continue}', err=invalid character 'c' looking for beginning of value` —
  the model wrote `continue` where a JSON value belonged. Task
  `54b010f4-fdae-4027-8e08-a84b50d90165` ended the same way after 19 tool calls with a truncated arguments
  object, `raw='{"lineEnd":3200,"lineStart":3180,"', err=unexpected end of JSON input`.
- **Root cause.** The hosted adapter already retries exactly this class of failure (a valid request the
  provider could not match to the model's output — finding 7), but `OllamaCodingModelProvider` had no
  retry at all, so the first bad sample failed the attempt even though a fresh sample usually decodes.
- **Decision.** Retry HTTP 429 and 5xx in the local adapter with bounded exponential backoff and jitter,
  reporting the reason as a low-cardinality tag (`rate_limited`, `tool_call_parse_failed`, `server_error`).
  The wait policy is shared with the hosted adapter through `ProviderRetryBackoff`, so both providers back
  off the same way; each adapter still owns what is retryable. The retry loop was then exercised live
  (runs K and L) and no further provider error ended a run.

### 13. The whole-file read refusal did not stop a local model from repeating it

- **Symptom.** In task `54b010f4-fdae-4027-8e08-a84b50d90165` the model called
  `read_file(path=src/main/java/org/json/JSONObject.java)` **ten times** and got the same refusal each
  time: `File exceeds the 48 KB whole-file read limit; pass startLine and endLine`. It never supplied a
  range; two of its attempts also mis-named the parameters (`lineStart`/`lineEnd`).
- **Root cause.** The refusal said what to do but not how large the file was, so the model had no way to
  choose a range and repeated the call it had already made.
- **Decision.** The refusal now names the file's size in lines: `File '...' has 3684 lines and exceeds the
  48 KB whole-file read limit; call read_file again with startLine and endLine for the region you need`.
  In the next run (K) the model switched to a ranged read on its first attempt and the refusal never
  repeated. That is one observation, not a controlled comparison; the tool contract is unchanged and a
  range is still required for a large file.

### 14. A local model pastes a glob into the `path` argument

- **Symptom.** In run K the model called `search_code(..., path="**/*.java")` twice and got only
  `Repository-relative path is invalid` back. Every scanning tool takes a concrete `path` plus a separate
  `glob`, so the pattern was in the wrong argument and the message did not say so.
- **Decision.** The refusal names the `glob` argument whenever the path looks like a pattern (contains
  `*`, `?` or `[`), on both the invalid-path and the not-a-file-or-directory path, so the same correction
  is offered on every platform. The message stays a refusal: the pattern is not silently reinterpreted as
  a glob, because guessing which argument a model meant would widen the tool contract.

### 15. What a local 20B on a 6 GB GPU actually does with the real issue

- **Setup.** Windows, RTX 2060 (6 GB VRAM, 4.8 GB free), 16 GB RAM, Ollama with `gpt-oss:20b`
  (12.8 GB, mixture-of-experts with 3.6B active parameters). Provider `ollama`, **no** rate limit, a
  `48000`-character tool-history budget, a `1200`-token repository map and a two-hour task limit — every
  platform limit that could be relaxed for a fair local test was relaxed. The external quota that blocks
  the hosted runs does not exist here.
- **Runs K (`bcd59f58-8467-432a-ab66-e32f1bfe98db`) and L (`878bdb97-56e0-43fe-b8d6-679612405de6`).**
  K: 25 tool calls (13 `search_code`, 4 `list_files`, 4 `read_file`, 1 `run_validation`, 2 rejected
  `search_code`), no patch, ended on the 24-tool-call limit. L: 15 tool calls, no patch, ended
  `NEEDS_INPUT`.
- **What worked.** `run_validation` ran JSON-java's full suite in the isolated Docker container and
  reported `824 tests, 0 failures` (`durationMillis` 97717) — the validation path is real, local and
  quota-free. The model localized nothing (it never called `find_symbol`, despite the system prompt), read
  1,488 characters of a 3,684-line file, and then searched the same ground repeatedly: nine `search_code`
  calls returned 17 characters (no matches).
- **What the platform refused to fake.** Run L ended with the model answering the report request with
  prose — *"I'm sorry, but I can't produce the requested report without knowing the acceptance criteria"* —
  instead of the JSON report, even though the criteria are in the task prompt. The attempt is recorded
  `NEEDS_INPUT` with all three criteria `UNVERIFIED`, `changedFiles=[]` and `patchDiff=null`; the narrative
  is stored under "Model-provided narrative (not independently verified)". The bounded report re-ask
  (finding 3) did its job; it did not invent a summary.
- **Conclusion so far.** At this size the model is a fluent tool-caller that cannot yet drive a
  3,684-line file to a correct patch: it does not follow the locate-first instruction reliably and does
  not converge inside a 24-call budget. The local constraint is capability, not throughput — generation
  is fast enough (run L: 20 model calls, 51,497 prompt tokens and 2,039 completion tokens in 324 s on a
  6 GB card) and there is no quota. The follow-up local experiment — a code-specialized 14B,
  `qwen2.5-coder:14b` (finding 16) — separated the model family from the size class.

### 16. The local limit is the model's family, not its size

- **Setup.** Same machine and card as finding 15, same platform and prompt, same two-hour limit and
  `48000`-character history budget. Only the model changes: `qwen2.5-coder:14b` (a 9 GB,
  code-specialized dense 14B) instead of the general-purpose `gpt-oss:20b` mixture-of-experts. At
  Ollama's default 4,096-token context it does not fit the 6 GB card, so it runs **57% CPU / 43% GPU**
  (`ollama ps`) — a smaller parameter count than the MoE, yet slower per call because most layers are
  on the host.
- **Run M (`5affe250-67a8-4407-9572-b9f8cfe1d3b6`, attempt `00c4401b-84e6-4ecb-a62b-ebe7bc35d1bf`).**
  Six tool calls and the model produced the change the issue asks for:
  1. `find_symbol(name=writeValue, path=src/main/java/org/json/JSONObject.java)` — the file-scoped
     call a model instinctively makes (finding 11), on its **first** turn;
  2. `read_file` (626 characters), 3. `find_symbol` again, 4. `read_file` (1,138 characters) — it read
     only the region it needed, never the 3,684-line whole file;
  5. `apply_patch`, 636 characters, applied cleanly;
  6. `run_validation` — JSON-java's full suite in the isolated Docker container.
- **The patch is the intended production change**, not an approximation:
  `writer.write(quote(((Enum<?>)value).name()));` → `...toString()));` in `writeValue`
  (`@@ -3183,7 +3183,7 @@`).
- **Validation, reported honestly.** `824 tests, 4 failures, 6 skipped` (~79 s): every failure is in
  `EnumTest` (`enumToString`, `jsonObjectFromEnum`, `verifyEnumConsistency`, `wrap`) — the assertions
  that still encode `name()`, and that the task required be updated. The model changed the production
  code but not the test file, so it did not converge on a green suite and did not claim to.
- **Outcome.** `NEEDS_INPUT` (`Acceptance criteria could not be verified`), all three criteria
  `UNVERIFIED`, `changedFiles=[src/main/java/org/json/JSONObject.java]`, the stored diff intact
  (`patchDiffTruncated=false`) and therefore **refused for export** because no criterion passed. After
  validation returned, both remaining model turns proposed no tool call — the model reported rather
  than iterating on the failure.
- **Cost of being local.** 8 model calls, 19,456 prompt / 1,180 completion tokens, 503.6 s in model
  generation, 596.4 s attempt wall clock. No quota, no rate limit, no provider error, no retry, no
  history truncation.
- **Conclusion.** The gap in runs J–L was the model **family**, not the size class. On the same card
  and the same platform, a code-specialized 14B follows the locate-first prompt, scopes `find_symbol`
  to the file, and reaches the correct patch where a general-purpose 20B MoE never did — in six tool
  calls against fifteen to twenty-five. The local model's remaining gap is task-level completeness
  (updating the test a correct source change breaks), not localization. The safe default is unchanged:
  the unverified change is stored and refused, never reported as complete.

### 17. A refreshed free-tier quota funds three real attempts, not one completion

- **Context.** The daily quota reset, so the real JSON-java task was retried on Groq with
  `openai/gpt-oss-120b` (the largest model in the catalog) and then `qwen/qwen3.8-27b` (a
  code-specialized model with its own 200,000-token daily bucket). Pacing was set to 8,000 and 7,000
  tokens per minute respectively. The task text now also says explicitly to update the `EnumTest`
  expectations and add a regression test.
- **Run N (`48e3992a-7fa3-46a3-a133-05dcbe565bf4`, attempt `ca642ad7`), `gpt-oss-120b`, 24-call cap.**
  25 calls, **FAILED** on `The agent exceeded its limit of 24 tool calls`. It produced the correct
  production patch (`name()` → `toString()`, plus a nine-line explanatory comment, 988 characters)
  but never touched `EnumTest`; its last executed call was a search inside `EnumTest` for the
  assertion site. 118,270 prompt / 4,608 completion tokens, 21 minutes, no validation.
- **Run O (`850ab03f-a142-4aaa-858f-c3d4ce599ce7`, attempt `0ffc04d7`), `gpt-oss-120b`, 36-call cap.**
  24 calls, **FAILED** on HTTP 429 *daily* tokens: `TPD Limit 200000, Used 194875, Requested 5910`
  after five 60-second retries. Correct production patch again (817 characters, seven-line comment),
  no test update, no validation. 25 minutes.
- **Run P (`7cdfcd1d-c418-47b1-98d3-7ff1f5e71e1b`, attempt `da395c24`), `qwen/qwen3.8-27b`, 35 calls.**
  **FAILED** on `TPD Limit 200000, Used 193374, Requested 6675`. Its first patch (444 characters) was
  also the right change, written through a real overload: `quote(value.toString(), writer)` is
  `quote(String, Writer)` at `JSONObject.java:2579`, not a hallucinated API. A second, 2,335-character
  patch failed honestly — `Codex patch hunk ... Expected 8 line(s) starting with: if (value == this) {`
  — because the model targeted the wrong site, and it never reached `EnumTest` or validation.
  38 minutes.
- **The shared cause is the free tier, not the loop.** One realistic attempt costs 118,000–125,000
  prompt tokens, and the daily bucket is 200,000 *per model*, so two attempts exhaust one. All three
  runs also show the working-set squeeze: the `16000`-character history budget dropped 2–27 older
  exchanges on nearly every turn, and `EnumTest` was re-read three, two and five times respectively.
  Raising the history further would exceed the 8,000-token-per-minute request cap (finding 5), so the
  free tier is caught between the two limits.
- **What held.** Every run failed honestly with all criteria `UNVERIFIED`, stored the unvalidated diff
  without exporting it, and surfaced the provider error verbatim. The bounded retry recovered
  transient 400/429 responses (waits up to 60 s) but correctly did not pretend a daily cap could be
  waited out. No run reported a failed or unrun validation as a pass.
- **Conclusion.** On Groq's free tier this multi-file task cannot complete: three models, two fresh
  daily buckets and 388,249 tokens produced three correct source localizations and zero completions.
  A funded key (a higher tier lifts TPM/TPD) or a different provider is required to carry this task
  through the test update and validation. That is an external ceiling, recorded rather than smoothed
  over; the loop did the right thing at every failure point.

### 18. A head-only output cap can cut the line that says whether the suite passed

- **Context.** Run M's stored validation details are exactly 12,000 characters and end mid-URL
  (`...confluence/dis`). The `Tests run: 824, Failures: 4` summary sits at character 10,924 of that
  capture, so it survived by about 1,100 characters. A slightly longer Maven log would have left the
  model with the per-test failures but not the suite verdict, and the stored report would have lost it
  too.
- **Cause.** `DockerValidationRunner` asked the command executor for 12,000 characters and then kept
  the first 12,000. Maven prints the verdict last, so the cap and the useful line were on opposite ends.
- **Fix.** The runner captures up to 48,000 characters and trims the display copy to 12,000 by keeping
  the start and the end with an explicit omission marker. The tool result does the same around its
  header, so a control-plane runner that returns more than the limit cannot hide the verdict either.
  The shared command executor stays head-only on purpose: `patchDiff` detects truncation by asking for
  one character more than it keeps, and a head-and-tail result would make a cut diff look short enough
  to export.
- **Test.** An oversized Maven capture keeps `Tests run: 824, Failures: 4` and `BUILD FAILURE` while
  staying within the limit, and a validation result whose details plus header exceed the tool limit
  keeps its end.

### 19. The 14B anchors on the method start, and a patch refusal needs to show the file

- **Setup.** Same task, model and local settings as run M (finding 16), but the jar now carries the
  test-update prompt (`ac9d1a5`), the named history drop note (`d6b96e4`) and the live `REJECTED`
  audit (`0e82772`). It does not yet carry the start-and-end validation trim (`21cde1a`) or the
  patch-context hint (`536d139`).
- **Run Q (`5711e308-419e-4dbe-b91a-f6830613f9d5`, attempt `73849308`).** Ten tool calls: file-scoped
  `find_symbol` for `writeValue`, then ranged reads of **3168-3175 only**, a file-scoped `search_code`,
  and two `apply_patch` attempts at lines **3171** and **3173**. The enum branch is at 3185. Both
  patches were rejected (`error: patch failed: ...:3171`, then `:3173`); the audit recorded `REJECTED`
  with the refusal text, as designed in `0e82772`. The model then ran validation on the unchanged tree,
  which passed the 824-test suite in 66 s.
- **The honest result of a baseline pass.** `NEEDS_INPUT`, `changedFiles=[]`, `patchDiff=null`, all
  three criteria `UNVERIFIED`. A green suite on an unmodified workspace is not evidence for the task,
  and the platform did not treat it as such; the report also says "Patch recovery: 2 patch attempt(s)
  failed before the final outcome".
- **Cost.** 15 model calls, 50,623 prompt / 2,094 completion tokens, 789 s generating, 858 s wall
  clock. The last five model turns proposed no tool call; the model had given up after the refusals.
- **What this run argues for.** The model localizes to the method but reads a four-line window and
  patches the method's opening instead of the branch. `git apply` reports only a path and a line
  number, so the refusal gave it nothing to correct against and it guessed again two lines later.
  Run Q therefore motivated `536d139`: a rejected patch now quotes the numbered lines that are
  actually at the reported location. Whether that is enough for a 14B is the next local experiment;
  the validation trim from `21cde1a` rides along, though this run's 9,586-character output did not
  reach the old 12,000-character cut.

## Where this stands after the real-repository runs

The first genuine third-party task — `stleary/JSON-java` issue #838, change `JSONObject.writeValue`
to use an enum's `toString()` and update `EnumTest` — has **not** completed on Groq's free tier. This is
recorded rather than glossed over, because the failures are informative.

| Run | Model | Outcome |
| --- | --- | --- |
| A `83ed833d` | `openai/gpt-oss-120b` | HTTP 413 after 13 tool calls: the request itself outgrew the tier's per-minute cap (the finding that produced `boundedHistory`) |
| B `0d6ca02b` | `openai/gpt-oss-120b` | HTTP 400 `output_parse_failed` treated as terminal (the finding that produced the generation-error retry) |
| C `7d1505a3` | `openai/gpt-oss-120b` | 25 tool calls, no patch: the too-small window made the model re-read files (the finding that sized the budget) |
| D `a3c7b4ef` | `openai/gpt-oss-120b` | 8 model calls, repeated `output_parse_failed` and a 429, no patch — Groq's decoder kept rejecting this model's output |
| E `e3be1cd1` | `qwen/qwen3.8-27b` | read `JSONObject.java`, proposed `apply_patch` with an unsupported `*** Find File:` directive (rejected honestly, as designed), then HTTP 413: `7250 > 7000` input tokens per minute |
| F `3766f0a7` | `qwen/qwen3.8-27b`, 12000-char window | 16 model calls, 25 tool calls (11 `search_code`, 7 `read_file`, 5 `list_files`, 2 `related_tests`), no patch, tool-call limit reached |
| G `1941c804` | `qwen/qwen3.8-27b`, after `find_symbol` | 18 tool calls (13 `find_symbol`, 4 `search_code`, 1 `apply_patch`): located the method and proposed its **first** patch, which was rejected because the context lost the file's indentation and the model had not read the file; the run then ended on Groq's daily token cap (TPD 200000) |
| H `235379b4` | `openai/gpt-oss-120b`, after `find_symbol` | 22 tool calls (7 `search_code`, 6 `list_files`, 4 `read_file`, 2 `find_symbol`), no patch: its first, best call scoped `find_symbol` to the suspect file and was rejected (finding 11); the 12000-character window then dropped up to 16 tool exchanges at a time and the model re-explored until the **daily** token cap (197282/200000) ended the run |
| I `7c883e11` | `openai/gpt-oss-20b`, after finding 11 | 17 tool calls (8 `search_code`, 6 `read_file`, 2 `find_symbol`), no patch: file-scoped `find_symbol` succeeded, but six `search_code` calls passed a file and were rejected (finding 11), then the run ended on a per-minute cap (TPM 7104+3570 > 8000) |
| J `b6d2afff` | local `gpt-oss:20b` (Ollama) | 2 tool calls, no patch: Ollama answered HTTP 500 `error parsing tool call` because the model wrote `"regex":continue` (finding 12) |
| J2 `54b010f4` | local `gpt-oss:20b` (Ollama) | 19 tool calls, no patch: 10 identical whole-file read refusals, then HTTP 500 on a truncated tool-call arguments object (findings 12 and 13) |
| K `bcd59f58` | local `gpt-oss:20b`, after the retry | 25 tool calls, no patch: the retry removed the fatal provider error, the first whole-file refusal produced a ranged read, two `search_code` calls pasted a glob into `path` (finding 14), then the 24-tool-call limit ended the run (finding 15) |
| L `878bdb97` | local `gpt-oss:20b`, after the glob hint | 15 tool calls, no patch, `NEEDS_INPUT`: `run_validation` passed JSON-java's full 824-test suite in Docker, the model never called `find_symbol`, then answered the report request with prose asking for the acceptance criteria instead of the JSON report (finding 15) |
| M `5affe250` | local `qwen2.5-coder:14b` (Ollama) | 6 tool calls, `NEEDS_INPUT`: file-scoped `find_symbol` on the first turn, then the **correct** production change (`name()` → `toString()`, 636-char patch), then Docker validation reported `824 tests, 4 failures` in `EnumTest` honestly; the model changed the source but not the tests it breaks, so no criterion passed and the diff is refused for export (finding 16) |
| N `48e3992a` | `openai/gpt-oss-120b` (Groq, refreshed quota) | 25 calls, `FAILED` on the 24-call cap: correct production patch (988 characters) but the `EnumTest` update was still in progress, and validation never ran; 118,270 prompt / 4,608 completion tokens (finding 17) |
| O `850ab03f` | `openai/gpt-oss-120b`, 36-call cap | 24 calls, `FAILED` on the **daily** token cap (`194875/200000`): correct production patch, no test update, no validation (finding 17) |
| P `7cdfcd1d` | `qwen/qwen3.8-27b`, 36-call cap | 35 calls, `FAILED` on its own daily cap (`193374/200000`): correct production patch through the real `quote(String, Writer)` overload, a second hunk rejected honestly, no test update or validation (finding 17) |
| Q `5711e308` | local `qwen2.5-coder:14b`, after the test-update prompt | 10 tool calls, `NEEDS_INPUT`: read only lines 3168-3175 around `writeValue`, then guessed patches at 3171 and 3173 (the enum branch is at 3185); both were rejected and recorded `REJECTED` with the actionable message. It ran validation on the unchanged tree (baseline PASS, 66 s), reported no change, and all criteria stayed `UNVERIFIED`; 15 model calls, 50,623 prompt tokens, 858 s (finding 19) |

What the runs do show: the safety model held in every case (no bad patch was applied, no failed or
unavailable check was reported as a pass, every provider error was surfaced verbatim), the once-fatal
HTTP 413 and re-read loop were fixed and did not recur, and the generation-error retry visibly recovered
attempts that previously died. Run G shows the localization change moving the needle in the intended
direction: the model that previously explored without ever patching now reaches the method and proposes a
change. Runs H and I show the tool-ergonomics bug that first call exposed: a model scopes `search_code`
and `find_symbol` to the single file it suspects, and a directory-only `path` rejected that (finding 11).
The remaining gap on the free tier is exactness — the patch must reproduce the file's indentation — and
the tier's hard caps: every Groq free-tier bucket is 200,000 tokens per day and as little as 8,000 tokens
per minute, which a half-hour run of real tool results exhausts, so pacing cannot recover it and a run
cannot be repeated until the quota resets. The read-before-patch guidance, the tolerant `find_symbol`
argument, the file-scoped `path` for both search tools and the actionable hunk diagnostic (findings 10 and
11) target the correctness gap; the quota is an external limit worth stating plainly. An explicit,
auditable plan step remains open. Hosted OpenAI API models (`gpt-4o`, `gpt-4.1`, the o-series) remain
untested here: no key is available.

Runs J–L moved the experiment off the hosted quota entirely. A local `gpt-oss:20b` on a 6 GB card removes
the external limit — no per-minute or per-day cap, a two-hour wall clock — and the two fixes those runs
produced (the local retry for a malformed tool call, and refusals that carry the line count and the
`glob` argument) both came from a live failure rather than a guess. What remains for a local run is the
model. `gpt-oss:20b` can read, search and run a real 824-test suite through the platform, but it does not
reliably follow the locate-first instruction, so it never reaches a patch (finding 15). Run M separates
the two variables that were still confounded: swapping **only** the model — same card, same platform,
same prompt — for a code-specialized 14B, `qwen2.5-coder:14b`, produced the correct one-line production
change and an honest 824-test result in a single six-call run (finding 16). The local limit is the
model's family more than its size class, and in every local run the loop held: it refused to report a
failing or unverified result as a pass.

Runs N–P returned to the hosted path once the daily quota reset. The refreshed quota funded three real
attempts — two on `gpt-oss-120b`, one on `qwen/qwen3.8-27b` — and all three produced the correct
source localization, but none reached the test update or validation: the first ended on the 24-call
cap and the other two on their models' 200,000-token daily caps (finding 17). Both strong model buckets
were exhausted by roughly one and a half attempts each. On the free tier the remaining gap is therefore
neither localization (three different models found `writeValue`) nor the safety loop (every run failed
honestly with unverified criteria), but the quota needed to carry a multi-file change through the test
update and a real Maven validation. A funded key or another provider is the next experiment worth
running; without one, the honest statement is that the loop is ready and the free tier is not.

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
