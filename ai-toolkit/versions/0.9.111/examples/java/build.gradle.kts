plugins { application }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql:0.9.111")
    annotationProcessor("org.babyfish.jimmer:jimmer-apt:0.9.111")
    compileOnly("org.babyfish.jimmer:jimmer-spring-boot-starter:0.9.111")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    runtimeOnly("com.h2database:h2:2.3.232")
}

sourceSets.main { resources.srcDir("../schema") }
tasks.compileJava { inputs.dir("src/main/dto") }
application { mainClass.set("example.Examples") }
