plugins { `java-library` }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    // Android supplies org.json at runtime; the JVM harness uses the same public API.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test { useJUnitPlatform() }

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

tasks.test {
    dependsOn(":server:bootJar")
    systemProperty(
        "mockServerJar",
        rootProject.file("server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar").absolutePath,
    )
}
