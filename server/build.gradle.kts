import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot")
    id("com.diffplug.spotless")
}

group = "ai.pleos.assistant"

version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
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
