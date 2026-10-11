plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
    // Gradle does not forward -D properties to the test JVM, so pass the live shim
    // endpoint across explicitly. Absent, the live parity check skips itself.
    providers.systemProperty("chuse.shim").orNull?.let { systemProperty("chuse.shim", it) }
    testLogging { events("passed", "skipped", "failed") }
}