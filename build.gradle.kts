plugins {
    base
    id("com.diffplug.spotless")
}

// Source checks remain available to server contributors without an Android SDK.
spotless {
    java {
        target("android/*/src/**/*.java")
        googleJavaFormat("1.28.0")
    }
    kotlin {
        target("android/*/src/**/*.kt")
        ktfmt("0.54").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "server/*.gradle.kts", "android/*/*.gradle.kts")
        ktfmt("0.54").kotlinlangStyle()
    }
}

tasks.register("format") {
    group = "formatting"
    description = "Format Java, Kotlin and Gradle sources; no Android SDK required."
    dependsOn("spotlessApply", ":server:spotlessApply")
}

tasks.register("verify") {
    group = "verification"
    description =
        "Check all source formatting, test and package the MOCK server; no Android SDK required."
    dependsOn("spotlessCheck", ":server:check", ":server:bootJar")
}

tasks.register("verifyAndroid") {
    group = "verification"
    description =
        "Check Android formatting, lint, unit tests and debug APK; requires Android opt-in and SDK."
    dependsOn("spotlessCheck")
    if (findProject(":android:probe") != null) {
        dependsOn(
            ":android:probe:lintDebug",
            ":android:probe:testDebugUnitTest",
            ":android:probe:assembleDebug",
        )
    } else {
        doFirst {
            throw GradleException(
                "Android is disabled. Run python3 scripts/setup-android.py --configure, or pass -PincludeAndroid=true."
            )
        }
    }
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Run both server and Android checks. Device operation is verified separately."
    dependsOn("verify", "verifyAndroid")
}

tasks.named("check") { dependsOn("spotlessCheck", ":server:check") }

tasks.named("assemble") { dependsOn(":server:bootJar") }
