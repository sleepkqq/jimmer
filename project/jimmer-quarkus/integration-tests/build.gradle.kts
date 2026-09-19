plugins {
	alias(quarkusLibs.plugins.quarkus)
	alias(libs.plugins.ksp)
}

configurations.all {
	exclude(group = "io.quarkus", module = "quarkus-devservices-h2")
	exclude(group = "io.quarkus", module = "quarkus-jdbc-h2-deployment")
	exclude(group = "com.h2database", module = "h2")
}

tasks.test {
	systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
	testLogging {
		showStandardStreams = true
	}
}

dependencies {
	implementation(enforcedPlatform(quarkusLibs.quarkus.bom))

	implementation(quarkusLibs.quarkus.rest)
	implementation(quarkusLibs.quarkus.rest.jackson)
	implementation(quarkusLibs.quarkus.config.yaml)
	implementation(quarkusLibs.quarkus.vertx)
	implementation(quarkusLibs.quarkus.redis.client)
	implementation(quarkusLibs.quarkus.caffeine)

	implementation(projects.jimmerQuarkus.quarkusJimmer)

	runtimeOnly(quarkusLibs.quarkus.jdbc.postgresql)

	annotationProcessor(projects.jimmerApt)
	// KSP is wired for the test source set only — a Kotlin entity under src/main/kotlin
	// would compile with no Jimmer codegen.
	kspTest(projects.jimmerKsp)

	testImplementation(quarkusLibs.quarkus.junit5)
	testImplementation(quarkusLibs.rest.assured)
	testImplementation(quarkusLibs.testcontainers)
	testImplementation(quarkusLibs.testcontainers.junit)
	testImplementation(quarkusLibs.testcontainers.postgresql)
}
