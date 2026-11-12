plugins { id("com.android.application") }

android {
    namespace = "ai.pleos.assistant.app"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "ai.pleos.assistant.mock"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "ai.pleos.assistant.app.DrivingDeviceChecks"
        buildConfigField(
            "boolean",
            "NAVIGATION_DEMO",
            providers.gradleProperty("navigationDemo").orElse("false").get().toBoolean().toString(),
        )
        buildConfigField(
            "boolean",
            "MOCK_PHONE_DEMO",
            providers.gradleProperty("mockPhoneDemo").orElse("false").get().toBoolean().toString(),
        )
        buildConfigField(
            "boolean",
            "EC2_MOCK_DEMO",
            providers.gradleProperty("ec2MockDemo").orElse("false").get().toBoolean().toString(),
        )
        if (providers.gradleProperty("ec2MockDemo").orElse("false").get().toBoolean()) {
            applicationId =
                if (providers.gradleProperty("mockUiValidation").orElse("false").get().toBoolean())
                    "ai.pleos.assistant.mock.uivalidation"
                else "ai.pleos.assistant.mock.ec2validation"
        }
        buildConfigField(
            "boolean",
            "REAL_PLACE_DEMO",
            providers.gradleProperty("realPlaceDemo").orElse("false").get().toBoolean().toString(),
        )
        if (providers.gradleProperty("realPlaceDemo").orElse("false").get().toBoolean()) {
            applicationId =
                if (
                    providers
                        .gradleProperty("realPlaceFilterValidation")
                        .orElse("false")
                        .get()
                        .toBoolean()
                )
                    "ai.pleos.assistant.mock.realplacefiltervalidation"
                else "ai.pleos.assistant.mock.realplacevalidation"
        }
        versionCode = 1
        versionName = "0.1.0-mock"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation(project(":ux:core"))
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
}

// Use the actual application ID also for isolated validation installations.
val gleoAssets = layout.buildDirectory.dir("generated/gleoAssets")
val generateGleoSchema by
    tasks.registering {
        inputs.file("src/main/gleo/schema.json")
        inputs.property("applicationId", provider { android.defaultConfig.applicationId!! })
        outputs.dir(gleoAssets)
        doLast {
            val output = gleoAssets.get().file("schema.json").asFile
            output.parentFile.mkdirs()
            output.writeText(
                file("src/main/gleo/schema.json")
                    .readText()
                    .replace("@APPLICATION_ID@", android.defaultConfig.applicationId!!)
            )
        }
    }

android.sourceSets.getByName("main").assets.srcDir(gleoAssets)

tasks.named("preBuild").configure { dependsOn(generateGleoSchema) }
