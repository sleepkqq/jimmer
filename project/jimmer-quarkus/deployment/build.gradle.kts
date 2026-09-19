dependencies {
	implementation(platform(libs.quarkus.bom))

	implementation(libs.quarkus.arc.deployment)
	implementation(libs.quarkus.agroal.deployment)
	implementation(libs.quarkus.narayana.jta.deployment)
	implementation(libs.quarkus.quartz.deployment)
	implementation(libs.quarkus.rest.deployment)
	implementation(libs.quarkus.rest.client.jackson.deployment)
	implementation(libs.quarkus.jackson.spi)

	compileOnly(libs.quarkus.redis.client.deployment)
	compileOnly(libs.quarkus.caffeine.deployment)

	implementation(project(":quarkus-jimmer"))

	annotationProcessor(libs.quarkus.extension.processor)

	testImplementation(libs.quarkus.junit5.internal)
}
