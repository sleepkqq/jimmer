pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		mavenLocal()
	}
}

rootProject.name = "quarkus-jimmer-extension"

include("runtime")
include("deployment")
include("integration-tests")

project(":runtime").name = "quarkus-jimmer"
project(":deployment").name = "quarkus-jimmer-deployment"
