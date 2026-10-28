# Quick model-output canaries — 0.9.111

These checks test **model-generated answers**, separately from the authored
examples. Seven prompts and 28 assertions define the release set. Since the
2026-10-02 revision the prompts ask for concrete usage and the skills describe
the API to use, instead of testing release-boundary trivia;
`trigger-version-boundary` became `cache-trigger-topology`. Prompts
deliberately state only the call interface the automated checks compile
against, not the API to use. Each run uses the existing closed-book toolkit
runner, selected release skills, fresh sessions and denied tools.

## Run

From the repository root, with an authenticated OpenCode CLI and a new output
directory outside the repository:

```bash
python3 tests/skill-evals/run.py \
  --cli opencode \
  --skills versions/0.9.111/skills \
  --cases versions/0.9.111/evals/cases.json \
  --output /path/to/new-canary-output \
  --model 'openai/gpt-6-astra#low' --repeats 1
```

Inspect answers and source-verify the rubric; CLI exit code alone is not a grade.
The first two prompts request complete Java/Kotlin code; the third requests a
DTO. Compile and execute those **unchanged generated blocks**, using the fixture
model and purpose-written H2 checks:

```bash
# JDK 21 / Gradle 8.12.1, as for the authored examples
python3 versions/0.9.111/evals/verify-generated.py \
  --runs versions/0.9.111/evals/results/2026-10-01-initial \
  --gradle /path/to/gradle-8.12.1/bin/gradle
```

The verifier creates a temporary project, compiles the generated class/object
and DTO, then checks strict insert, duplicate preservation, generated identity,
filtered paging and conditional price/version updates (including stale and
missing rows). It leaves the authored examples untouched. It accepts one
untagged code fence when no language-tagged block is found, writes the patch DTO
under `src/main/dto/example/` and removes the repository's same-entity DTO to
avoid a `Filer` collision. Inspect fresh model code before executing it. The
fixed-seed fixture is not a production-dialect concurrency or throughput
benchmark. On networks with flaky Maven Central TLS handshakes, invoke Gradle
through a wrapper that forces IPv4 and longer HTTP timeouts.

## Evidence

- Model: `openai/gpt-6-astra#low`.
- Initial pass: **19/20 assertions, 4/5 complete cases**. Raw JSONL answers,
  prompt hashes/timings and the initial skill snapshot are retained under
  [results/2026-10-01-initial](results/2026-10-01-initial).
- Generated Java and Kotlin implementations compiled and passed H2 checks.
  The generated `StorePatch` compiled with both APT and KSP.
- The failure was real: the model correctly rejected `isAccepted`, but incorrectly
  rejected **Kotlin `isRowAffected`** and suggested the Java count accessor for a
  Kotlin result. [grading.json](grading.json) records the original failure.
- The skill correction documents the distinct result interfaces, their exact
  semantics and language-specific count methods. The targeted rerun passed
  **8/8 assertions across two fresh repetitions** of the same frozen prompt.
  Its raw answers and corrected skill snapshot are under
  [results/2026-10-01-corrected](results/2026-10-01-corrected).
- [report.json](results/report.json) contains inspected grades, full answers,
  hashes and timings, validated through the shared reporting code. The other
  four cases were not rerun after the targeted result-contract documentation fix.
- Simplified-prompt round (2026-10-01, `opencode-go/deepseek-v4.1-flash`,
  repeats=1): **28/28 assertions, 7/7 cases**. The graded Java and Kotlin answers
  and the graded `StorePatch` compiled and passed the H2 checks (see
  `verify.log`). Artifacts: [results/2026-10-01-flash](results/2026-10-01-flash).
  Three guidance gaps surfaced before this green run and were fixed in the
  skills rather than patched in the prompts: the missing Java
  `Immutables.createBook(...)` facade and Kotlin `Book { ... }` factory, the
  required `@OnDissociate(SET_NULL)` (or runtime override) for save-time
  dissociation of an inverse collection, and the static first-component entry
  point of a generated `@TypedTuple` mapper. Failed intermediate answers remain
  in [grading.json](grading.json) as findings.

- Post-cleanup round (2026-10-01, same model): **28/28 assertions, 7/7 cases**
  after the references switched to the generated Java facades and Kotlin DSL and
  dropped pinned versions from skill text. Two initial misses were fixed in
  guidance, not prompts: the standalone Jackson module name now appears in
  `jimmer-kotlin`, and `jimmer-query` carries a complete `@TypedTuple` query
  example. Compiled Java/Kotlin answers and the DTO:
  [results/2026-10-01-flash-facades](results/2026-10-01-flash-facades).
- Usage-first round (2026-10-02, `opencode-go/deepseek-v4.1-flash`, repeats=1):
  **28/28 assertions, 7/7 cases** after the skills dropped deprecation and
  cross-release bookkeeping and the prompts moved to usage tasks. The first pass
  found one real usage gap — the model guessed
  `org.babyfish.jimmer.sql.ast.Page` instead of `org.babyfish.jimmer.Page` —
  fixed in `jimmer-query`; the targeted rerun of `java-create-and-query` passed
  4/4. Grading was source inspection against the compiled examples; the compile
  verifier was not rerun for this round (Gradle 8.12.1 / JDK 21 unavailable).
  Evidence: [results/2026-10-02-usage](results/2026-10-02-usage).

Grades are a **non-blind self-review against pinned source**, with executable
evidence for the generated functions. This small canary set does not prove
automatic skill discovery, universal API coverage or output quality on other
models. Failed answers remain in the evidence after fixing guidance.
