pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jimmer-published-consumer"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(providers.gradleProperty("forkRepository").getOrElse("https://jitpack.io"))
                }
            }
            filter { includeGroup("com.github.sleepkqq.jimmer") }
        }
    }
    versionCatalogs {
        create("quarkusLibs") {
            from(files("../project/jimmer-quarkus/gradle/libs.versions.toml"))
        }
    }
}
