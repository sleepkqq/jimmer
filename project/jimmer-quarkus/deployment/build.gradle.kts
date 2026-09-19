dependencies {
	implementation(platform(quarkusLibs.quarkus.bom))

	implementation(quarkusLibs.quarkus.arc.deployment)
	implementation(quarkusLibs.quarkus.agroal.deployment)
	implementation(quarkusLibs.quarkus.narayana.jta.deployment)
	implementation(quarkusLibs.quarkus.quartz.deployment)
	implementation(quarkusLibs.quarkus.rest.deployment)
	implementation(quarkusLibs.quarkus.rest.client.jackson.deployment)
	implementation(quarkusLibs.quarkus.jackson.spi)

	compileOnly(quarkusLibs.quarkus.redis.client.deployment)
	compileOnly(quarkusLibs.quarkus.caffeine.deployment)

	implementation(projects.jimmerQuarkus.quarkusJimmer)

	annotationProcessor(quarkusLibs.quarkus.extension.processor)

	testImplementation(quarkusLibs.quarkus.junit5.internal)
}
