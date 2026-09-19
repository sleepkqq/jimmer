plugins {
	`java-library`
	alias(quarkusLibs.plugins.quarkus.extension)
}

quarkusExtension {
	deploymentModule.set("quarkus-jimmer-deployment")
}

dependencies {
	api(platform(quarkusLibs.quarkus.bom))

	api(quarkusLibs.quarkus.arc)
	api(quarkusLibs.quarkus.agroal)
	api(quarkusLibs.quarkus.narayana.jta)
	api(quarkusLibs.quarkus.quartz)
	api(quarkusLibs.quarkus.rest)
	api(quarkusLibs.quarkus.rest.client.jackson)

	api(libs.kotlin.stdlib)

	api(projects.jimmerSql)
	api(projects.jimmerSqlKotlin)
	api(projects.jimmerClient)
	api(projects.jimmerClientSwagger)

	api(quarkusLibs.java.uuid.generator)

	compileOnly(quarkusLibs.quarkus.redis.client)
	compileOnly(quarkusLibs.quarkus.caffeine)
	compileOnly(quarkusLibs.graalvm.nativeimage)

	annotationProcessor(quarkusLibs.quarkus.extension.processor)
}
