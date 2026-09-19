plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
    alias(quarkusLibs.plugins.quarkus)
}

val forkVersion = providers.gradleProperty("forkVersion").get()
val fixture = file("../project/jimmer-quarkus/integration-tests/src")

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)

sourceSets {
    main {
        java.setSrcDirs(listOf(fixture.resolve("main/java")))
        resources.setSrcDirs(listOf(fixture.resolve("main/resources")))
    }
    test {
        java.setSrcDirs(listOf(fixture.resolve("test/java")))
        resources.setSrcDirs(listOf(fixture.resolve("test/resources")))
    }
}
kotlin.sourceSets.test { kotlin.setSrcDirs(listOf(fixture.resolve("test/kotlin"))) }
val syncDtos by tasks.registering(Sync::class) {
    from(fixture.resolve("main/dto"))
    into(layout.projectDirectory.dir("src/main/dto"))
}
tasks.compileJava { dependsOn(syncDtos) }

configurations.configureEach {
    exclude(group = "io.quarkus", module = "quarkus-devservices-h2")
    exclude(group = "io.quarkus", module = "quarkus-jdbc-h2-deployment")
    exclude(group = "com.h2database", module = "h2")
}

dependencies {
    implementation(enforcedPlatform(quarkusLibs.quarkus.bom))
    implementation("com.github.sleepkqq.jimmer:quarkus-jimmer:$forkVersion")
    annotationProcessor("com.github.sleepkqq.jimmer:jimmer-apt:$forkVersion")
    kspTest("com.github.sleepkqq.jimmer:jimmer-ksp:$forkVersion")
    implementation(quarkusLibs.quarkus.rest)
    implementation(quarkusLibs.quarkus.rest.jackson)
    implementation(quarkusLibs.quarkus.config.yaml)
    implementation(quarkusLibs.quarkus.vertx)
    implementation(quarkusLibs.quarkus.redis.client)
    implementation(quarkusLibs.quarkus.caffeine)
    runtimeOnly(quarkusLibs.quarkus.jdbc.postgresql)
    testImplementation(quarkusLibs.quarkus.junit5)
    testImplementation(quarkusLibs.rest.assured)
    testImplementation(quarkusLibs.testcontainers)
    testImplementation(quarkusLibs.testcontainers.junit)
    testImplementation(quarkusLibs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}

tasks.register("verifyForkClasspath") {
    doLast {
        listOf("runtimeClasspath", "annotationProcessor", "kspTestKotlinProcessorClasspath").forEach { name ->
            configurations[name].resolvedConfiguration.resolvedArtifacts.forEach {
                val id = it.moduleVersion.id
                check(id.group != "org.babyfish.jimmer") { "Upstream dependency leaked: $id" }
                if (id.group == "com.github.sleepkqq.jimmer") {
                    check(id.version == forkVersion) { "Mixed fork versions: $id" }
                }
            }
        }
    }
}
tasks.check { dependsOn("verifyForkClasspath") }
