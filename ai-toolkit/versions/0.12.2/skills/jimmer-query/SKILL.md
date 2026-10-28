---
name: jimmer-query
description: Write Jimmer typed queries with dynamic predicates, collection filters, stable pagination, aggregates, base tables/CTEs and cursor streaming; diagnose duplicates or unexpected joins.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Query

1. Identify result shape, filters, cardinality, ordering and whether an exact total is needed. Reuse an existing repository method when it expresses the whole operation.
2. Use a Fetcher/View for the read graph. A to-many join can multiply root rows: for “has matching child” prefer an EXISTS/implicit-subquery predicate; verify page/count behavior rather than adding joins blindly.
3. Use `fetchPage` for an exact total, `fetchSlice` for has-more, `exists()` for existence. Pagination needs deterministic order with an ID tie-breaker.
4. Use `stream()` only for a stream-compatible shape; close it within the connection/transaction lifetime. Fetcher associations requiring later secondary queries cannot stream.
5. Compile the affected method and inspect representative SQL, bindings and result cardinality. Use the installed DSL, not raw SQL interpolation.

For global visibility use `jimmer-filters`. For query-derived `createInsert`/`createUpsert` use `jimmer-dml`; for object graph writes use `jimmer-save-modes`.

## Reference Routing

[Query guide](GUIDE.md): load Java syntax, base-table or streaming details only when needed. Bundled scan/compile scripts are relative to this skill, not the target working directory.

- Workflow
- Approach Table
- Java DSL Rules
- Base Tables (window functions, column reuse)
- Typed Tuple Rule
- Kotlin Note
