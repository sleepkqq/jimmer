# Jimmer skill canaries

These are closed-book **output-quality** checks, not SQL throughput measurements. The tasks use synthetic domains and public APIs. `cases.json` freezes prompts and behavior rubrics; exact phrasing is not graded.

These retained suites target the **0.12.2-era set and its separately versioned
Quarkus fork**. Their newer-API assertions are not acceptance criteria for
0.9.111. Its Java/Kotlin compiler and runtime checks live in
[`versions/0.9.111/examples`](../../versions/0.9.111/examples/README.md).

Recorded refresh: [results, limitations and answer evidence](RESULTS.md).

Latest comparison: [fresh paired regression and transfer evaluation](ROUND2.md).

Cross-model protocol: [empty versus supplied skill reference](CROSS-MODEL.md), using the same frozen tasks and [grading policy](grading-policy.md).

Follow-up methodology: [transfer-focused refinement](../../docs/skill-refinement-method.md). `transfer-cases.json` was independently authored and sealed before the follow-up candidate was frozen; it is now a regression asset, not a reusable unseen holdout for future tuning.

## Paired protocol

1. Before edits, copy the selected `versions/<release>/skills/` directory to a baseline directory, including any existing uncommitted changes.
2. Run each case in fresh OpenCode sessions using the same model/variant for baseline and updated skills. The runner supplies the named skill's Markdown and references, with tools denied and one model step.
3. Grade each assertion against the retained answer. A PASS needs evidence for the whole assertion; partial or guessed APIs fail. Report failed runs separately from answer failures.
4. Keep latency, context bytes and prompt hashes. The CLI JSON stream used here does not expose token usage, so context bytes are **not** reported as tokens.
5. Retain all failures and repetitions. If the skill changes after evaluation, rerun affected cases and mark the new iteration; do not silently replace bad outputs.

```bash
python3 tests/skill-evals/run.py \
  --skills /path/to/baseline/skills \
  --output /tmp/opencode/jimmer-baseline-run \
  --model 'openai/gpt-5.6-luna#low' --repeats 2

python3 tests/skill-evals/run.py \
  --skills versions/0.12.2/skills \
  --output /tmp/opencode/jimmer-updated-run \
  --model 'openai/gpt-5.6-luna#low' --repeats 2
```

Run with an already configured/authenticated OpenCode provider. No API key or credential export is needed. `--cli` selects the executable. Output directories must be new and outside the public repository; the runner writes a local denied-tools agent there and saves raw CLI events/stderr. It does not alter global configuration or authorize repository edits. On timeout inspect/stop the corresponding session before retrying.

`--case <id>` limits a run to one case and can be repeated. `--cases <path>` selects a separately frozen suite without editing the original tasks. Each new run records the task hash separately from the skill-plus-task prompt hash. A zero CLI exit status only proves submission/completion at the CLI level: inspect the JSON for a final answer and absence of tool calls before grading.

## Limits

- Bundling the specified skill and references measures knowledge transfer, **not automatic skill activation or progressive-disclosure efficiency**. It intentionally removes network/document lookup variability.
- Global harness configuration and model service latency remain environmental factors. Two repetitions are canaries, not a statistical significance claim.
- Manual rubric grading is inspectable but not a blind independent assessment. Do not convert an answer-quality delta into a claim of faster application SQL or universal coding success.
- Generalization checks should use different entities/phrasing and include adjacent-task negative routing prompts before optimizing descriptions further.

Source snapshots and authoring research: [evidence ledger](../../docs/skill-refresh.md).

## Executable checks

```bash
python3 -B tests/skill-evals/test_reporting.py
gradle -p versions/0.12.2/examples/java --no-daemon --console=plain run
```

Use an installed compatible Gradle or an existing wrapper; the fixture adds no wrapper binary. It needs a JDK able to compile with `--release 21` and Maven Central access. Dependency pins are evaluation inputs and can be changed with `-PjimmerVersion=...` for explicit compatibility experiments; do not use this newer-API fixture as the 0.9.111 test.

The smoke application runs APT and DTO generation, then verifies JSON omission/null/empty-list loaded state, owning-side dissociation metadata and polymorphic DTO conversion. It executes insert-select, conditional query-derived upsert and external-version save on an ephemeral H2 database using both `H2Dialect` and the materialized `DefaultDialect` fallback. Assertions cover insert/update/rejection, no-update conflicts, returning membership, final database state, and omission versus empty-collection saves under MERGE/REPLACE. This does not certify production-dialect concurrency or native-image behavior.

The reporting test rejects incomplete or tool-contaminated runs, even when the CLI exit status is zero, and checks that nested skill references reach the canary context.

Generate an auditable report (including answers, grades, timing and hashes):

```bash
python3 tests/skill-evals/report.py \
  --baseline /tmp/opencode/jimmer-baseline-run \
  --updated /tmp/opencode/jimmer-updated-run \
  --output /tmp/opencode/jimmer-report.json
```

`--grading` selects a rubric-result file for a later targeted iteration; pair it with `--cases` for a transfer suite. Grading remains a human/agent inspection step; the script aggregates it rather than pretending text-pattern matching proves correct ORM semantics.

For independent grading, `blind.py --cases ... --baseline ... --updated ... --output ...` creates a shuffled `packet.json` with tasks, assertions and answers but no condition labels. Give the grader only that file; keep `key.json` separate until the grades are complete. Masking reduces condition/order bias but does not make an LLM judge infallible. Audit source-backed API defects and document any adjudication rather than silently replacing grades.
