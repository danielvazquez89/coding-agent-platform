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

### 20. The patch hint works; the model resends the same bytes, and a 4,096-token window confounds it

- **Setup.** Same task, model and local settings as run Q, with `21cde1a` (start-and-end validation
  trim) and `536d139` (numbered lines at a failed hunk) in the jar. It did **not** carry `ad09433`
  (read the enclosing block), `6fedf1f` (retry) or `70e5f4e` (duplicate-resend guard).
  `OLLAMA_NUM_CTX` was unset, so Ollama loaded the model with its default window.
- **Run R (`f5ad2e79-13f6-4c9b-b870-6b1d84230a95`, attempt `2a108f7c`).** Ten tool calls in
  31 minutes: `find_symbol(writeValue)`, `read_file` 3168-3172, a file-scoped `search_code`, a
  whole-file read of `EnumTest` (11,999 characters), then five `apply_patch` attempts. Call 5
  (1,582 characters) was rejected at **`EnumTest.java:100`**, and its refusal carried the numbered
  actual lines. Call 6 read exactly **94-104** - the hint worked. Calls 7 and 8 sent the **same
  1,582-character patch** again; calls 9 and 10 sent a 1,643-character variant; all four were
  rejected at the same line.
- **The context confounder.** `ollama ps` reported **4,096 tokens**, and the whole-file `EnumTest`
  result alone is roughly 3,000 tokens before the system prompt, the task and the repository map.
  Ollama truncates a prompt that exceeds the loaded window, so this run cannot separate "the model
  ignores the hint" from "the model never received the hint in full". The next local run must set
  `OLLAMA_NUM_CTX=8192` and confirm the loaded context before using the result to judge the prompt.
- **Bounded stop and honest recovery.** Rather than let the remaining 14 calls repeat the same hunk,
  the operator stopped the app after call 10. A restart with `AGENT_MAX_TASK_DURATION_SECONDS=1`
  (the derived stale timeout is twice that) reaped the orphan: the task and attempt became `FAILED`
  with "The execution worker stopped while attempt 1 was running (started ...); its outcome is
  unknown ... a new task is required to retry the work", a report summary of "no result was
  recorded", `changedFiles=0` and no validation. Nothing was reported as a pass.
- **What this argues for.** `70e5f4e` refuses a byte-identical resend of a patch already rejected in
  the same attempt (SHA-256, per attempt) and tells the model to change the hunk context; `ad09433`
  tells the model to read the enclosing declaration, not four lines around the declaration.
  `docs/local-deployment.md` now sets `OLLAMA_NUM_CTX=8192` in the recipe rather than leaving it
  commented out, because the default window is smaller than the conversation this task produces.

### 21. A larger window does not fix the narrowing; the duplicate guard bounds it

- **Setup.** The jar now carried the read-the-enclosing-block prompt (`ad09433`) and the duplicate
  guard (`70e5f4e`), and `OLLAMA_NUM_CTX=8192` was set and confirmed with `ollama ps`. Same task,
  same local 14B.
- **Run S (`8ec6f803-94ac-4bcd-8d0e-6c1af0ebc693`, attempt `4f6e43e2`).** Nine tool calls in eleven
  minutes: a file-scoped `find_symbol(writeValue)` (486 characters), then a read of **3165-3175**
  (521 characters), then a 630-character patch aimed at **3170** - the declaration line - which git
  rejected. The model then repeated read-3165-3175 / same-patch four times. It never read line 3185,
  where the enum branch is.
- **What the larger window ruled out.** The whole conversation in this run was well under 8,192
  tokens: the largest tool result was a 521-character read and the patch was 630 characters. Ollama
  therefore received the full context, so the narrow read is not a truncation artifact. Run R's
  confound is removed, and the anchoring is the model/prompt problem it looked like.
- **The duplicate guard works live.** Call 3 cost 93 ms in `git apply`; calls 5, 7 and 9 were refused
  in **6 ms** each, before `git`, and audited `REJECTED` with the byte-for-byte message. The model
  received that message and still re-read the same window and resent the same patch, so the guard
  bounds the wasted work but cannot teach a 14B to look further. That is the right division: the
  application refuses an action that cannot succeed, and does not pretend it changed the model's mind.
- **Bounded stop and honest recovery.** Stopped after call 9; reaped `FAILED` with the same
  outcome-unknown report, `changedFiles=0`, no validation. Nothing was reported as a pass.
- **What this argues for.** The prompt alone did not widen the read. `450d2a1` now has `find_symbol`
  report the method's heuristic block end (`JSONObject.writeValue` ends at line 3203), so the next run
  can read 3169-3203 and reach 3185 without guessing. That is the next experiment, not yet run.

### 22. The block-end hint widens the read; a corrupt patch needs its patch line named

- **Setup.** The jar carried `450d2a1` (a method hit in `find_symbol` reports its heuristic block end)
  and the 8,192-token window, but not `46d6a14` (the corrupt-patch hint, committed while this run was
  in flight). Same task, same local 14B.
- **Run T (`37aca9ff-58ec-4cd2-9c97-a7b43e459cb2`, attempt `c3d1f4d2`).** Six tool calls in twenty
  minutes before the operator stopped it:
  1. `find_symbol(writeValue, JSONObject.java)` returned 602 characters (486 without the hint) and
     reported that the block ends at line 3203.
  2. `read_file 3169-3203` (1,761 characters) - the **whole method**, including the enum branch at
     3185. Runs Q, R and S had read only a narrow window around the declaration (3168-3175,
     3168-3172 and 3165-3175) and patched the opening; this is the first change that moved the read.
  3. `find_symbol(jsonObjectFromEnum, EnumTest.java)`, 466 characters.
  4. `read_file EnumTest 38-72`, 1,916 characters - it had moved on to the test helper, as the task
     requires.
  5. A 2,575-character `apply_patch`, its first two-file attempt, was **rejected**: an
     `index 1234567..89abcde 100644` line inside a hunk produced
     `warning: recount: unexpected line: ...` and `error: corrupt patch at <stdin>:34`.
  6. **13.5 minutes later**, the identical 2,575-character patch; the duplicate guard refused it in
     5 ms.
- **The refusal was not actionable.** `<stdin>:34` names a line in git's stdin, not anything the model
  can match to the text it wrote, and the model's next reply repeated the same bytes. `46d6a14`
  (built after this jar) now quotes that patch line, says a hunk line must start with a space, `+` or
  `-`, and notes that a working-tree patch does not need an `index` line. Whether that lets the model
  fix it is the next run's question, not an assumption.
- **Slow local turns are a real cost.** Ollama's log showed about 1,590 generated tokens at
  **2.4 tokens/s** by 21:15; the turn returned at 21:17:54 with the same patch. A 14B on this machine
  can spend 13+ minutes on one reply, and the per-call timeout (1,200 s) is close enough that a longer
  reply would be lost as a timeout rather than delivered. That is a hardware limit the prompt cannot
  remove; the duplicate guard at least keeps the repeat from spending another `git apply`.
- **Bounded stop and honest recovery.** Stopped after call 6; reaped `FAILED` with the outcome-unknown
  report, `changedFiles=0`, no validation. Nothing was reported as a pass.
- **What this run shows.** The block-end hint is validated: it changed the read from a ten-line window
  to the whole block and let the model reach the test. The next bottleneck is a malformed patch whose
  refusal was opaque; `46d6a14` is the direct fix to test.

### 23. The block-end hint produces the right patch; the model stops after a failed validation

- **Setup.** The jar carried `450d2a1` (block-end hint) and `46d6a14` (corrupt-patch hint) with the
  8,192-token window. Run U (`8975f3c9`, attempt `e717bb44`) was created through the new
  `POST /api/tasks/{id}/retry` endpoint from run T, so `retryOf=37aca9ff...`; the retry endpoint returned
  `201` with the linked task, as tested.
- **Calls 1-2 reproduce run T.** `find_symbol(writeValue)` returned 602 characters and reported the
  block end at 3203; `read_file 3169-3203` returned 1,761 characters, the whole method including the
  enum branch at 3185.
- **Call 3 was a 28-character patch** and was rejected with "Patch must change between 1 and 10 files".
  That message is application-level and specific, and the model could act on it.
- **Call 4 applied a 595-character patch.** The stored diff changes `JSONObject.java:3186` from
  `writer.write(quote(((Enum<?>)value).name()));` to `writer.write(quote(value.toString()));`. This is
  the **first local run to apply a correct source patch**; the block-end read is what made the enum
  branch visible.
- **Call 5 ran validation, which failed**: `EnumTest` still asserted the old name. The model then
  returned a report (zero tool calls) whose narrative said the tests were not updated. The attempt
  ended `NEEDS_INPUT` with `changedFiles=1`, validation `FAILED`, and every criterion `UNVERIFIED`.
  Nothing was reported as a pass, and no pull request was opened.
- **The loop accepted the report two steps early.** The failing validation and its test names were in
  the tool conversation, and the model had 19 of its 24 calls left. The loop had no rule that a failed
  validation is not a final answer, so the model's accurate statement that the tests were stale ended
  the run.
- **What this argues for.** `9943192` (the validation-failure nudge) validates a patched tree before
  accepting a final answer and, when the check fails, tells the model to read the failing test, fix it
  with `apply_patch`, and rerun validation, at most twice; a report or a question is not accepted while
  validation fails. It also stores only the final validation run in the report, so a successful retry
  can export its patch; the earlier failure remains in the tool audit. Run V tests that live.

### 24. A tiny fixture completes the work; the report can still end NEEDS_INPUT

- **Setup.** To separate the model's limits from a large repository, a new fixture,
  `repos/small-java` (commit `6187794`), holds a 30-line Maven project with JUnit 4.13.2 and one bug:
  `Greeting.greet(String)` calls `name.isBlank()` before a null check, so `greet(null)` throws. The
  task asks for the null check, a `greetsNullNameAsGuest` test, and a green suite. The jar was
  `1f4e5dd` (validation nudge), model `qwen2.5-coder:14b`, context 8192.
- **W1a (`057934ee`, attempt 1 `22d7ea81`).** Six calls: `find_symbol(greet)`, a 6-line read, a
  1,206-character create-style patch rejected because `GreetingTest.java` already exists (the model
  read the existing test next), then a **1,051-character patch applied**: the null check plus the
  test. `run_validation` failed in 2 s because Docker Desktop's Linux engine was down
  (`dockerDesktopLinuxEngine` pipe missing), not because of the code. The attempt ended
  `NEEDS_INPUT`, `changedFiles=2`, criteria 1-2 `UNVERIFIED`, criterion 3 `FAILED` with the Docker
  error. The platform did not report the unavailable check as a pass.
- **W1b (attempt 2 `7d6f6c4e`, via `POST /answer` "Docker is available again").** Docker was
  restarted (29.8.2). Six calls: it read both files before patching, applied a **1,008-character
  patch** with the same correct change, and validation **passed**. The report marked criteria 2 and 3
  `PASSED`, but criterion 1 stayed `UNVERIFIED` with no evidence, so the task again ended
  `NEEDS_INPUT` ("Acceptance criteria could not be verified") despite the correct, validated change.
- **W1c (attempt 3 `b567d1e1`, via `POST /answer` citing the new test as evidence).** The model read
  both files, then sent a 752-character patch with `main/java/example/Greeting.java` - the `src/`
  prefix was dropped - and git answered only `No such file or directory`. It resent the **identical**
  patch twice; the duplicate guard refused both, but its message said to "change the hunk context",
  which was wrong for a missing path. The model re-read the file, then returned prose; the operator
  stopped the run and the reaper recorded `FAILED` with an outcome-unknown report. The patch was never
  applied, so nothing false was reported.
- **What this shows.** On a small, unambiguous change the loop reaches the right two-file patch and a
  real Docker validation pass; the remaining failure was reporting, not code. A valid report that
  leaves a supplied criterion unassessed is accepted as final (there is no "assess every criterion"
  nudge, only the report-format retry), and the answer flow then depends on the operator guessing the
  missing evidence. The two message gaps W1c exposed are fixed in `b04f111`: the missing-path refusal
  names the existing file with the same suffix, and the duplicate refusal quotes the original
  rejection instead of assuming hunk context.

### 25. On a tiny fixture the repair nudge fires twice and the model still explains

- **Setup.** W2 (`165b5c36`, attempt `c7ef6bf2`) ran the same `small-java` task on the jar with the
  acceptance nudge (`93b973e`), Docker 29.8.2, `qwen2.5-coder:14b`, context 8192.
- **Calls 1-3:** `find_symbol` (452 characters), a 6-line read of `Greeting.java`, then
  `run_validation` **before any change** - the baseline passed. The model returned a report (0 calls);
  the premature-final nudge ("you have not applied a patch") sent it back.
- **Calls 4-6:** it read `GreetingTest.java`, applied a **618-character test-only patch** (added
  `greetsNullNameAsGuest`), and ran validation, which **failed**: the new test calls
  `Greeting.greet(null)` and the source still calls `name.isBlank()` (NPE at `Greeting.java:8`).
- **The validation nudge fired twice.** The model answered each nudge with a 0-tool-call turn
  (5.6 and 5.5 minutes of prose), not with `apply_patch`; the worker refused both as final answers.
  After the third 0-call turn (4.7 minutes) the retries were exhausted and the report was accepted.
- **Outcome.** `NEEDS_INPUT`, `changedFiles=src/test/java/example/GreetingTest.java`, validation
  `FAILED`, all three criteria `UNVERIFIED`, no false pass. The report's validation details name the
  exact NPE and test. The model still had 18 of 24 tool calls left; the missing source change was one
  `if (name == null)`. The acceptance nudge correctly did not fire while validation failed.
- **What this isolates.** W1 showed the model can produce the correct two-file patch; W2 shows the
  failure is repair behavior, not localization: given a failing test it wrote, it explains the
  failure instead of fixing the source. The two validation nudges (finding 23's fix) did not change
  that on this model.
- **Export check, live.** W1b's stored 1,025-character diff applies cleanly to a fresh clone at
  `6187794` (`git apply --check` accepts both files), but the patch endpoint returns `409` for W1
  (now `FAILED` after the reaped third attempt) and for W2: only a `COMPLETED` task with evidenced
  criteria exports a patch. The refusal is the policy working, not a defect.

### 26. A test-only task completes locally, and its exported patch verifies outside the platform

- **Setup.** W3 (`05466bf5`, attempt `374af846`) asked for a test-only change on `small-java`: cover
  surrounding-whitespace trimming (`greet("  Ada  ")` -> `"Hello Ada"`). Jar `9ef4ef5` (validation
  details without Docker setup IDs), `qwen2.5-coder:14b`, context 8192, Docker 29.8.2.
- **Six calls.** `find_symbol(greet)` scoped to `Greeting.java` (452 characters), the 6-line source
  read, `find_symbol(GreetingTest)` (423), the test's lines 7-20 (321), a **568-character test-only
  patch** adding `greetsTrimmedName`, and `run_validation` -> **PASSED**.
- **Report.** Both criteria `PASSED` with evidence: the new test name and the passing suite. The
  acceptance nudge (93b973e) was not needed here; it remains covered by its unit tests (missing
  evidence, exhausted retries, blocking question). The task reached `COMPLETED` with
  `changedFiles=1`.
- **Export, live.** `GET /api/tasks/05466bf5.../attempts/374af846.../patch` returned `200`,
  `text/x-patch`, 569 bytes. On a fresh clone at `6187794`, `git apply --check` and `git apply` both
  exited 0; the clone then passed `mvn -B -o test` in the same Maven image (3 tests, 0 failures)
  outside the platform. This is the first local run in this series to complete the full handoff.
- **The details cleanup.** The report's validation details now start with `[INFO] Scanning for
  projects...`; the two 64-hex Docker IDs from W2 are gone (`9ef4ef5`).

### 27. A rejected patch is resent unchanged, and the model claims a change that never applied

- **Setup.** W4 (`b4f1a8db`, attempt `c1b3f59a`) reran the null-handling task on `small-java` with the
  jar at `fa6513f`, before the failed-validation and no-change status rules. `qwen2.5-coder:14b`,
  context 8192, Docker 29.8.2.
- **The patch loop.** `find_symbol(greet)` and a 6-line read found the relevant code. The model then
  sent a 436-character patch that did not apply at `Greeting.java:7`; the refusal quoted the actual
  numbered lines. The model **resent the byte-identical patch three times** - the duplicate guard
  refused each without another `git apply` - then re-read lines 7-12 and **resent it again**. It never
  produced a different hunk.
- **The ending.** It ran `run_validation`, which passed on the unchanged tree (baseline), and produced
  a final report after 12 model calls (9m46s of model time, 10m09s total). `changedFiles=[]`, yet the
  report's narrative claimed `Greeting.greet` was modified and `greetsNullNameAsGuest` was added. The
  acceptance checks are model-assessed: criterion 2 was `PASSED` with the fabricated test as evidence;
  criteria 1 and 3 were `UNVERIFIED`. Under the old rules the task ended `NEEDS_INPUT` with the
  synthetic "Acceptance criteria could not be verified" question.
- **What this shows.** The duplicate guard prevents repeated git work, but it cannot teach a model to
  repair a hunk; the exact-lines hint was delivered and ignored. The no-change guard is the reason the
  fabricated claim did not complete: `COMPLETED` requires a changed workspace, and the new rule makes
  this attempt `FAILED` ("The agent did not produce a repository change") rather than `NEEDS_INPUT`.
  Acceptance evidence remains a model assessment, not independent proof; the report labels the
  narrative as unverified and the reviewer sees the evidence, but a claim can still name a test that
  does not exist. A future check could cross-reference cited identifiers against the changed tree.

### 28. The no-change rule holds live, but the model fabricates a test and never patches

- **Setup.** W5 (`74feed0b`, attempt `141c76c0`) ran the same null-handling task on `small-java` with
  the jar rebuilt after both status rules (`3d9ce1b`). Same model, context and Docker.
- **What the model did.** Five tool calls: `find_symbol(greet)`, read `Greeting.java:7-12`, read
  `GreetingTest.java`, read the source again after a nudge, then `run_validation` on the unchanged
  tree (PASSED baseline). It **never attempted a patch**. It spent four turns writing prose, two of
  them five minutes long, and produced a final report after 8 model calls (18m42s of model time,
  19m05s total).
- **The report.** `changedFiles=[]`, `patchDiff=null`. The narrative still claimed `Greeting.greet` was
  changed and `greetsNullNameAsGuest` was added to `GreetingTest`. Criterion 1 was `UNVERIFIED`;
  criterion 2 was `PASSED` citing the non-existent test; criterion 3 was `PASSED` citing the baseline
  suite. The last two are false as evidence of the claimed change.
- **The outcome.** `FAILED` with "The agent did not produce a repository change", and **no** synthetic
  acceptance question (`openQuestions=0`). This is the first live verification of the no-change
  precedence: the old rules would have returned `NEEDS_INPUT` with "Acceptance criteria could not be
  verified".
- **What this shows.** The status rule works, but the nudge did not: after the first prose turn the
  worker said "call read_file ... then apply_patch", and the model read the same source again. The
  worker also replayed each rejected report as assistant context, anchoring the next turn to repeat
  the claim. The nudge change committed after this run removes the replayed report and tells the model
  not to re-read an already-read file; W6 tests that change. The model's acceptance evidence remains
  a claim: the no-change guard prevents `COMPLETED` here, but the report still marks a fabricated test
  `PASSED`.

### 29. A correct, validated change is stranded by a report with no acceptance evidence

- **Setup.** W6 (`c8775b02`, attempt `cde00335`) ran the same null-handling task with the jar that
  includes the anti-anchoring nudge (`dcfdc31`), before the concrete acceptance-evidence nudge.
- **What the model did.** Eight tool calls: `find_symbol(greet)`, read `Greeting.java:5-12`, a
  **successful 432-character source patch**; a `create_file` attempt on the existing test was rejected;
  a first test patch was rejected at `GreetingTest.java:11`; it read the test at lines 7-17 and sent a
  **successful 509-character test patch**; `run_validation` **PASSED**.
- **The diff is the requested change.** It adds `if (name == null) { return "Hello guest"; }` before the
  blank check and a `greetsNullNameAsGuest` test that asserts `Greeting.greet(null)` returns
  `"Hello guest"`. `changedFiles` contains both files; the stored diff is not truncated.
- **The report blocked completion.** The narrative correctly described both changes, but
  `acceptanceChecks` left all three criteria `UNVERIFIED` with empty evidence. The acceptance nudge
  fired twice; the model repeated the empty report. The attempt ended `NEEDS_INPUT` with the synthetic
  "Acceptance criteria were not verified" question after 12 model calls (16m22s of model time, 16m49s
  total).
- **What this shows.** The anti-anchoring nudge is consistent with the first run since W3 that applied a
  patch and passed validation — including recovering from a rejected test patch after re-reading the
  file — but one run is not a controlled comparison. The platform was right not to mark evidence-free
  criteria `PASSED`, yet a correct validated change is now stranded: it cannot be exported because the
  export policy requires `COMPLETED`, and a new attempt starts from a fresh workspace. The following
  commit makes the acceptance nudge concrete (changed files, validation result, one example entry); if
  a live run still cannot produce evidence, the next step is a human confirmation path rather than
  trusting model prose.

### 30. A concrete acceptance nudge gets two of three criteria; a recorded human confirmation completes the validated run

- **Setup.** W7 (`5e5b7731-2c1e-4e01-8fdc-4c0a80bb685a`, attempt
  `bff28ad1-7fd5-43d9-ba0d-8cb41b583fea`) ran the same null-handling task on `small-java`
  (`6187794`) with the jar rebuilt after the concrete acceptance nudge (`b3673c1`). Same local
  `qwen2.5-coder:14b`, context 8192 and Docker validator.
- **What the model did.** Eight tool calls: `find_symbol(greet)`, read `Greeting.java:7-12`, read
  `GreetingTest.java:1-25`; a 891-character Codex envelope was rejected because its first content
  line came before an explicit `@@`; a 972-character patch was rejected at `Greeting.java:7` and
  `GreetingTest.java:13` with the actual-lines hint; the byte-identical resend was refused by the
  duplicate guard; a corrected 1,034-character patch applied; `run_validation` **PASSED**.
- **The diff is the requested change.** It adds `if (name == null || name.isBlank())` to
  `Greeting.greet` and `greetsNullNameAsGuest`, which asserts `Greeting.greet(null)` returns
  `"Hello guest"`. `changedFiles` contains both files; the stored diff is not truncated and the
  exported patch is 1,037 bytes.
- **The report improved but did not finish.** The concrete nudge visibly raised compliance from W6's
  0/3 to 2/3 evidenced criteria: criteria 2 and 3 were `PASSED` citing the new test and the Maven
  `BUILD SUCCESS`, but criterion 1 (the null behavior itself) stayed `UNVERIFIED` with empty
  evidence. The synthetic question remained and the task ended `NEEDS_INPUT` after 12 model calls
  (1,521,757 ms of model time, 1,546,068 ms total, 25m46s).
- **A human confirmation completed it.** With the confirmation build, `POST
  /api/tasks/{id}/attempts/{attemptId}/confirm` for that task and attempt, with reviewer `Daniel`,
  returned `200`. The evidence was:
  > Reviewed the stored diff: Greeting.greet now treats null as a guest, and greetsNullNameAsGuest
  > asserts Greeting.greet(null) returns "Hello guest"; the stored run_validation result was Maven
  > tests PASSED with exit 0.

  Task and attempt became `COMPLETED`; `report.review` holds the reviewer, evidence
  and timestamp; `acceptanceChecks` hold the human evidence; `modelAcceptanceChecks` preserve
  criterion 1 as `UNVERIFIED` and criteria 2-3 as the model's `PASSED` claims; `openQuestions` is
  empty; and history sequence 4 is `NEEDS_INPUT -> COMPLETED` with actor `REVIEWER`.
- **The exported patch verified outside the platform.** The patch endpoint returned the 1,037-byte
  diff. On a fresh clone of `small-java` at `6187794`, `git apply` changed both files cleanly and
  `mvn -B -o test` passed **3/3** with `BUILD SUCCESS`.
- **What this shows.** The concrete acceptance nudge is an improvement, but a local 14B still left the
  behavioral criterion unevidenced; automated completion remains report-limited. The human path is
  not a false pass: it required changed files, non-empty validation with every check `PASSED`, an
  untruncated diff, no `FAILED` criterion and no blocking model question, and it records the reviewer
  and evidence while preserving the model's original claims. The first rejection also exposed a
  normalizer stricter than the Codex grammar; the follow-up commit (`3960220`) accepts an implicit
  first update hunk, while exact-match placement and every path/size check remain. A generic
  `PATCH /status` remains an administrative override; only the confirmation endpoint updates both the
  attempt and the task and enables export.

### 31. Eight rejected patches leave no change; the loop fails honestly

- **Setup.** W8 (`28a2fb5a-9d64-4c74-9db3-a9e103e6dcf6`, attempt
  `ddbc7275-2f4c-4875-a460-5e420e24d229`) ran the same null-handling task on `small-java`
  (`6187794`) with the jar rebuilt after the acceptance-evidence prompt change and before the
  applied-patch fix. Same local `qwen2.5-coder:14b`, context 8192 and Docker validator.
- **What the model did.** Seventeen tool calls: `find_symbol(greet)`, reads of the source and test,
  and eight `apply_patch` attempts (894/905/905/905/564/564/564/564 characters). The first patch
  failed at `Greeting.java:6` and `GreetingTest.java:15`; later attempts failed at the test's line 15
  or 16, then at `Greeting.java:13`, and four byte-identical resends were refused by the duplicate
  guard in a few milliseconds. It re-read the same ranges between attempts but never produced a patch
  that matched the file.
- **No change reached the workspace.** `changedFiles=[]` and `patchDiff=null`. The model still called
  `run_validation`, which passed the **unchanged 2-test suite**. Its narrative claimed it had applied a
  source patch and added `greetsNullNameAsGuest`, but the machine report recorded no diff and left all
  three criteria `UNVERIFIED`. The attempt ended `FAILED` ("The agent did not produce a repository
  change") after 22 model calls, 2,105,268 ms of model time and 2,160,423 ms total (36m00s).
- **What this shows.** The duplicate guard and the no-change rule held: a baseline validation pass
  cannot turn an untouched repository into a completed task, and the model's prose cannot either.
  The eight failures are a patch-generation problem, not a safety problem, and the new
  test-assertion evidence guidance was not exercised because no test changed.

### 32. One applied patch survives eight failures, but the worker forgot it

- **Setup.** W8b (`6ff90389-348c-4dfb-8d83-16d14e891340`, attempt
  `a03cdff8-3efb-4218-bc1f-6dd8e71123c6`) ran the same task and fixture with the same jar.
- **What the model did.** Fifteen tool calls: `find_symbol`, read `Greeting.java:7-12`, three rejected
  source patches (a corrupt patch at line 13, a context failure at line 7, and a duplicate), then a
  **467-character source patch applied**. It searched for the test, read `GreetingTest.java:7-13`,
  and sent a 562-character test patch that failed at line 7; two duplicates followed, a repeated
  source patch failed because the source was already changed, and a final duplicate test patch was
  refused before `run_validation` **PASSED** the old 2-test suite.
- **The stored diff is one correct line, but the test was never added.** The untruncated diff changes
  `src/main/java/example/Greeting.java` from `if (name.isBlank())` to
  `if (name == null || name.isBlank())`; `changedFiles` contains only that file. Criterion 1 (the
  behavior) was `UNVERIFIED`; criteria 2 and 3 were `UNVERIFIED` with "Patch application failed,
  changes not made." The blocking question "Why is the patch failing to apply?" made the attempt
  `NEEDS_INPUT` after 20 model calls, 1,897,185 ms of model time and 1,925,451 ms total (32m05s).
- **The applied-patch state was reset by a later failure.** After the successful source patch,
  `patchApplied` was true; the rejected test patch set it back to false. The worker's no-patch check
  therefore treated the workspace as unchanged on the final turns and could say "you have not applied
  a patch" even though `Greeting.java` was already changed. The regression test
  `keeps an applied patch when a later patch is rejected` sends one applied patch, one stale patch and
  a final report, then asserts that the applied file remains and no false no-patch nudge appears. The
  fix (`fa7c789`) sets the fact on success only; a rejected patch no longer clears it.
- **What this shows.** The attempt ended honestly: one source-only change, an unverified test
  criterion and a blocking question, so it stayed `NEEDS_INPUT` and no patch was exported. The test
  patch failure is still a model limitation. W8b did not exercise the changed-test evidence guidance
  because the test never changed; it did expose and fix a worker-state bug that would otherwise
  misreport a partly changed workspace.

### 33. A confident report cannot hide an unapplied patch

- **The gap.** W8b applied the source fix, never applied the test patch, and happened to end
  `NEEDS_INPUT` because it asked a blocking question. The status decision did not read
  `trace.patchFailures` at all: a source patch plus a rejected test patch, passing validation, and a
  final report claiming both criteria `PASSED` would have satisfied the old completion rule. The
  failure was stored in the report but had no effect on the outcome.
- **Fix.** The worker now tracks the latest `apply_patch` outcome separately from the one-shot
  correction nudge. The unresolved failure is set on a rejection and cleared only when a later patch
  applies to every file the rejected patch named. A malformed patch that names no files is cleared by any
  later successful patch, because there is nothing to match; a successful patch to a different file is
  not a correction. If the failure is still set at finalization, the task ends `NEEDS_INPUT` with the
  reason "A patch failed and was not corrected", even when the report claims every criterion passed and
  validation passed. The report's implementation records the failure, and `patchFailures` still keeps
  the full recovery history. A failure followed by a successful patch to its named files clears the
  state and can still complete.
- **Regression coverage.** `does not complete when the final report claims success after an unapplied
  patch` applies a source patch, rejects a test patch, then returns a report claiming both criteria
  passed against a passing validation. It asserts `NEEDS_INPUT`, all criteria still `PASSED` in the
  stored model assessment, one recorded patch failure, and an unchanged test file. The earlier
  `keeps an applied patch but blocks completion when a later patch stays rejected` test now expects
  `NEEDS_INPUT` while keeping its no-false-no-patch-nudge assertion. `does not clear a patch failure
  when a later patch changes a different file` rejects a test patch, applies a source patch to another
  file, and asserts the rejected test path still blocks completion. The failure-then-success tests
  still complete.
- **What this shows.** The report field was not a status input; the guard prevents a false completion
  even when the model's narrative is confident. This is a code-review finding from W8b, not a new live
  run: W8b remains unconfirmed `NEEDS_INPUT`.

### 34. A created file is a workspace change, not a missing patch

- **The gap.** The worker's changed-workspace fact (formerly `patchApplied`) was set only by a
  successful `apply_patch`. A successful `create_file` changed the tree but did not count: the worker
  could tell the model it had not changed the workspace and could skip the pre-final validation that a
  patch triggers. The message also named only `apply_patch`, so a model that legitimately created a new
  file was pushed toward a redundant patch.
- **Fix.** A successful `create_file` now sets the workspace-changed fact and invalidates earlier
  validation, just like a successful patch. Validation runs before a final report is accepted. The
  nudge says "you have not changed the workspace" and names both `create_file` (new files) and
  `apply_patch` (existing files).
- **Regression coverage.** `treats a created file as a workspace change` runs validation, creates
  `NewTest.java`, returns a final report, and asserts the file exists, the task completes, validation
  passed again (`run_validation`, `create_file`, `run_validation`), and no no-change nudge was sent.
  The applied-patch tests still assert that a later rejected patch does not reset the
  workspace-changed fact.
- **What this shows.** This is a code-review finding from the `create_file` path W6 exercised (its
  attempt was rejected because the test file already existed); no live run has completed a
  create-only change. Finding 35 covers the rejected-creation path.

### 35. A rejected file creation cannot be reported as a created test

- **The gap.** A failed `create_file` was only in the tool audit; the status decision did not read
  it. A source patch plus a rejected `create_file` for the test, a passing validation of the old
  suite, and a final report claiming both criteria `PASSED` would have satisfied the old completion
  rule. This is the same shape as finding 33, through a different write tool.
- **Fix.** The worker records a failed creation by normalized repository path. While any path remains
  unresolved, the task ends `NEEDS_INPUT`, even when the report claims the file exists and validation
  passed. A later successful `create_file` for that path, or an `apply_patch` whose reported path list
  includes that path, clears the failure; a source-only patch does not. At the time, the reason was
  "A file creation failed and was not corrected" and the report said "File creation still failed".
  Finding 36 generalizes the rule to every failed write and renames those strings.
- **Regression coverage.** `does not complete when the final report claims a failed file creation
  succeeded` applies the source patch, rejects a `create_file` for the existing test, and returns a
  report claiming both criteria passed against a passing validation; it asserts `NEEDS_INPUT`, the
  failed creation recorded in the report, all model checks still `PASSED`, no open question, and the
  test file unchanged. `clears a failed file creation when a later patch changes the same file` rejects
  the creation, applies a test patch to that path, and asserts `COMPLETED`.
- **What this shows.** A failed creation is a known negative, not an invisible no-op. The path match
  keeps a valid recovery unblocked; a failure for a path no later write touches remains unresolved
  for human review. This is a code-review finding, not a live run.

### 36. An exact text edit removes the unified-diff hunk bottleneck

- **The evidence.** W8 and W8b (findings 31 and 32) both read the exact source and test lines, then
  still failed to produce a unified diff whose context and hunk ranges matched. W8 tried eight patches
  and changed nothing; W8b applied one source patch but never applied the test patch and ended
  `NEEDS_INPUT` with a blocking question. Four byte-identical resends were refused by the duplicate
  guard. The repeated failure was patch construction, not localization or validation.
- **The change.** `WorkspaceToolExecutor` now offers `replace_text` for an existing UTF-8 file. It takes
  `path`, `oldText`, and `newText`; `oldText` must match literally and occur exactly once, and `newText`
  may be empty to delete. It rejects a missing file, an outside path, binary content, identical old and
  new text, missing or ambiguous snippets, text over 64,000 characters, and a file or result over 2 MB;
  each refusal says nothing was changed and how to proceed. It preserves CRLF when the file uses it.
  This is still a proposal the application authorizes: it has no shell, network, or
  arbitrary-command path, and `apply_patch` / `create_file` remain available for multi-file,
  multi-hunk, and new-file changes.
- **Worker state.** A successful `replace_text` is a workspace change: it suppresses the no-change
  nudge, invalidates an earlier validation, and forces validation before a final report. A failed
  replacement is recorded by normalized path, and completion stays blocked until a later successful
  write touches that path. A successful replacement can also clear a rejected patch when it writes
  every path that patch named. The completion reason and report wording now cover both creation and
  replacement ("A file write failed and was not corrected", "File write still failed").
- **Prompts and audit.** The system and task prompts and the no-change, patch-failure, and
  validation-failure nudges point to `replace_text` for a localized edit and keep `apply_patch` for
  larger changes. The tool audit records the path and `oldCharacters` / `newCharacters`, not the
  replacement text.
- **Regression coverage.** `replaces exactly one exact snippet and preserves line endings` covers an
  exact edit, deletion, and CRLF preservation. `rejects missing ambiguous or unbounded text
  replacements` covers missing, ambiguous, identical, outside-path, missing-file, oversized text,
  oversized file/result, binary, and missing-argument cases. `treats a text replacement as a workspace
  change and forces revalidation` covers the change/validation rule. `does not complete when a failed
  text replacement is not corrected`, `clears a failed text replacement when a later replacement
  succeeds`, and `clears a failed patch when a later text replacement changes every named file` cover
  unresolved-write and recovery behavior. The two focused suites pass 80/80.
- **What this shows and what it does not.** The tool removes exact unified-diff hunk construction as a
  failure mode for a localized edit, but no live run has exercised it yet. Whether
  `qwen2.5-coder:14b` uses it, still over-reads, or still leaves criteria unevidenced is an empirical
  question for W9; it must not be reported as an improvement until a live attempt completes. W8 and
  W8b remain unconfirmed, and their `FAILED` / `NEEDS_INPUT` outcomes stand.

### 37. `replace_text` fixes the source edit; the model invents the test and the report accepts it

- **Setup.** W9 (`6246c9d6-58d1-45be-ab31-074f2f1c1edc`, attempt
  `2034440d-8c74-4d82-bc9b-a110e0219de0`) ran the null-handling task on `small-java` at `6187794`
  with local `qwen2.5-coder:14b`, context 8192, and Docker validation. The attempt made 9 model calls
  and 6 tool calls in about 10m41s (24,737 prompt / 1,933 completion tokens; 643,549 ms of model
  time).
- **The source edit worked on the first write.** The tool sequence was `find_symbol`, `read_file`,
  `replace_text`, `find_symbol`, `read_file`, `run_validation`. The replacement changed
  `if (name.isBlank())` to `if (name == null || name.isBlank())`; the only changed file was
  `src/main/java/example/Greeting.java`, and validation passed 2/2. This is the first live use of
  `replace_text`, and it replaced W8/W8b's repeated rejected unified diffs with one exact edit.
- **The report fabricated the test.** The model never called `create_file` or `replace_text` for
  `GreetingTest.java`; the tool audit has no such call. Its report marked the test criterion `PASSED`
  with the evidence "Test method greetsNullNameAsGuest in GreetingTest.java", but the method did not
  exist, and validation had run the old two-test suite. Criterion 1 was `UNVERIFIED` only because the
  model quoted `'Hello guest'` with single quotes while the task used `"Hello guest"`; criterion 3
  passed. The task ended `NEEDS_INPUT` ("Acceptance criteria could not be verified"), patch export
  returned `409`, and the stored 467-byte diff was untruncated and correct.
- **Fix: verify a cited method, and match quote style.** Criterion matching now normalizes whitespace
  and quote characters, so a report that copies a criterion with different quotes still matches while
  two criteria that quote different values remain distinct. A `PASSED` check that explicitly cites a
  test method is checked against the final workspace: test-like files (a path segment `test`/`tests`,
  or a name ending `Test`, `Tests`, `Spec`, or `IT` with `.java`/`.kt`) are scanned for a declaration
  or a backticked name, bounded to depth 12, 200 files and 500 KB per file, skipping VCS, build
  output, dependency and IDE directories. A missing method, or a scan failure, downgrades the check to
  `UNVERIFIED` and records the rejection. The check can only ever weaken a claim.
- **Nudge.** When a report marks a test-requiring criterion `PASSED` but no test file changed, the
  worker asks once for the missing edit, and only when the workspace changed, validation passed, no
  blocking question remains, and no patch or write failure is unresolved. A cited method that already
  exists is left alone, so a fix validated by an existing regression test is not asked to add another.
  The prompt now tells the model that a cited test method is checked against the workspace.
- **Regression coverage.** `rejects a cited test method that is absent from the final workspace` also
  places a matching declaration in `target/` and an oversized `HugeTest.java` to prove build output
  and the size bound are skipped. `matches a criterion that differs only in quote style` covers the
  W9 mismatch, and `nudges once when the report claims a test but no test file changed` covers the
  nudge and the recovery write; `does not treat a test-suite validation criterion as a missing test`
  covers the suite exclusion. The worker suite is 56 tests; the full gate is 298 tests with the
  coverage checks met.
- **What this shows and does not.** W9 is both a real improvement in the write path and a real
  false-positive in the report: the model changed the source with one exact edit, then described a
  test it never wrote. The evidence check turns that claim into `UNVERIFIED` instead of a completion,
  and the nudge gives the model one chance to make it true. W9 remains an unconfirmed `NEEDS_INPUT`,
  and no run had yet completed the small-fixture task with both the source and the test changed on the
  current jar. W8, W8b and W9 remain unconfirmed.

### 38. The current rules complete the small fixture with a real source and test change

- **Setup.** W10 (`d0516b53-4686-4cae-84df-78dd90bed65f`, attempt
  `350f0a74-f1bd-4138-bb6a-67ebdbe0b69a`) ran the same null-handling task on `small-java` at
  `6187794` with the same local `qwen2.5-coder:14b`, context 8192 and Docker validation, on the jar
  built from `47a780b` (the cited-method check, missing-test nudge, and quote normalization). One
  attempt; 7 model calls, 6 tool calls, 650.7 seconds (~10m51s); 19,168 prompt / 1,718 completion
  tokens.
- **What happened.** The model called `find_symbol`, `read_file`, `find_symbol`, `read_file`, then
  sent one 896-character `apply_patch` that added the null guard to `Greeting.java` and the
  `greetsNullNameAsGuest` test to `GreetingTest.java`, then called `run_validation`. Docker Maven
  passed 3/3. The report listed both changed files, all three criteria `PASSED`, and cited the test
  method that the workspace now contains. It did not use `replace_text`; the one unified diff was
  accepted without a retry.
- **Interface check.** `GET .../patch` returned `200` with a 1,025-byte, untruncated diff. On a fresh
  clone at `6187794`, `git apply --check` accepted it, `git apply` changed exactly the two files, and
  `mvn -B -o test` passed 3/3 outside the platform.
- **What the new rules did and did not do.** The missing-test nudge did not fire: the report's test
  file had changed and the cited method existed, so there was nothing to ask for. The evidence check
  ran and left the claims `PASSED`. Criterion 1 still differed from the task only in quote style, and
  normalized matching accepted it. This run proves the current path can complete honestly; it does
  not prove the nudge changed the model's behavior. A recurrence of W9's fabricated method would be
  downgraded, but W10 did not reproduce that failure.
- **Comparison.** W8 failed with no change after eight rejected patches; W8b applied the source but
  never the test; W9 fixed the source with one `replace_text` edit and fabricated the test; W10
  changed both files with one patch and passed 3/3. The model's behavior varies between runs. The
  platform rules decide whether a report is honest, not whether the model chooses to write the test.
  One completion is not reproducibility; a second run on the same jar is needed before calling the
  task reliable.
- **What this shows and does not.** This is the first automated `COMPLETED` on the small fixture with
  the current safety rules, and its exported patch verifies outside the platform. It is one run, not
  a benchmark result; W8, W8b and W9 remain unconfirmed, and W10 is not evidence for a larger
  repository.

### 39. Two consecutive completions make the narrow task reproducible

- **Setup.** W11 (`38885077-1db7-4c0d-b85a-664357bccb27`, attempt
  `179bed06-27d3-4830-9c08-3adb3ce0c6df`) repeated W10 exactly on `small-java` at `6187794`, with
  the same jar built from `47a780b` and the same local model and settings. 8 model calls, 7 tool
  calls, 464.3 seconds (~7m45s); 21,973 prompt / 1,414 completion tokens.
- **What happened.** `find_symbol`, `read_file`, `replace_text` (the source null check),
  `find_symbol`, `read_file`, `apply_patch` (the test), `run_validation`. Docker Maven passed 3/3,
  both files changed, and all three criteria were `PASSED` with evidence. No missing-test nudge
  fired, no blocking question remained, and there was no patch failure.
- **Interface check.** `GET .../patch` returned `200` with a 1,037-byte, untruncated diff. On a fresh
  clone at `6187794`, `git apply --check` accepted it, `git apply` changed exactly the two files, and
  `mvn -B -o test` passed 3/3 outside the platform.
- **Comparison with W10.** W10 used one `apply_patch` for both files; W11 used `replace_text` for the
  source and `apply_patch` for the test. Both wrote the test unprompted; in neither run did the
  missing-test nudge fire. That is model variance inside a working tool loop, not a deterministic
  recipe.
- **What this shows and does not.** Two consecutive completions on the same 30-line fixture, with
  real test changes, passing Docker validation, evidenced criteria, and exported patches that pass
  outside the platform. The narrow task class is reproducible at 2/2. This is not a benchmark result
  and says nothing about larger repositories or other task classes; the next step is a measurable
  baseline or a second task class.

### 40. A stale report after the repair patch blocks a correct completion

- **Setup.** W12 (`a354a70f-7274-4a91-b47c-c4184bc210ca`, attempt
  `5339de70-1985-4784-95d2-2a12f936af13`) ran a second task class on `small-java` at `6187794`
  with the same jar built from `47a780b` and the same local model and settings: change the named
  greeting prefix from "Hello " to "Hi " and update the existing test. 16 model calls, 15 tool calls,
  707.7 seconds (~11m45s); 54,348 prompt / 1,675 completion tokens.
- **The final workspace was correct.** The model updated the test with `replace_text`, validated too
  early (the source still said "Hello "), then applied the source change with a 289-character
  `apply_patch`; the automatic validation passed 3/3. Changed files were `Greeting.java` and
  `GreetingTest.java`; the 881-character diff was untruncated and exactly the intended two-line
  change. The audit shows three rejected `replace_text` calls ("oldText occurs more than once"), one
  rejected patch, then the successful writes.
- **The report was stale.** After the failed validation, the model returned a report marking the two
  change criteria and the suite `FAILED`, the guest criterion `UNVERIFIED`, and asking "Why did the
  patch not apply cleanly...". The failed-validation nudge correctly asked for a repair instead of
  accepting it. The model repaired the source and then sent an **empty final turn**. The worker's
  fallback reused the earlier report, whose facts were now false, so the task ended `NEEDS_INPUT`
  ("Agent needs a user decision") with a correct, passing workspace. `confirmAcceptance` could not
  help: it requires no `FAILED` criterion and no blocking question, and W12 had both.
- **Fix: reuse a report only while no tool has run since it was produced.** `lastModelText` now records
  the tool-call count at capture, and the empty-turn fallback is used only when that count still
  matches. A later write or validation may change the facts, so an empty turn after it is a missing
  report: the format nudge asks for a new JSON report, or the criteria stay unverified. The worker
  also avoids appending an empty assistant message for that nudge. Failure paths with no intervening
  tool keep their narrative, which is why this is a guard rather than a removal.
- **Regression coverage.** `does not reuse a report from before the repair patch and passing
  validation` reproduces the sequence — failed validation, `FAILED` report with a blocking question,
  repair patch, empty turn, automatic passing validation — and asserts `COMPLETED`, the fresh
  evidence, no open questions, and the request for a new report. With the guard disabled, the same
  test reproduces W12 exactly: `expected <COMPLETED> but was <NEEDS_INPUT>`. Worker suite 57 tests;
  the full gate is 299 tests with the coverage checks met.
- **What this shows and does not.** W12 is a second task class, and its change/test coordination is
  harder than the null-handling shape, but the failure was in report state rather than the model's
  edit: the diff and validation were right, only the report was stale. W12 remains an unconfirmed
  `NEEDS_INPUT`; a rerun on the fixed jar is the next experiment.

### 41. The prefix task completes twice in three runs; one run makes no tool call

- **Setup.** W13 (`6ceea219-8de9-40b7-a119-2794dea2c94d`, attempt
  `410966f6-be52-4d4a-8f6e-9f1f3d82ae45`), W14 (`02ce0edd-14d8-4e24-9cec-2783efa8bbe8`, attempt
  `0e96e8d2-9110-4a38-9d74-9b8208925ee6`) and W15 (`6fd5f061-3c9c-4bd5-9ea8-8fc2178bbcf6`, attempt
  `88cd9e2e-6ea6-4f4c-a745-14cf16b8db4a`) repeated W12's prefix task on `small-java` at `6187794`,
  with the same jar built from `e97a440` (the stale-report guard) and the same local
  `qwen2.5-coder:14b` settings.
- **W13: completed after a failed validation and several rejected edits.** 24 model calls, 20 tool
  calls, 1,667 seconds (~27m46s); 93,250 prompt / 3,473 completion tokens. Two `replace_text` calls
  that tried to replace the bare `"Hello "` were rejected as ambiguous, a 38-character-to-3-character
  edit left the source incomplete, and validation failed 3/3 after the test edit. The model read the
  source, had a 30-to-27-character replacement rejected because the text did not match, read it three
  more times, then replaced the 3-character fragment with the full 35-character line carrying the new
  prefix. The final validation passed 3/3. All four criteria were `PASSED` with evidence and no
  question remained; the 881-byte diff was untruncated, and the exported patch applied to a fresh
  clone and passed `mvn -B -o test` 2/2 outside the platform.
- **W14: an honest no-change failure.** 4 model calls, **0 tool calls**, 285 seconds (~4m45s); 9,575
  prompt / 888 completion tokens. The model wrote a plan as prose whose `implementation` contained
  JSON pseudo tool calls (`find_symbol`, `replace_file`) but never invoked a tool. The worker ran its
  automatic baseline validation on the unchanged tree (it passed 2/2) and failed the task with "The
  agent did not produce a repository change". `changedFiles=[]`, the patch was empty, and every
  criterion stayed `UNVERIFIED`: no false pass.
- **W15: clean completion.** 8 model calls, 7 tool calls, 646 seconds (~10m45s); 21,723 prompt /
  2,028 completion tokens. `find_symbol`, `read_file`, `replace_text` (source), `find_symbol`,
  `read_file` (the whole test), `replace_text` (test), `run_validation` passed 3/3. All four criteria
  were `PASSED` with evidence and no question remained; the same 881-byte diff was untruncated, and
  its exported patch also applied and passed 2/2 on a fresh clone.
- **What this shows and does not.** The second task class is 2/3: two correct, evidenced completions
  and one model stall that the platform failed honestly. That is not yet the 2/2 of the null-handling
  class, and another run would refine the estimate rather than change the platform's behavior. W13
  and W15 also show the task can be reached through different edit paths: a long recovery from
  ambiguous and malformed replacements, and a clean seven-call sequence. The stale-report guard was
  not directly exercised by these runs — W13 recovered from its failed validation and produced a
  fresh report, W14 never called a tool, and W15 never failed validation — so its evidence remains
  the regression test and W12. W12 stays unconfirmed.

The next slice is the phase-2 baseline rather than another prefix run. The two completions already
characterize the task class, and the third run's failure is a model behavior the platform handled
correctly; repeating it would not change the code or the safety story. The baseline should carry the
model-side no-tool-call failure as a known outcome, not average it away.

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
| R `f5ad2e79` | local `qwen2.5-coder:14b`, Ollama context **4096** | 10 tool calls, operator-stopped and reaped `FAILED` with an outcome-unknown report: the first refusal quoted the numbered lines at `EnumTest.java:100` and the model immediately read 94-104, then sent the **same 1,582-character patch** twice more (calls 7, 8) and a 1,643-character variant twice (calls 9, 10), all rejected at the same line. `ollama ps` showed 4,096 tokens, smaller than this conversation, so truncation confounds the result (finding 20) |
| S `8ec6f803` | local `qwen2.5-coder:14b`, Ollama context **8192** | 9 tool calls, operator-stopped and reaped `FAILED`: `find_symbol` scoped to `JSONObject.java`, then four reads of 3165-3175 and five 630-character patches aimed at 3170 (the enum branch is 3185). Call 3 was rejected by git; calls 5, 7 and 9 were refused in **6 ms** by the new duplicate guard. The larger window did not change the narrow read (finding 21) |
| T `37aca9ff` | local `qwen2.5-coder:14b`, context **8192**, with the block-end hint | 6 tool calls, operator-stopped and reaped `FAILED`: `find_symbol` reported the block end (602 characters) and the model read **3169-3203**, the whole method including the enum branch, then `EnumTest` 38-72. Its first two-file patch (2,575 characters) was rejected for an `index 1234567..89abcde` line **inside a hunk** (`corrupt patch at <stdin>:34`); after a 13.5-minute turn it resent the identical patch and the duplicate guard refused it in 5 ms (finding 22) |
| U `8975f3c9` | local `qwen2.5-coder:14b`, context **8192**, block-end + corrupt-patch hints; created by `POST /api/tasks/{T}/retry` | 5 tool calls, `NEEDS_INPUT`: after `find_symbol` (602 characters) and reading **3169-3203**, a 28-character patch was rejected ("Patch must change between 1 and 10 files") and a **595-character patch applied**: `JSONObject.java:3186` `name()` → `toString()`. Validation **failed** because `EnumTest` still asserted the old name; the model then reported that the tests were not updated. `changedFiles=1`, all criteria UNVERIFIED, no false pass (finding 23) |

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

## The small-fixture runs

The JSON-java task carries the whole repository and a 24-call budget; this fixture isolates the
model's report discipline. `small-java` is a 30-line Maven project (JUnit 4.13.2, compiler 3.11.0).
W1/W2 fix a null-handling bug in `Greeting.greet` (three criteria: null returns "Hello guest", a test
covers it, the suite passes); W3 adds a test for surrounding-whitespace trimming (two criteria: the
test exists, the suite passes); W12-W15 change the named greeting prefix from "Hello " to "Hi " and
update the existing test (four criteria), the second task class.

| Run | Model | Outcome |
| --- | --- | --- |
| W1a `057934ee` attempt `22d7ea81` | local `qwen2.5-coder:14b`, context 8192 | 6 calls: correct 1,051-character two-file patch (null check + test) applied; validation failed because Docker Desktop's Linux engine was down; `NEEDS_INPUT`, criteria 1-2 UNVERIFIED, criterion 3 FAILED with the Docker error - no false pass |
| W1b attempt `7d6f6c4e` (via `/answer`) | same, Docker restarted | 6 calls: correct 1,008-character patch applied; validation **PASSED**; criteria 2-3 PASSED, criterion 1 left UNVERIFIED with no evidence -> `NEEDS_INPUT` |
| W1c attempt `b567d1e1` (via `/answer` with the evidence) | same | 752-character patch used `main/java/...` (no `src/`); git said only `No such file or directory`; the identical patch was resent twice and refused by the duplicate guard with hunk-context advice that did not fit; the model re-read, then returned prose; operator stopped, reaper recorded FAILED outcome unknown - nothing applied |
| W2 `165b5c36` attempt `c7ef6bf2` | same, jar with the acceptance nudge (`93b973e`) | 6 calls: baseline validation before any change (the premature-final nudge corrected that), then a test-only 618-character patch and a **failed** validation (the source still NPEs); two validation nudges produced two 0-call prose turns of 5+ minutes instead of a source fix; `NEEDS_INPUT`, test-only change, all criteria UNVERIFIED, export 409 (finding 25) |
| W3 `05466bf5` attempt `374af846` | same, jar `9ef4ef5` (validation details without setup IDs) | 6 calls: read the source and test, applied a 568-character **test-only** patch (`greetsTrimmedName`), validation **PASSED**; both criteria PASSED with evidence -> **COMPLETED**. Export returned 200 (`text/x-patch`, 569 bytes); on a fresh clone `git apply --check` and `git apply` succeeded, and `mvn -B -o test` passed 3/3 outside the platform (finding 26) |
| W4 `b4f1a8db` attempt `c1b3f59a` | same, jar `fa6513f` (before the failed-validation/no-change status rules) | 8 calls: read the source, a 436-character patch was rejected at line 7; the model resent the identical patch three times (duplicate guard) and re-read the file, then resent it again; baseline validation PASSED, `changedFiles=[]`, criterion 2 falsely PASSED with a claimed test, criteria 1/3 UNVERIFIED -> `NEEDS_INPUT` under the old rule; the new rule makes it `FAILED` (finding 27) |
| W5 `74feed0b` attempt `141c76c0` | same, jar `3d9ce1b` (both status rules) | 5 calls: read the source and test, read the source again after a nudge, baseline validation PASSED; no patch was ever attempted, 4 prose turns (two five minutes long), 8 model calls / 19m05s; `changedFiles=[]`, criterion 2 falsely PASSED with a claimed test, criterion 3 PASSED on the baseline suite, criterion 1 UNVERIFIED -> **FAILED** ("The agent did not produce a repository change"), no synthetic question (finding 28) |
| W6 `c8775b02` attempt `cde00335` | same, jar `dcfdc31` (anti-anchoring nudge) | 8 calls: source patch applied (432 chars), `create_file` rejected, first test patch rejected, re-read the test, test patch applied (509 chars), validation **PASSED**; the diff is the correct null guard plus `greetsNullNameAsGuest`; `changedFiles=2`, all criteria UNVERIFIED with empty evidence, two acceptance nudges did not help -> `NEEDS_INPUT`; 12 calls / 16m49s (finding 29) |
| W7 `5e5b7731` attempt `bff28ad1` | same, jar `b3673c1` (concrete acceptance nudge) | 8 calls: `find_symbol`, two reads, a 891-char implicit-hunk rejection, a 972-char context rejection, an identical-resend rejection, a corrected 1,034-char patch applied, validation **PASSED**; criteria 2-3 PASSED with evidence, criterion 1 UNVERIFIED -> `NEEDS_INPUT`; 12 calls / 25m46s. Reviewer confirmation -> **COMPLETED**; exported 1,037-byte patch applied to a fresh `6187794` clone, `mvn -B -o test` 3/3 (finding 30) |
| W8 `28a2fb5a` attempt `ddbc7275` | same, jar with the acceptance-evidence prompt, before the applied-patch fix | 17 calls: eight rejected patches, no change; baseline Maven PASSED 2/2, all criteria UNVERIFIED -> **FAILED** ("did not produce a repository change"); 22 model calls / 36m00s (finding 31) |
| W8b `6ff90389` attempt `a03cdff8` | same | 15 calls: one source patch applied, eight rejected including the test patch; Maven PASSED 2/2 on the old suite, one changed file, all criteria UNVERIFIED and a blocking question -> **NEEDS_INPUT**; 20 model calls / 32m05s (finding 32) |
| W9 `6246c9d6` attempt `2034440d` | same, jar `ccec354` (`replace_text`; before the cited-method check) | 6 tool calls: `find_symbol`, `read_file`, one `replace_text` source edit (the correct null guard), `find_symbol`, `read_file`, `run_validation` **PASSED** 2/2; only `Greeting.java` changed, 467-byte untruncated diff. The report falsely marked the test criterion PASSED with `greetsNullNameAsGuest` in `GreetingTest.java`, which was never written; criterion 1 UNVERIFIED on a quote-only mismatch, criterion 3 PASSED -> **NEEDS_INPUT**, export 409; 9 model calls / 10m41s (finding 37) |
| W10 `d0516b53` attempt `350f0a74` | same, jar `47a780b` (cited-method check, quote normalization, missing-test nudge) | 6 tool calls: 2 `find_symbol`, 2 `read_file`, one 896-char `apply_patch` (null guard plus `greetsNullNameAsGuest`), `run_validation` **PASSED** 3/3; both files changed, all three criteria PASSED with real evidence -> **COMPLETED**; export 200, 1,025-byte patch applied to a fresh `6187794` clone, `mvn -B -o test` 3/3 outside; 7 model calls / 10m51s (finding 38) |
| W11 `38885077` attempt `179bed06` | same, jar `47a780b` | 7 tool calls: `find_symbol`, `read_file`, `replace_text` (source null guard), `find_symbol`, `read_file`, `apply_patch` (test), `run_validation` **PASSED** 3/3; both files changed, all three criteria PASSED -> **COMPLETED**; export 200, 1,037-byte patch applied to a fresh `6187794` clone, `mvn -B -o test` 3/3 outside; 8 model calls / 7m45s (finding 39) |
| W12 `a354a70f` attempt `5339de70` | second task class: change the named prefix to "Hi " and update the existing test; same jar `47a780b` | 15 tool calls: 3 rejected `replace_text` (ambiguous "Hello "), 1 rejected patch, a successful test `replace_text`, a failed validation, a successful source `apply_patch`, automatic validation **PASSED** 3/3; both files changed, 881-byte untruncated diff. The model then sent an empty turn and the worker reused its earlier FAILED report and blocking question -> **NEEDS_INPUT**; 16 model calls / 11m45s (finding 40) |
| W13 `6ceea219` attempt `410966f6` | same second task class, jar `e97a440` (stale-report guard) | 20 tool calls: 2 ambiguous `replace_text` rejections, a 38-to-3-character source edit, a test `replace_text`, validation **FAILED** 3/3, a rejected 30-to-27-character replacement, 3 reads, a 3-to-35-character repair, validation **PASSED** 3/3; both files changed, 881-byte untruncated diff, all four criteria PASSED -> **COMPLETED**; export 200, patch applied to a fresh `6187794` clone, `mvn -B -o test` 2/2 outside; 24 model calls / 27m46s (finding 41) |
| W14 `02ce0edd` attempt `0e96e8d2` | same | 4 model calls, **0 tool calls**: the model narrated pseudo tool calls as prose and made no change; the automatic baseline validation passed 2/2 on the unchanged tree, `changedFiles=[]`, all criteria UNVERIFIED -> **FAILED** ("The agent did not produce a repository change"); 4m45s (finding 41) |
| W15 `6fd5f061` attempt `88cd9e2e` | same | 7 tool calls: `find_symbol`, `read_file`, `replace_text` (source), `find_symbol`, `read_file`, `replace_text` (test), `run_validation` **PASSED** 3/3; both files changed, 881-byte untruncated diff, all four criteria PASSED -> **COMPLETED**; export 200, patch applied to a fresh `6187794` clone, `mvn -B -o test` 2/2 outside; 8 model calls / 10m45s (finding 41) |

This is the first local evidence that the loop can carry a small change through a real Docker
validation; the remaining gap is the report's acceptance assessment (finding 24). W6 repeated it with
the source and test change and a green suite, but an empty acceptance report stranded the validated
change in `NEEDS_INPUT` (finding 29). W7, with the concrete nudge, evidenced two of three criteria;
the stored diff and validation were sound, so an explicit reviewer confirmation completed the task and
the exported patch passed the suite outside the platform (finding 30). The human path is an audited
fallback, not an automated completion, and the remaining automation gap is the local model's report
discipline. W8 and W8b did not exercise the changed-test evidence guidance: W8 never applied a patch,
and W8b never changed the test. W8 correctly failed an unchanged task; W8b correctly stayed
`NEEDS_INPUT` with one source-only change and exposed the applied-patch reset fixed in `fa7c789`.
Neither run was confirmed. W9 (finding 37) exercised `replace_text`: the model made the correct source
edit in one write and passed Docker validation, but never wrote the test it claimed, and the report
marked that test `PASSED`. The cited-method check and the missing-test nudge now refuse that claim.
W8, W8b and W9 remain unconfirmed. W10 (finding 38), on the new jar, changed both the source and the
test with one patch, passed validation 3/3, completed with real evidence, and its exported patch
passed the suite on a fresh clone. W11 (finding 39) repeated it with a different write path
(`replace_text` for the source, `apply_patch` for the test), passed 3/3, and its patch also passed on
a fresh clone. The narrow task class is now reproducible at 2/2 on the current rules, but that is a
30-line fixture and two runs; it is not a benchmark result and says nothing about larger repositories.
W12 (finding 40) tried the second task class: the model coordinated a test edit and a source edit
through three rejected ambiguous replacements and still produced the correct two-file diff and a
passing 3/3 validation, but an empty final turn reused a report from before the repair. The worker now
reuses an earlier report only while no tool has run since it was produced, and W13/W15 completed the
same task on that rule (finding 41). W13 recovered from a failed validation and several rejected
edits; W15 took a clean seven-call path. W14 made no tool call at all and failed honestly as an
unchanged task. The second task class is therefore 2/3, not yet the 2/2 of the null-handling class,
and W12 remains an unconfirmed `NEEDS_INPUT`.

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
