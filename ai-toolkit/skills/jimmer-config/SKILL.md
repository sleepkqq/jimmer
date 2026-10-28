---
name: jimmer-config
description: Finds and verifies Jimmer configuration keys, defaults and build-time versus runtime scope for Spring Boot and the sleepkqq/jimmer Quarkus integration, and diagnoses ignored keys. Use when the user asks which jimmer.* property controls something (show-sql, dialect, batch size, triggers) or why a setting has no effect. Not for application config unrelated to Jimmer.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Configuration Keys

1. Identify the resolved runtime and framework integration. Quarkus here means `sleepkqq/jimmer/project/jimmer-quarkus`.
2. Find the option in that integration's config class and check its default, datasource scope and build-time/runtime phase. A SQL-client builder method does not prove that a framework exposes a matching property.
3. Change the smallest relevant key; verify effective behavior on startup or with an SQL/cache test. A build-time key requires rebuilding the application.

## High-impact differences

- New trigger configurations use `BINLOG_ONLY`; `TRANSACTION_ONLY` and `BOTH` are deprecated. CDC must actually deliver changes before event-driven caching is safe.
- Quarkus constraint defaults: `constraint-violation-translatable=false`, `sql-state-exception-translator=true`. Do not import Spring's default into this module.
- Quarkus reads only `database-validation-mode`; the deprecated nested `database-validation.mode` is accepted but ignored at runtime (the fork README table is stale on this).
- Only datasource-scoped keys accept a datasource name. `language`, cache configuration and validation are not blindly replicated under each datasource.
- `quarkus.jimmer.cache.guard.enabled` requires one application-owned `CacheReadiness`; it does not install CDC.
- Fork caches: `entities[].content-only` is the only admission-related key; `content-fields` was removed in 1.1.12, and declaring an entity cache already admits hint-free DTO/View root queries.

## Reference Routing

[Key guide](GUIDE.md): read only the matching section. Defaults are reference points; installed configuration source wins.

- Core
- SQL logging
- Validation and triggers
- Query and fetching
- Save commands
- Entity cache (Q) — `quarkus.jimmer.cache.*`
- Error translation and generated clients
