# Transfer-focused follow-up — 2026-09-20

Compared the previously refreshed **17 skills** with one new frozen candidate. Model: `openai/gpt-5.6-luna#low`, existing OpenAI subscription via `opencode2`. Both conditions received identical task/system prompts, model settings and reference-loading rules. Two fresh trials per task per condition: **64 answering calls** across regression and transfer suites.

## Frozen regression suite

| Scenario | Previous skills | Candidate |
|---|---:|---:|
| Cache/CDC migration | 6/8 | 6/8 |
| Insert-if-absent result | 4/8 | 5/8 |
| External version ingestion | 8/8 | 8/8 |
| Owning-side dissociation | 6/6 | 6/6 |
| DTO PATCH presence | 6/8 | 6/8 |
| Unified Quarkus setup | 10/10 | 10/10 |
| Entity inheritance | 6/8 | 6/8 |
| Insert from select | 6/8 | 7/8 |
| **Total** | **52/64 (81.25%)** | **54/64 (84.38%)** |

An independent grading agent saw shuffled answers with **condition labels hidden**. It checked APIs against public source. Original prompts and assertions were not changed, and no grades were changed after unmasking.

This is a modest observed improvement, **not 95% and not proof of a general performance gain**. The earlier 87.5% used manual non-blind grading and a composite of different iterations. The fresh stricter grading found Java `SaveResult<Item>` declarations and an invented query syntax that the earlier conceptual-flow assessment missed. Those are genuine code defects, not missing keywords.

The original rubric also has task mismatches. It demands a `MappedSuperclass` comparison in a hierarchy-design task, persistence-policy prose in a DTO-only response, and naming both deprecated modes in a task that asks for a supported replacement. These failures remain counted. Adding forced unrelated paragraphs to skills to recover those points would reward benchmark compliance over answering the user.

## Independently authored transfer suite

This suite was hidden from the implementing session until the candidate was frozen. A second independent grading agent evaluated its shuffled, condition-masked answers against public source. Skills were not edited after unsealing, and grades were not changed after unmasking.

| Scenario | Previous skills | Candidate |
|---|---:|---:|
| Query-derived catalog upsert | 4/8 | 7/8 |
| Inventory optimistic save | 8/8 | 5/8 |
| Travel fetch graph¹ | 4/8 | 3/8 |
| Museum Kotlin DTO | 6/8 | 5/8 |
| Catalog cache invalidation | 4/8 | 6/8 |
| Museum aggregate query¹ | 4/8 | 6/8 |
| Travel schema migration¹ | 7/8 | 8/8 |
| Quarkus repository fetcher¹ | 8/8 | 8/8 |
| **Total** | **45/64 (70.31%)** | **48/64 (75.00%)** |

¹ These four tasks had **byte-identical supplied prompts in both conditions** because their skills did not change. Their combined score nevertheless moved from 23/32 to 25/32. The four tasks exposed to changed context moved only from 22/32 to 23/32. With two trials per condition, variation is material; the total increase is not clean causal evidence of a general skill-quality improvement.

Retained failures are substantive: invented factory/binder combinations, unsupported fetcher methods and projection forms, missing DTO entity binding, and confusing optimistic-lock exceptions with ordinary save rejection. The optimistic-save and DTO scenarios regressed in this sample. The strongest improvement was query-derived upsert, but its remaining trial still passed a raw string where an SQL expression was required.

The candidate adds source-correct guidance and executable examples. **The measurements do not establish a universal performance gain or 95% reliability.** Further revisions need another unseen validation set; reusing these now-visible tasks as the sole target would undermine the separation protocol. The report retains regressions rather than selecting favorable tasks or weakening their criteria.

## Executable verification

The Java smoke uses real APT/DTO generation and an ephemeral H2 database. It verifies:

- JSON omission/null/empty-list loaded state and polymorphic DTO conversion;
- `createInsert` leaves conflicts untouched and returns inserted rows;
- `createUpsert` inserts missing rows, updates eligible rows, skips rejected updates and returns only accepted IDs;
- externally assigned versions are not incremented, and stale revisions are rejected;
- omitted collections preserve children, loaded-empty `MERGE` preserves them, and loaded-empty `REPLACE` applies owning-reference dissociation.

Mutation checks run with **both `H2Dialect` and the materialized `DefaultDialect` fallback**. The first exported-entity-table conditional-upsert example failed only on fallback. The final scalar-projection example passes both paths. See the [source discovery and methodology](../../docs/skill-refinement-method.md).

This validates the shipped examples, not every generated model answer. Production-dialect concurrency, native images, automatic skill activation and progressive disclosure remain outside this experiment.

## Cost observations

| Metric | Previous skills | Candidate |
|---|---:|---:|
| Regression: mean supplied reference context | 15,280 bytes | 16,142 bytes |
| Regression: median CLI wall time | 9.65 s | 8.83 s |
| Transfer: mean supplied reference context | 13,875 bytes | 14,518 bytes |
| Transfer: median CLI wall time | 19.46 s | 23.06 s |

Reference context increased by about 5.6% on regression and 4.6% on transfer. No token-usage events were available. Timings are descriptive: concurrent CLI activity and service/cache effects were uncontrolled. There is no demonstrated speedup. Assertions within a task are correlated; 64 assertions are not 64 independent tasks.

## Checks

- Skill metadata, local references, version-free guidance: 17 skills passed.
- Both task files retain their frozen hashes; current skill files match the frozen candidate.
- All 16 task definitions and 13 shell helpers validate.
- Reporting/blinding checks reject changed task hashes and failed/contaminated/incomplete answer evidence.
- Codex installer regression passed.
- Java APT/DTO build and all executable smoke assertions passed.
- Public artifacts passed the private-project-reference scan; `git diff --check` passed.

## Evidence

- [Research and separation protocol](../../docs/skill-refinement-method.md)
- [Frozen task and skill hashes](results/2026-09-20-round2-manifest.json)
- [Original regression tasks](cases.json)
- [Independent regression grades and rubric notes](grading-round2-regression.json)
- [All regression answers, hashes and metrics](results/2026-09-20-round2-regression.json)
- [Independently authored transfer tasks](transfer-cases.json)
- [Independent transfer grades and source references](grading-round2-transfer.json)
- [All transfer answers, hashes and metrics](results/2026-09-20-round2-transfer.json)
