# Jimmer Docs MCP

Source-grounded retrieval from the official
[`babyfish-ct/jimmer-doc`](https://github.com/babyfish-ct/jimmer-doc) and
[`babyfish-ct/jimmer`](https://github.com/babyfish-ct/jimmer) repositories.
Requires Node.js 18+, npm, and Git 2.29+.

## Tools

### `jimmer_docs_search`

Search **English keywords or exact API identifiers** in documentation and FAQ
sections, including shared MDX fragments. Translate questions in other languages
to English before calling. Parameters:

- `query`: nonempty text, up to 500 characters.
- `limit`: maximum distinct source documents, integer 1–5, default 3.
- `ref`: documentation branch, tag, or full commit SHA, default `main`.
- `refresh`: check upstream immediately, default false.

Results contain short previews, up to three matching sections per document,
source line ranges, imports, immutable GitHub URLs, and ready-to-use `read`
arguments. Previews are explicitly incomplete; read the relevant section before
using its examples. The page limit is applied after deduplication.

### `jimmer_docs_read`

Read unchanged source with bounded, lossless continuation:

- `path`: exact repository-relative path returned by search/lookup or an import.
- `source`: `docs` (default) or `jimmer`.
- `ref`: branch, tag, or commit. **Pass the search result's full `revision`**.
- `startLine`: 1-based line, default 1.
- `maxLines`: integer 1–300, default 120.
- `refresh`: check a moving ref immediately, default false.

Each response includes `content`, actual line range, total lines, and `nextRead`
arguments, or null at EOF. Follow `nextRead` to continue at the **same commit**.
Content is limited to 24,000 characters per response at whole-line boundaries;
no ellipsis is substituted for source. Extremely long individual lines produce
an explicit error with the source URL available from search. Files over 2 MiB
are rejected. Concatenating all `content` fields reproduces the original text,
including CRLF, Unicode, generics and operators.

MDX is returned as source, not executed. Java/Kotlin tabs, code fences, links,
admonitions and imports remain intact. Repository-local imports are resolved to
paths when possible; read those separately. Theme imports and unresolved dynamic
components are explicit, not silently replaced with guessed rendered content.
Shared fragments have GitHub source links but no invented standalone website URL.

### `jimmer_source_lookup`

Find production Java/Kotlin files in official Jimmer by `symbol` (simple type
name, qualified class name, or filename), with `limit`, `ref`, and `refresh`.
Use a **release tag matching the application** to verify version-sensitive APIs;
`main` can include unreleased changes. Read matches with `jimmer_docs_read` using
the returned `read` arguments. This is a filename/type lookup, not method-body
search. For a method, start with its declaring type.

Documentation refs belong to the documentation repository; do not assume they
have the same release tags as Jimmer. Forks, third-party integrations, and
application-specific code are outside this server's source scope.

## Freshness and provenance

Every successful response identifies the repository, requested ref, exact
`revision`, commit date, and `checkedAt` (last successful upstream check).
Documentation can lag library releases even when its repository is current;
the source lookup provides an independent check, not an automatic claim that
all documentation matches the application's version.

- Moving refs are refreshed at most once an hour unless `refresh=true`.
- Git downloads shallow snapshots into an owned **bare** cache, never a checkout
  in the user's project. No documentation code or hooks are executed.
- Concurrent requests in a process share a load. Failed initial loads retry on
  the next request rather than retaining a rejected promise.
- If refresh fails, a persisted snapshot remains readable with `stale=true`,
  its original `checkedAt`, and an explicit warning. Automatic retry is after
  30 seconds; `refresh=true` bypasses that backoff.
- A cached full SHA needs no upstream check. Its `checkedAt` is null: a pinned
  historical revision is not a claim about the latest upstream state.
- Operational errors have MCP `isError=true`. No lexical match is a successful
  empty result with guidance for reformulating the query.

Cache location: `$JIMMER_DOCS_CACHE_DIR`, otherwise
`${XDG_CACHE_HOME:-~/.cache}/jimmer-docs-mcp`. First access requires network
access to GitHub; later queries use the cached snapshot. Removing the owned
cache forces a fresh download on the next start.

## Installation

From the toolkit root:

```bash
./install.sh --mcp
```

Or build this directory with `npm ci && npm run bundle` and register:

```json
{
  "mcpServers": {
    "jimmer-docs": {
      "type": "stdio",
      "command": "node",
      "args": ["/absolute/path/to/jimmer-ai-toolkit/mcp/jimmer-docs-mcp/dist/bundle.js"]
    }
  }
}
```

Reconnect an existing MCP process after rebuilding to discover all three tools.
The existing `jimmer_docs_search(query, limit)` call remains valid, but its output
now contains section previews and read instructions instead of clipped HTML
pages. Responses are JSON text so partial source ranges cannot break the outer
Markdown formatting.

## Verification

```bash
npm test          # TypeScript + production bundle + offline Git/stdio regression checks
npm run test:live # Explicit GitHub network check, frozen queries, readback and source lookup
```

The live check uses the original 26 audit queries plus four mapping holdouts
written before the first repository-backed run. The original queries and their
expected paths are unchanged. One holdout had an erroneous expected URL (404);
its correction and original value are retained in `retrieval-cases.json`.
It reports target-page ranks
without treating them as a universal relevance score, verifies result URLs,
reconstructs three long documents against raw upstream text, and checks
deprecated trigger types at a pinned library release. Network failures fail the
check; ranking misses remain visible in the report.

Optional live-check environment variables: `JIMMER_DOCS_EVAL_REF`,
`JIMMER_SOURCE_EVAL_REF`, and `JIMMER_MCP_EVAL_OUTPUT` (JSON report path).

### Measured on 2026-09-20

Documentation commit: `5f3ad502fd64faca7695a8073e55c170dd4fd53e`.
Source-check commit: `871c49379149c1950a836bb11b59d30a0babd476`.

| Diagnostic | Previous HTML implementation | Repository implementation |
|---|---:|---:|
| Expected page at rank 1, original 20 English queries | 15/20 | 18/20 |
| Expected page within first 3, same queries | 19/20 | 19/20 |
| Broken links in returned URL sets checked | 42/42 | 0/49 |
| Complete read with continuation | Unavailable | Exact reconstruction of 3 long documents |

The readback comparison covered 89,619 characters across 32 responses, including
Java/Kotlin examples and late sections. Four additional mapping queries put the
expected page first; one expected-path typo was corrected as disclosed above.
All six negative/language/source-boundary controls returned empty results with
guidance. Six offline regression checks cover MDX fidelity, ranking structure,
cache refresh/recovery, immutable reads, ownership/path protections, and real
stdio tool calls.

`cache invalidation external writes` still misses the designated consistency
page in the first three results; it retrieves cache-type pages. This is an
explicit lexical-retrieval limitation, retained rather than patched with
query-specific aliases. The first repository run also exposed missing parent
topic context in ranking; adding document breadcrumbs corrected that general
issue. This small diagnostic set does not establish universal semantic recall
or measure downstream model-answer quality.
