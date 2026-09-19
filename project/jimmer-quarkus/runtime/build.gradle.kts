plugins {
	`java-library`
	alias(libs.plugins.quarkus.extension)
}

quarkusExtension {
	deploymentModule.set("quarkus-jimmer-deployment")
}

dependencies {
	api(platform(libs.quarkus.bom))

	api(libs.quarkus.arc)
	api(libs.quarkus.agroal)
	api(libs.quarkus.narayana.jta)
	api(libs.quarkus.quartz)
	api(libs.quarkus.rest)
	api(libs.quarkus.rest.client.jackson)

	api(libs.kotlin.stdlib)

	api(libs.jimmer.sql)
	api(libs.jimmer.sql.kotlin)
	api(libs.jimmer.client)
	api(libs.jimmer.client.swagger)

	api(libs.java.uuid.generator)

	compileOnly(libs.quarkus.redis.client)
	compileOnly(libs.quarkus.caffeine)
	compileOnly(libs.graalvm.nativeimage)

	annotationProcessor(libs.quarkus.extension.processor)
}
