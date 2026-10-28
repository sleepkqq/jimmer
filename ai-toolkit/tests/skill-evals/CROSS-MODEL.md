# Cross-model reference ablation — 2026-09-20

## Results

| Model, `low` | Empty supplied reference | Frozen skill reference | Change | Complete-rubric answers, empty → reference |
|---|---:|---:|---:|---:|
| GPT-5.6 Luna | 46/128 — 35.9% | 102/128 — **79.7%** | +43.8 pp | 1/32 → 13/32 |
| GPT-5.5 | 35/128 — 27.3% | 109/128 — **85.2%** | +57.8 pp | 5/32 → 17/32 |
| GPT-6 Astra | 77/128 — 60.2% | 123/128 — **96.1%** | +35.9 pp | 12/32 → 27/32 |

Supplying the skill reference improved aggregate rubric scores for all three tested models. The improvement is not specific to Luna, but absolute quality and the size of the gain still depend on the model. **96.1% is an assertion score, not the percentage of entirely correct answers**; Astra passed every rubric assertion in 27 of 32 reference answers.

### Per-task scores

Each cell is empty-reference → supplied-reference. The maximum covers both repetitions.

| Task | Maximum | Luna | GPT-5.5 | Astra |
|---|---:|---:|---:|---:|
| Cache/CDC | 8 | 3 → 6 | 5 → 8 | 6 → 8 |
| Insert-if-absent result | 8 | 1 → 5 | 1 → 6 | 2 → 7 |
| External version | 8 | 2 → 8 | 0 → 8 | 0 → 8 |
| Dissociation | 6 | 3 → 6 | 6 → 6 | 6 → 6 |
| DTO PATCH | 8 | 0 → 6 | 0 → 6 | 2 → 8 |
| Unified Quarkus | 10 | 2 → 10 | 1 → 10 | 2 → 10 |
| Inheritance | 8 | 2 → 6 | 1 → 5 | 2 → 6 |
| Insert from select | 8 | 1 → 7 | 0 → 8 | 2 → 8 |
| Conditional query-derived upsert | 8 | 4 → 7 | 0 → 8 | 4 → 8 |
| Optimistic save | 8 | 6 → 5 | 2 → 6 | 8 → 8 |
| Fetch shape | 8 | 6 → 3 | 2 → 6 | 8 → 8 |
| Kotlin DTO | 8 | 1 → 5 | 0 → 6 | 6 → 8 |
| Cache invalidation | 8 | 4 → 6 | 1 → 4 | 6 → 8 |
| Aggregate-query near miss | 8 | 2 → 6 | 4 → 6 | 8 → 8 |
| Migration | 8 | 6 → 8 | 8 → 8 | 7 → 6 |
| Repository/fetcher boundary | 8 | 3 → 8 | 4 → 8 | 8 → 8 |

Regressions remain visible: Luna loses points on optimistic-save semantics and fetcher/query API syntax; Astra chooses stricter NULL uniqueness semantics than the migration rubric allows. No scores were changed after unmasking. Some inheritance/DTO/cache criteria demand explanation beyond the corresponding task wording; those frozen mismatches are documented in the grading policy rather than optimized away.

### Timing and context

| Model | Median CLI seconds, empty → reference |
|---|---:|
| Luna | 19.44 → 13.05 |
| GPT-5.5 | 5.89 → 7.31 |
| Astra | 15.39 → 14.77 |

The mean supplied reference is 0 versus 15,330 bytes per task for every model. Token usage was not exposed. These timings do not establish a speedup: calls ran concurrently, provider/cache effects are uncontrolled, and Luna's reference trials were reused.

## Fixed comparison

This experiment tests whether supplying the current skill content helps across model families. It is separate from [the previous revision-to-revision comparison](ROUND2.md), in which both conditions already had skills.

- Models: `openai/gpt-5.6-luna#low`, `openai/gpt-5.5#low`, `openai/gpt-6-astra#low`, resolved from the installed OpenCode model catalog before running.
- Tasks: the unchanged 8 original and 8 transfer tasks, combined in [cross-model-cases.json](cross-model-cases.json). The transfer cases are now a fixed regression set, not a newly unseen holdout.
- Conditions: an empty supplied reference directory versus the frozen round-2 candidate. The common OpenCode harness/system instructions remain the same; “empty” does not mean a raw model without any harness context.
- Two fresh sessions per task/condition: 32 answers and 128 assertion decisions per condition, per model.
- Reuse the 32 existing Luna reference answers byte-for-byte. Run its 32 empty-reference answers and all 128 answers for the two added models: **160 new answering calls, 192 answers in the comparison**.
- Keep skill files, tasks, assertions, system prompt, tool prohibition and reference-loading rules fixed. No model-specific prompt or skill adjustments.

The [pre-run plan](results/2026-09-20-cross-model-plan.json) records the chosen models, task hash, conditions and reuse. Skill-file hashes remain in the [frozen candidate manifest](results/2026-09-20-round2-manifest.json).

## Grading protocol

Mask both model and reference-condition labels and shuffle answer order. The existing regression and transfer graders each evaluate a 96-answer packet, so the same independent grading agent evaluates every model/condition for a given task. Use the existing [source-backed ambiguity policy](grading-policy.md) and full-assertion criteria; retain task/rubric mismatches rather than altering them after seeing answers. Source inspection is required for suspicious API syntax. The reused Luna answers are graded again in these masked packets rather than carrying over their earlier scores.

Report assertion scores and complete-rubric passes separately. A partial assertion score does not mean the entire code snippet compiles. This remains a closed-book content evaluation: tools, automatic skill activation and compilation of every generated application sketch are outside the answering runs.

Two trials and 16 diagnostic tasks cannot establish independence from every model or task. The useful comparison is the within-model change from empty to supplied reference, alongside per-task regressions and differences in absolute quality. CLI wall times are observational because concurrent calls and service/cache effects are uncontrolled; Luna's reference answers also come from the earlier run window.

## Integrity checks

All 192 trials completed with nonempty text and no error/tool events. Prompt hashes match the captured prompts, task hashes match the frozen cases, and each model received byte-identical prompts for the same task/reference condition. The combined suite equals the two original case arrays exactly. At collection completion, all 37 skill files matched the frozen candidate.

The skill validator and reporting/blinding regression passed. The validator counts 32 case definitions across the three JSON files because the combined suite repeats the same 16 unique tasks; this does not add evaluation scenarios.

### Executable check of a grading ambiguity

The masked regression review identified a rejected-save boundary that deserved a runtime check. For an identity-generated ID and an ordinary DTO requiring that ID, `INSERT_IF_ABSENT` followed by `execute(ItemView.class)` throws `UnloadedException` on a duplicate natural key **before the caller can inspect acceptance**. `SimpleSaveResult.toView` applies the converter even to a rejected partial root. An explicitly rejection-safe DTO could behave differently; the task did not specify one.

The [smoke fixture](../../versions/0.12.2/examples/java/src/main/java/example/Smoke.java) now reproduces this on H2 and verifies a working alternative for both an existing row and a new row: execute with the View metadata's **entity Fetcher**, check acceptance, then construct the DTO only for an accepted root or query the complete unique key when the existing row is required. The existing native/fallback DML, version, DTO-presence and association checks still pass. This validates the runtime concern without modifying benchmark answers or assertions.

After the complete benchmark was graded and unmasked, this general rejection/conversion rule was added to `jimmer-save-modes`. The table above belongs to the frozen pre-correction candidate; the added guidance is runtime-verified but has not been rebenchmarked. The correction applies to rejectable commands generally, not only the benchmark's insert-if-absent example.

## Evidence and reproduction

- Complete answers, hashes and metrics: [Luna](results/2026-09-20-cross-model-luna.json), [GPT-5.5](results/2026-09-20-cross-model-55.json), [Astra](results/2026-09-20-cross-model-astra.json).
- Assertion vectors, masked IDs, per-answer evidence and ambiguity notes: [Luna](grading-cross-model-luna.json), [GPT-5.5](grading-cross-model-55.json), [Astra](grading-cross-model-astra.json).
- [Pre-run plan](results/2026-09-20-cross-model-plan.json), [frozen skill hashes](results/2026-09-20-round2-manifest.json), [unchanged task union](cross-model-cases.json), [grading policy](grading-policy.md).
- [Exact frozen skill bundle](results/2026-09-20-round2-skills.tar.gz), containing all 37 files before the rejection-guidance correction. SHA-256: `7fc349dae0cd8f8ff71d2c516d2e7869000ee22f42eb6a4fb488a4f40ed4b964`.

Use the existing [runner and reporting commands](README.md), pass `--cases tests/skill-evals/cross-model-cases.json` and one of the model IDs above, and supply an empty directory or the frozen skill snapshot through `--skills`. Use two repetitions and a new output directory for each model/condition. Reproducing this exact historical comparison requires the frozen candidate, not the later rejection-guidance correction.
