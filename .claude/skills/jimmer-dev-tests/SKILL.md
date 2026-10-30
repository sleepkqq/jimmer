---
name: jimmer-dev-tests
description: Write, place and run tests in the Jimmer fork using the real Java/Kotlin harnesses, fixtures and native-database switch. Use when the owner says "напиши тест", "регрессионный тест", "как запустить тесты jimmer", or asks where a regression belongs. Not for fixing the production defect itself (jimmer-dev-change) or reviewing a diff (jimmer-dev-review).
---

# Tests (Jimmer fork)

Everything runs from `project/`. The build uses JDK 25 and Gradle 9.8.2; core modules still
compile as Java 8.

| Module | Framework | Command |
|---|---|---|
| `jimmer-core` | JUnit 5 | `:jimmer-core:test` |
| `jimmer-core-kotlin` | JUnit 4 (`useJUnit()`, `kotlin.test`) | `:jimmer-core-kotlin:test` |
| `jimmer-sql` | JUnit 5 (`java-convention`, JUnit 5.13.4) | `:jimmer-sql:test` |
| `jimmer-sql-kotlin` | JUnit 4 (`useJUnit()`, `kotlin.test`) | `:jimmer-sql-kotlin:test` |
| `jimmer-dto-compiler` | JUnit 5 | `:jimmer-dto-compiler:test` |
| `jimmer-ksp` | JUnit 4 (`useJUnit()`) | `:jimmer-ksp:test` |
| `jimmer-ddl-compiler` | JUnit 4 | `:jimmer-ddl-compiler:test` |
| Quarkus extension | JUnit 5 | `:jimmer-quarkus:integration-tests:test` |

`jimmer-apt` has no test source set: Java generation is exercised by compiling
`jimmer-sql-test/jimmer-sql-test-model`, and the shared logic by `jimmer-dto-compiler`.

## Java harness

`project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/common/`:

- `AbstractTest` — H2 client, executed-SQL recorder (`getExecutions()`, `clearExecutions()`),
  `jdbc(Consumer<Connection>)`, `testConnectionManager()`, `setAutoIds(...)`.
- `AbstractQueryTest` — `executeAndExpect(query, ctx -> …)` when the case needs no setup;
  `connectAndExpect(con -> query.execute(con), ctx -> …)` / `anyAndExpect` when it needs a
  connection; `assertLoadState`, `expect(json, obj)`.
- `AbstractMutationTest` — `executeAndExpectResult(command, ctx -> …)`,
  `executeAndExpectRowCount(executable, ctx -> …)`, `connectAndExpect`, `resetIdentity`.

`QueryTestContext` DSL: `statement(i)` selects a recorded statement, `sql(...)` compares the SQL
verbatim (`--->` is stripped), `variables(...)` / `batchVariables(i, ...)`, `rows(json)`,
`rows(count)`, `rows(List)`, `row(i, json)`. Statement count is asserted automatically. For
multi-statement queries call `ctx.statement(1).sql(...)`.

Mutation DSL: `ctx.statement { it.sql(...); it.variables(...) }`,
`ctx.totalRowCount(n)`, `ctx.rowCount(AffectedTable.of(BookStore.class), n)`,
`ctx.entity { it.original(json); it.modified(json) }`, and
`ctx.throwable { it.type(IllegalArgumentException.class); it.message(...) }`.

Real excerpt — `project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/query/SubQueryGlobalFilterTest.java`:

```java
connectAndExpect(con -> {
    deleteDepartment(con);
    return getLambdaClient().createQuery(EmployeeTable.class, (q, employee) -> q
            .where(employee.id().eq(1L))
            .select(getLambdaClient().createSubQuery(q, EmployeeTable.class, (sq, target) -> sq
                    .where(target.id().eq(employee.id()))
                    .whereIf(usedInWhere, target.department(JoinType.LEFT).name().isNotNull())
                    .select(target.department(JoinType.LEFT).name()))))
            .execute(con);
}, ctx -> {
    ctx.sql("select (select tb_3_.NAME from EMPLOYEE tb_2_ …");
    ctx.variables(0L, 0L, 1L, 0L);
    ctx.rows("[null]");
});
```

`ctx.variables(...)` is compared in order; use `new Object[]{...}` for array parameters.

## Kotlin harness

`project/jimmer-sql-kotlin/src/test/kotlin/org/babyfish/jimmer/sql/kt/common/` mirrors the Java
one with receiver lambdas:

```kotlin
executeAndExpect(
    sqlClient.createQuery(BookStore::class) {
        select(case(table.name).match("O'REILLY", "O").match("MANNING", "M").otherwise("-"))
    }
) {
    sql("""select --->case tb_1_.NAME … from BOOK_STORE tb_1_""".trimMargin())
    variables("O'REILLY", "O", "MANNING", "M", "-")
    rows("""["M","O"]""")
}
```
(`ComplexExprTest.testSimpleCase`, abridged SQL.) Mutation:
`executeAndExpectRowCount({ con -> … }) { statement { sql(...); variables(...) } }`,
`executeAndExpectResult`, `rowCount(Book::class, n)`, `entity { modified(json) }`. `contentEquals`
compares JSON semantically; `expectJson` normalizes `--->` and line breaks for string form.

## Fixtures, DTOs, regeneration

- Java entities/DTOs: `project/jimmer-sql-test/jimmer-sql-test-model/src/main/java` and
  `src/main/dto`; the bundle trigger is `src/main/dto-bundle` (`META-INF/jimmer/dto-bundle.properties`).
- Kotlin entities/DTOs: `project/jimmer-sql-test/jimmer-sql-test-model-kotlin/src/main/kotlin`,
  `src/main/dto`, `src/main/dto-bundle`.
- Add a new entity for new behaviour; do not distort a shared one used by other tests.
- Generated APT code: `build/generated/sources/annotationProcessor/java/main/…`; KSP:
  `build/generated/ksp/main/kotlin` (tests: `build/generated/ksp/test/kotlin`).
- Regenerate with `./gradlew :jimmer-sql-test:jimmer-sql-test-model:classes` or
  `:jimmer-sql-test:jimmer-sql-test-model-kotlin:classes`; add `clean` for a full rebuild.

H2 schema is `project/jimmer-sql/src/test/resources/database.sql`, loaded once by `AbstractTest`
into `jdbc:h2:mem:jimmer_test_db;…` (Kotlin uses `jimmer_kt_test_db`). A new table must also be
added to `database-mysql.sql`, `database-postgres.sql`, `database-sqlite.sql` and
`database-sqlserver.sql` if native cases use it; `docker-compose.yml` mounts them as container
init scripts.

## Native databases

The switch is the environment variable `jimmer-sql-test-native-database`, read by
`System.getenv` in `common/NativeDatabases.java`, `kt/common/NativeDatabases.kt` and
`jimmer-sql/build.gradle.kts`. The hyphen means use `env` (not `export`):

```bash
docker compose -f jimmer-sql/src/test/resources/docker-compose.yml up -d --wait postgres
env jimmer-sql-test-native-database=postgres \
  ./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.query.InCollectionTest'
```

- Any non-empty value other than `false` enables `assumeNativeDatabase()`; `oracle` and
  `sqlserver` additionally gate their `DataSource`s (`assumeOracleDatabase`,
  `assumeSqlserverDatabase`). CI uses `postgres` and loads `database-postgres.sql`.
- Fixed endpoints/credentials are in `NativeDatabases`: PostgreSQL `localhost:5432/jimmer_test`
  (`root`/`123456`), MySQL `3306`, SQL Server `1433`, SQLite under `build/sqlite*.db`.
- When the variable is set, `tasks.test` forces `maxParallelForks = 1`; otherwise workers are
  parallel. Do not run native suites in parallel.

## Running one case

```bash
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.query.SubQueryGlobalFilterTest'
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.query.SubQueryGlobalFilterTest.testUserFilterOnSelectionJoin'
./gradlew :jimmer-sql-kotlin:test --tests '*AssociationQueryTest'
./gradlew :jimmer-dto-compiler:test --tests 'org.babyfish.jimmer.dto.compiler.DtoCompilerTest'
./gradlew :jimmer-ksp:test --tests 'org.babyfish.jimmer.ksp.dto.DtoGeneratorTest'
```

Prefer one class plus the neighbouring classes the change can reach. At most two heavy Gradle
runs at a time on the host.

## Processor tests

- DTO compiler: extend `DtoCompilerTest` (`jimmer-dto-compiler`). It builds source text in memory,
  so add the failing DTO as a string, not a file.
- KSP: extend `DtoGeneratorTest` / `ListIdentityTest` in `org.babyfish.jimmer.ksp.dto`. They use
  `KotlinCompilation` (`createCompilation()` in `ksp/dto/AbstractTest.kt`) and require the Gradle
  `jimmer.test.jvmTarget` system property — run through Gradle only.
- APT↔KSP parity: the same DTO/entity shape needs a case on both sides where both processors
  implement it.

## Combinatorial error matrices

For parser/compiler error paths, nest every boolean dimension and assert the full message plus
line/column, passing the offending source as the assertion message — `DtoCompilerTest`:

```java
for (boolean nested : new boolean[] {false, true}) {
    for (boolean aliasGroup : new boolean[] {false, true}) {
        for (boolean userPropFirst : new boolean[] {false, true}) {
            …
            DtoAstException ex = Assertions.assertThrows(DtoAstException.class,
                    () -> MyDtoCompiler.bookStore(code), code);
            Assertions.assertEquals(line, ex.getLineNumber(), code);
            Assertions.assertEquals(indent.length(), ex.getColNumber(), code);
```

## Quarkus integration tests

`:jimmer-quarkus:integration-tests:test` starts `IntegrationTestResources`: two PostgreSQL
Testcontainers plus Redis, so Docker must be running. `application.yml` disables H2 Dev Services;
the datasources/Redis are overridden by that test resource, not by local services.

## Naming and reporting

- Class named for the behaviour (`DuplicateKeySaveTest`, `DraftIsolationTest`,
  `SubQueryGlobalFilterTest`); method is a sentence (`testUserPropConflictsWithMacroAlias`).
- Place the test in the module that owns the layer; pin SQL verbatim rather than asserting
  fragments.
- Report executed counts and skipped counts separately. Native cases skipped by
  `Assumptions`/`assumeTrue` are **skipped**, never passed; name the database that was not run
  (for example "Native PostgreSQL was not run").
