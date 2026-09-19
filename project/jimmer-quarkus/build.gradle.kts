plugins {
	alias(quarkusLibs.plugins.quarkus.extension) apply false
	alias(quarkusLibs.plugins.quarkus) apply false
}

subprojects {
	apply(plugin = "org.jetbrains.kotlin.jvm")

	configure<JavaPluginExtension> {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
		withSourcesJar()
	}

	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
		compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
		testLogging {
			events("passed", "skipped", "failed")
			exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		}
	}

	if (name != "integration-tests") {
		apply(plugin = "publish-convention")
	}
}
