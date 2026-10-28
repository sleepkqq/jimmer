---
name: jimmer-performance
description: Diagnoses measured Jimmer slowness - SQL round trips per request, QueryReason fallbacks, pagination and streaming cost, save-result fetching, expensive graph writes - without changing concurrency or cache semantics. Use when the user reports too many SQL statements, slow pages or slow saves in Jimmer code with evidence. Not for a single missing N+1 fetch shape (jimmer-fetchers) or exceptions (jimmer-debug).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: guide
---

# Jimmer Performance

Start with a representative operation, its SQL log/QueryReason, row count and latency. Change the demonstrated bottleneck, then compare the same operation and dialect.

- Request the needed save-result Fetcher/View instead of an unconditional save-then-find. A rejected `INSERT_IF_ABSENT` root is an exception: it does not return the conflicting row; use `isAccepted` and an explicit lookup if required.
- Native upsert, JDBC batching and DML returning are conditional optimizations, not guarantees of one SQL statement for every graph. Requested result fields can add a fake update or follow-up select; request only what the caller uses.
- `TRANSACTION_ONLY` and `BOTH` are deprecated. Move cache delivery to actual CDC with `BINLOG_ONLY`; do not remove invalidation just to reduce queries.
- Use slice/exists when the product does not need an exact total. Use stable ordering and bound graph fan-out.
- An unloaded association throws; it does not lazily query like a JPA proxy. N+1 usually comes from repeated explicit calls or per-ID resolver/interceptor queries.
- Do not replace an interceptor requiring original state, optimistic locking, or a visibility rule merely to obtain fewer statements.

## Reference Routing

[Domain guide](GUIDE.md): read the section relevant to the behavior being changed, not the whole file by default. Existing scripts and relative resources remain beside this skill.

- Save results and acceptance
- Upsert cost ladder (cheapest first)
- Atomic column math — assignment expressions
- Bulk update returning
- QueryReason audit
- Deletes under triggers/cache
- Pagination
- N+1 and fetch strategy
- Selections with subqueries
- Checklist before "done"
