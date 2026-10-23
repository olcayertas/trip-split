plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val vectorsDir = rootProject.projectDir.resolve("../protocol/vectors")

tasks.test {
    useJUnitPlatform()
    // The vectors live outside the module, so declare them as an input. Without this, Gradle marks the
    // test task UP-TO-DATE after a vector change and runs nothing.
    inputs.dir(vectorsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("vectors")
    systemProperty("vectors.dir", vectorsDir.canonicalPath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
