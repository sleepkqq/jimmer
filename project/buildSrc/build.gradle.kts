plugins {
    `kotlin-dsl`
}

dependencies {
    // buildSrc is a parent classloader of Quarkus' plugins; align Dokka's Jackson with them.
    api(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    api("org.jetbrains.dokka:dokka-gradle-plugin:2.1.0")
    api("com.vanniktech:gradle-maven-publish-plugin:0.33.0")
}
