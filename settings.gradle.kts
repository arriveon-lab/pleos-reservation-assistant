pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.springframework.boot") version "3.5.16"
        id("com.diffplug.spotless") version "7.0.4"
        id("com.android.application") version "8.9.2"
    }
}

// Machine-local opt-in lets Android Studio sync the same root project without CLI arguments.
val localBuildProperties =
    java.util.Properties().apply {
        val config = rootDir.resolve("gradle-local.properties")
        if (config.isFile) config.inputStream().use { load(it) }
    }
val includeAndroid =
    (providers.gradleProperty("includeAndroid").orNull
            ?: localBuildProperties.getProperty("includeAndroid", "false"))
        .toBooleanStrict()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (includeAndroid) {
            google()
            maven {
                url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
                content { includeGroupByRegex("ai\\.pleos.*") }
            }
        }
    }
}

rootProject.name = "pleos-reservation-assistant"

include(":server")

include(":ux:core")

// Server contributors and CI do not need an Android SDK or Pleos account.
if (includeAndroid) {
    include(":android:probe")
    include(":android:app")
}
