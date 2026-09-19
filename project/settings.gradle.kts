rootProject.name = "jimmer"
include(
    "jimmer-bom",
    "jimmer-core",
    "jimmer-mapstruct-apt",
    "jimmer-apt",
    "jimmer-sql",
    "jimmer-core-kotlin",
    "jimmer-ksp",
    "jimmer-sql-kotlin",
    "jimmer-client",
    "jimmer-spring-boot-starter",
    "jimmer-dto-compiler",
    "jimmer-client-swagger",
    "jimmer-client-scalar",
    "jimmer-ddl-compiler",
    "jimmer-sql-test:jimmer-sql-test-model-base",
    "jimmer-sql-test:jimmer-sql-test-model",
    "jimmer-sql-test:jimmer-sql-test-model-kotlin",
    "jimmer-sql-test:jimmer-sql-test-support",
    "jimmer-quarkus:quarkus-jimmer",
    "jimmer-quarkus:quarkus-jimmer-deployment",
    "jimmer-quarkus:integration-tests",
)

project(":jimmer-quarkus:quarkus-jimmer").projectDir = file("jimmer-quarkus/runtime")
project(":jimmer-quarkus:quarkus-jimmer-deployment").projectDir = file("jimmer-quarkus/deployment")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    versionCatalogs {
        create("quarkusLibs") {
            from(files("jimmer-quarkus/gradle/libs.versions.toml"))
        }
    }
    dependencyResolutionManagement {
        repositories {
            mavenCentral()
        }
    }
}
