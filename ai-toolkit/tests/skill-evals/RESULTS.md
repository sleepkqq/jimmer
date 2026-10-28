# Refresh canary results — 2026-09-20

**Historical first-pass results.** The [follow-up comparison](ROUND2.md) uses fresh runs and independent masked grading, which found additional API defects and task/rubric mismatches. Do not treat the earlier 87.5% below as compiler-verified correctness or compare it directly to the stricter follow-up score.

Model: `openai/gpt-5.6-luna#low` through `opencode2` and its configured OpenAI subscription. Eight frozen synthetic tasks, two fresh sessions per condition. Baseline is the working-tree skill snapshot taken before this refresh, including pre-existing edits. There were 36 model calls: 16 baseline, 16 initial updated, and two complete two-run DTO follow-ups.

## Output quality

| Scenario | Baseline | First updated pass | Final applicable pass |
|---|---:|---:|---:|
| Cache/CDC migration | 4/8 | 6/8 | 6/8 |
| Insert-if-absent result | 2/8 | 6/8 | 6/8 |
| External version ingestion | 0/8 | 8/8 | 8/8 |
| Owning-side dissociation | 2/6 | 6/6 | 6/6 |
| DTO PATCH presence | 2/8 | 2/8 | 6/8 |
| Unified Quarkus setup | 4/10 | 10/10 | 10/10 |
| Entity inheritance | 2/8 | 8/8 | 8/8 |
| Insert from select | 1/8 | 6/8 | 6/8 |
| **Total** | **17/64 (26.6%)** | **52/64 (81.3%)** | **56/64 (87.5%)** |

Final applicable pass uses both repetitions of the latest DTO iteration and the unchanged relevant cases from the first updated run. It is not a fresh full-suite third run or selection of the best individual answers. Improvement over baseline: **+60.9 percentage points** on this targeted suite.

### What changed in actual answers

- Baseline proposed deprecated `BOTH`; updated proposed `BINLOG_ONLY` with CDC delivery, retries and readiness checks.
- Baseline put `@OnDissociate` on `Warehouse.bins`; updated correctly put it on `Bin.warehouse`.
- Baseline invented optimistic-lock modes for external versions; updated used `ASSIGNMENT`, `setUpdateWhere` and `isAccepted`.
- Baseline invented dependency coordinates and Quarkus defaults; updated used the unified fork, JitPack and the real cache/error configuration contracts.
- Baseline invented inheritance/DTO and insert-select APIs; updated used the documented/source-verified API shapes.

### Compiler feedback and iteration

First updated DTO answers used `note?` for an already nullable property. Real APT rejected that declaration. After correcting the rule, one rerun still omitted the optional marker on the non-null collection. The next revision distinguished input presence mode from optionality and added a collection to the general example. Both final answers used `note` and `bins? { id }`, the compiler/runtime-verified shape.

Remaining failures are retained: concise responses omit some rubric-required caveats (explicitly naming both modes as deprecated, technical UPSERT update effects, collection-save policy, and dialect-dependent DML materialization). The skills contain these caveats; their presence does not guarantee every model answer repeats them. A high score is not full behavioral certification.

## Cost and timing

For the first complete paired run:

| Metric | Baseline | Updated |
|---|---:|---:|
| Median CLI wall time per response | 17.58 s | 9.62 s |
| Mean supplied skill context | 9,282 bytes | 15,081 bytes |

The quality improvement used more supplied reference context. Timing is descriptive only: runs were sequential, service/cache effects were not controlled, and two repetitions do not establish a general speedup. Token usage was not exposed by the captured CLI events and is not inferred from bytes. Automatic skill selection/progressive disclosure was not benchmarked.

## Verification beyond model answers

- Skill validator: 17 entry points, metadata, local reference links, no fixed library versions, 8 case definitions and 13 shell helpers.
- Reporting regression: rejects missing answers, incomplete runs and tool-contaminated output.
- Codex installer regression, including copied guide/reference files: passed.
- OpenCode clean-home install: 17 skills and all 37 bundled files matched source.
- Real APT/DTO compile and executable Java smoke: passed. JSON omission/null/empty-list state, owning-reference dissociation metadata and polymorphic DTO conversion executed successfully. Java insert-select and conditional external-version APIs compile-checked.
- `git diff --check`: passed.

No production database/native-image workload was run. The smoke build initially required adjusting its compiler selection to the locally available JDK and updating a stale Jackson module import; these were resolved before the successful run.

## Inspectable evidence

- [Frozen tasks and assertions](cases.json)
- [Initial manual grading](grading.json) and [all initial answers/metrics/hashes](results/2026-09-20-iteration-1.json)
- [DTO iteration 2 grading](grading-dto-iteration-2.json) and [answers](results/2026-09-20-dto-iteration-2.json)
- [DTO final grading](grading-dto-iteration-3.json) and [answers](results/2026-09-20-dto-iteration-3.json)
- [Reproduction instructions](README.md) and [public source ledger](../../docs/skill-refresh.md)

Grades are manual and non-blind, using whole assertions and retaining failures. The task set targets known fragile areas and is not representative of every Jimmer task.
