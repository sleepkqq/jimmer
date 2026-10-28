plugins {
    application
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xjvm-default=all") }
}

dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:0.9.111")
    ksp("org.babyfish.jimmer:jimmer-ksp:0.9.111")
    compileOnly("org.babyfish.jimmer:jimmer-spring-boot-starter:0.9.111")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    runtimeOnly("com.h2database:h2:2.3.232")
}

sourceSets.main { resources.srcDir("../schema") }
tasks.matching { it.name == "kspKotlin" }.configureEach { inputs.dir("src/main/dto") }
application { mainClass.set("example.ExamplesKt") }
