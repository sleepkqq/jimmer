# Grades — usage-first canary round (2026-10-02)

Model: `opencode-go/deepseek-v4.1-flash`, `--repeats 1`, closed-book, tools denied.
Grading: source-grounded inspection against the frozen assertions in [cases.json](../cases.json).
The compile verifier was **not** rerun for this round (Gradle 8.12.1 / JDK 21 not installed);
Java/Kotlin patterns were checked against the authored, compiled `examples/` sources.

| Case | Assertions | Notes |
|---|---|---|
| java-create-and-query | 3/4 → rerun 4/4 | First pass imported `org.babyfish.jimmer.sql.ast.Page` (does not exist in v0.9.111; `Page` is `org.babyfish.jimmer.Page`). Fixed in `jimmer-query`; targeted rerun passed. |
| kotlin-conditional-and-query | 4/4 | Single conditional `createUpdate`, `execute() == 1`, no read-modify-write or graph-save idioms. |
| kotlin-patch-presence | 4/4 | `dynamic` input, omission vs `[]`, `REPLACE`/`UPDATE_ONLY`, `SET_NULL` on owning `Book.store`, unsuffixed `ImmutableModule`. |
| save-result-contract | 4/4 | `INSERT_IF_ABSENT` + real unique constraint, affected-count verification, explicit key lookup on conflict, bounded retry/isolation caveat. |
| cache-trigger-topology | 4/4 | `BOTH` with real CDC for the JDBC importer and second instance; ack only after invalidation; retry/redelivery and outage policy. |
| kotlin-ksp-construction | 4/4 | One model per file, both creation facades, unsuffixed Jackson module. |
| typed-tuple-aggregate | 4/4 | `@TypedTuple` mapper chain, `groupBy`, `forEach(128, ...)`, no `stream()` promise. |

Result after the targeted rerun: **28/28 assertions, 7/7 cases**.

Raw answers: `*-1.jsonl` in this directory; the rerun answer is under `rerun-java/`.
