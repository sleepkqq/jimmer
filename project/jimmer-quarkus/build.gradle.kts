plugins {
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.quarkus.extension) apply false
	alias(libs.plugins.quarkus) apply false
}

subprojects {
	group = "com.github.sleepkqq.quarkus-jimmer-extension"
	version = findProperty("version") as String? ?: "0.0.0-SNAPSHOT"

	apply(plugin = "org.jetbrains.kotlin.jvm")

	repositories {
		mavenCentral()
		mavenLocal()
	}

	configure<JavaPluginExtension> {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
		withSourcesJar()
	}

	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
		}
	}

	tasks.withType<Test> {
		useJUnitPlatform()
		testLogging {
			events("passed", "skipped", "failed")
			showExceptions = true
			showCauses = true
			showStackTraces = true
			exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		}
	}
}

tasks.register("syncReadme") {
	group = "documentation"
	description = "Sync library versions in README.md from gradle/libs.versions.toml"

	val catalog = layout.projectDirectory.file("gradle/libs.versions.toml").asFile
	val readme = layout.projectDirectory.file("README.md").asFile
	val extensionVersionProperty = findProperty("version") as String?

	inputs.file(catalog)
	inputs.property("extensionVersion", extensionVersionProperty ?: "")
	outputs.file(readme)
	outputs.upToDateWhen { false }

	doLast {
		val versions = catalog.readLines()
			.dropWhile { it.trim() != "[versions]" }.drop(1)
			.takeWhile { !it.trim().startsWith("[") }
			.mapNotNull { line ->
				Regex("""^\s*([\w-]+)\s*=\s*"([^"]+)"\s*$""").find(line)
					?.let { it.groupValues[1] to it.groupValues[2] }
			}.toMap()

		val jimmer = requireNotNull(versions["jimmer"]) { "Missing 'jimmer' version in catalog" }
		val quarkus = requireNotNull(versions["quarkus"]) { "Missing 'quarkus' version in catalog" }
		val kotlin = requireNotNull(versions["kotlin"]) { "Missing 'kotlin' version in catalog" }

		var text = readme.readText()

		// Pin Jimmer codegen versions inside dependency snippets (anchored to artifact ids → safe)
		text = text.replace(Regex("""jimmer-apt:[\d.]+"""), "jimmer-apt:$jimmer")
		text = text.replace(Regex("""jimmer-ksp:[\d.]+"""), "jimmer-ksp:$jimmer")

		// On a release build (-Pversion=1.x.y) also pin the extension coordinate
		val realExtensionVersion = extensionVersionProperty
			?.takeIf { Regex("""^\d+\.\d+\.\d+$""").matches(it) }
		if (realExtensionVersion != null) {
			text = text.replace(Regex("""quarkus-jimmer:[\d.]+"""), "quarkus-jimmer:$realExtensionVersion")
		}

		// Regenerate the compatibility table row between the markers, preserving the extension cell
		val block = Regex("""<!-- versions:start -->\R([\s\S]*?)<!-- versions:end -->""")
		val current = block.find(text)?.groupValues?.get(1).orEmpty()
		val existingExtension = Regex("""\|\s*`([^`]+)`\s*\|""").find(current)?.groupValues?.get(1)
		val extension = realExtensionVersion ?: existingExtension ?: "1.5.1"

		val table = buildString {
			appendLine("<!-- versions:start -->")
			appendLine("| Extension | Quarkus  | Jimmer    | Kotlin  | JDK  |")
			appendLine("|-----------|----------|-----------|---------|------|")
			appendLine("| `$extension`   | `$quarkus` | `$jimmer` | `$kotlin` | `21` |")
			append("<!-- versions:end -->")
		}
		text = block.replace(text, Regex.escapeReplacement(table))

		readme.writeText(text)
		logger.lifecycle("README.md synced: extension=$extension quarkus=$quarkus jimmer=$jimmer kotlin=$kotlin")
	}
}

configure(subprojects.filter { it.name != "integration-tests" }) {
	apply(plugin = "maven-publish")

	configure<PublishingExtension> {
		publications {
			create<MavenPublication>("maven") {
				from(components["java"])

				artifactId = project.name

				pom {
					name.set(project.name)
					description.set("Quarkus extension for Jimmer ORM")
					url.set("https://github.com/sleepkqq/quarkus-jimmer-extension")

					licenses {
						license {
							name.set("Apache License 2.0")
							url.set("https://www.apache.org/licenses/LICENSE-2.0")
						}
					}

					scm {
						url.set("https://github.com/sleepkqq/quarkus-jimmer-extension")
					}
				}
			}
		}
	}
}
