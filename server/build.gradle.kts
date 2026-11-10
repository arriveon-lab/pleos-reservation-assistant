import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot")
    id("com.diffplug.spotless")
}

group = "ai.pleos.assistant"

version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// Isolated JVM client-check dependency; never on server or Spring test runtime classpaths.
val previewClientJson by configurations.creating

dependencies {
    previewClientJson("org.json:json:20240303")
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking { lockAllConfigurations() }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    // The contract checker also consumes this fictional plan when tests are restored from cache.
    outputs.file(layout.buildDirectory.file("rec08/mock-recommendation-plan.json"))
    outputs.file(layout.buildDirectory.file("contract-exchanges/place-preview.json"))
    // These apply to the isolated test JVM, not the Gradle daemon or developer's global settings.
    jvmArgs("-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.redirects.retrylimit=1")
    systemProperty("placeTestClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    testLogging { events("failed", "skipped") }
}

tasks.bootJar { archiveFileName = "reservation-server-${project.version}.jar" }

tasks.jar { enabled = false }

tasks.bootRun { workingDir = rootProject.projectDir }

tasks.register<JavaExec>("placePreview") {
    group = "application"
    description = "Run a loopback-only, one-request place preview; default is MOCK."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ai.pleos.assistant.PlacePreviewDemo"
    workingDir = rootProject.projectDir
    jvmArgs("-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.redirects.retrylimit=1")
    jvmArgs("-Djdk.httpclient.HttpClient.log=")
    args(providers.gradleProperty("placePreviewMode").getOrElse("mock"))
    providers.gradleProperty("placePreviewKeyFile").orNull?.let { args(it) }
}

tasks.register<JavaExec>("recommendationPlan") {
    group = "verification"
    description = "Print a fictional listing shortlist and UNKNOWN inquiry plan; no external calls."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ai.pleos.assistant.RecommendationPlanDemo"
    workingDir = rootProject.projectDir
}

spotless { java { googleJavaFormat("1.28.0") } }

// Local test harness only. This file and test classes are never included in bootJar.
val smokeClasspath by
    tasks.registering {
        dependsOn(tasks.testClasses)
        val destination = layout.buildDirectory.file("smoke-classpath.txt")
        outputs.file(destination)
        inputs.files(sourceSets.test.get().runtimeClasspath)
        doLast { destination.get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
    }

tasks.named("check") { dependsOn(smokeClasspath) }

val previewClientClasspath by
    tasks.registering {
        val destination = layout.buildDirectory.file("preview-client-classpath.txt")
        inputs.files(previewClientJson)
        outputs.file(destination)
        doLast { destination.get().asFile.writeText(previewClientJson.asPath) }
    }

tasks.named("check") { dependsOn(previewClientClasspath) }

// Device acceptance fixtures use real app tokens, never the synthetic smoke-test Principal.
tasks.register<JavaExec>("previewAcceptance") {
    group = "verification"
    description = "Serve an explicit loopback MOCK preview fixture for joint device acceptance."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ai.pleos.assistant.PreviewAcceptanceApplication"
    workingDir = rootProject.projectDir
    args(
        providers.gradleProperty("previewFixture").getOrElse("RESULTS"),
        providers.gradleProperty("previewPort").getOrElse("18082"),
        providers.gradleProperty("previewDatabase").getOrElse("work/rec05-acceptance/auth"),
    )
}

// Explicit development image: real auth plus only these two test-only launch helpers.
tasks.register<Sync>("previewAcceptanceDist") {
    dependsOn(tasks.testClasses)
    into(layout.buildDirectory.dir("preview-acceptance"))
    from(sourceSets.main.get().output) { into("classes") }
    from(sourceSets.test.get().output.classesDirs) {
        include(
            "ai/pleos/assistant/PreviewAcceptanceApplication*.class",
            "ai/pleos/assistant/PreviewAcceptanceHealthCheck.class",
        )
        into("classes")
    }
    from(configurations.testRuntimeClasspath) { into("lib") }
}
