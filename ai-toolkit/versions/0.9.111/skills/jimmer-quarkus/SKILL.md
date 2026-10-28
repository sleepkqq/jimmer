---
name: jimmer-quarkus
description: Integrate Jimmer with Quarkus through the application's installed extension; verify its coordinates, CDI/transaction wiring and configuration keys before suggesting APIs.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Quarkus integration boundary

**Quarkus integration comes from a separately versioned extension, not from the Spring starter.** The installed extension is the authority for coordinates, CDI discovery, transactions, native support and configuration keys.

1. Inspect the application's exact Quarkus extension artifact, resolved Jimmer runtime and APT/KSP dependencies. Extension version and Jimmer version are separate.
2. Read that extension's release source for compatibility, CDI discovery, transactions, native support and configuration keys. If it forces a different Jimmer runtime, it cannot establish an exact release-aligned setup.
3. Preserve the installed integration when it matches; otherwise identify the missing decision before adding another extension. Do not invent `org.babyfish.jimmer:quarkus-jimmer:<release-version>`, repository types, UUIDv7 generators, readiness guards or `quarkus.jimmer.*` keys the extension does not define.

## Framework-independent core access

Once the application supplies a correctly integrated client, core queries are the same as elsewhere.

Java:

```java
public List<BookView> books(JSqlClient sqlClient) {
    var b = Tables.BOOK_TABLE;
    return sqlClient.createQuery(b)
        .orderBy(b.id())
        .select(b.fetch(BookView.class))
        .execute();
}
```

Kotlin:

```kotlin
fun books(sqlClient: KSqlClient): List<BookView> =
    sqlClient.createQuery(Book::class) {
        orderBy(table.id)
        select(table.fetch(BookView::class))
    }.execute()
```

These methods do not implement CDI or transaction integration. For custom integration, the connection manager must use the framework-managed datasource/transaction connection; opening independent connections inside a JTA operation breaks atomicity. Keep blocking JDBC on worker threads. Native reflection/metadata, entity discovery and Jackson registration require integration-specific checks in both Java and Kotlin.

Verify an actual Quarkus startup and commit/rollback across multiple statements before claiming integration. For native delivery, run its native integration test. Core APT/KSP compilation cannot prove these properties.

Source boundary: [official module list](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project).
