---
name: jimmer-config
description: Verify Spring and standalone Java/Kotlin configuration keys, defaults, processor alignment and trigger policy.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Configuration

Check resolved runtime **and** processor coordinates before changing a key. Official artifacts use `org.babyfish.jimmer`, aligned at the release version (`<release-version>` in the snippets below). Spring properties use `jimmer.*`; standalone client builders do not consume Spring YAML.

Java Gradle dependencies (in a Java project):

```kotlin
dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql:<release-version>")
    annotationProcessor("org.babyfish.jimmer:jimmer-apt:<release-version>")
}
```

Kotlin Gradle dependencies (with compatible Kotlin/KSP plugins):

```kotlin
dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:<release-version>")
    ksp("org.babyfish.jimmer:jimmer-ksp:<release-version>")
}
```

In Spring use `jimmer-spring-boot-starter:<release-version>`, retain the matching processor, and add `jimmer-sql-kotlin` for Kotlin DSL use. Kotlin and KSP plugin versions must match each other; Jimmer's version is not the KSP plugin version. Runtime and annotation/KSP processors have separate dependency configurations; inspect both.

```yaml
jimmer:
  language: kotlin  # java for Java entities
  show-sql: true
  pretty-sql: true
  database-validation-mode: ERROR
  trigger-type: BINLOG_ONLY
```

Logging is for a suitable environment; avoid leaking values into production logs. Schema validation checks migrated schema and does not apply migrations. `BINLOG_ONLY` requires actual event delivery when event-based cache consistency is expected.

Read [GUIDE.md](GUIDE.md) for source-verified keys. Verify by starting the actual framework context and inspecting effective client configuration, not merely YAML parsing.
