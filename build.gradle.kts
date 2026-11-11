plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.result4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
// The measuring runs (compare, slowConsumer, wireBytes) are tasks of their own and never part of check.
val entryPoints = mapOf(
    "serve" to "io.plainround.seatwave.live.ServeCli",
    "compare" to "io.plainround.seatwave.live.bench.ComparisonCli",
    "slowConsumer" to "io.plainround.seatwave.live.bench.SlowConsumerCli",
    "wireBytes" to "io.plainround.seatwave.live.bench.WireBytesCli",
    "pollingRates" to "io.plainround.seatwave.live.bench.PollingRatesCli",
    "sseResume" to "io.plainround.seatwave.live.bench.SseResumeCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
    }
}
