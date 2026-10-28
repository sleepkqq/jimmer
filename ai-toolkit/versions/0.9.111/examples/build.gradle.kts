plugins {
    base
    kotlin("jvm") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}

tasks.check {
    dependsOn(":java:run", ":kotlin:run")
}
