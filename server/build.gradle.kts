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
    testLogging { events("failed", "skipped") }
}

tasks.bootJar { archiveFileName = "reservation-server-${project.version}.jar" }

tasks.jar { enabled = false }

tasks.bootRun { workingDir = rootProject.projectDir }

spotless { java { googleJavaFormat("1.28.0") } }
