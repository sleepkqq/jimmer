plugins {
    application
}

repositories {
    mavenCentral()
}

val jimmerVersion = providers.gradleProperty("jimmerVersion").orElse("0.12.2")
dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql:${jimmerVersion.get()}")
    annotationProcessor("org.babyfish.jimmer:jimmer-apt:${jimmerVersion.get()}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
    runtimeOnly("com.h2database:h2:2.4.240")
}

application {
    mainClass.set("example.Smoke")
}
tasks.compileJava {
    options.release.set(21)
    inputs.dir("src/main/dto")
}
