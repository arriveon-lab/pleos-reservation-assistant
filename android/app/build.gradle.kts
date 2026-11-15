plugins { id("com.android.application") }

val drivingVoiceTest =
    providers.gradleProperty("drivingVoiceTest").orElse("false").get().toBoolean()

android {
    namespace = "ai.pleos.assistant.app"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        val submissionDemo =
            providers.gradleProperty("submissionDemo").orElse("false").get().toBoolean()
        val pairedPhoneRelease =
            providers.gradleProperty("pairedPhoneRelease").orElse("false").get().toBoolean()
        if (drivingVoiceTest) {
            require(
                listOf(
                        "submissionDemo",
                        "pairedPhoneRelease",
                        "pairedPhoneDemo",
                        "realPlaceDemo",
                        "phoneTrialDial",
                        "filmingDemo",
                        "mockDesignValidation",
                        "mockUiValidation",
                    )
                    .none { providers.gradleProperty(it).orNull.toBoolean() }
            ) {
                "The driving voice test must use its own MOCK package"
            }
        }
        if (submissionDemo) {
            require(
                listOf("phoneTrialDial", "realPlaceDemo", "pairedPhoneDemo", "pairedPhoneRelease")
                    .none { providers.gradleProperty(it).orNull.toBoolean() }
            ) {
                "submissionDemo supports only the MOCK reservation flow"
            }
        }
        if (pairedPhoneRelease) {
            require(
                listOf("phoneTrialDial", "mockPhoneDemo", "ec2MockDemo", "filmingDemo").none {
                    providers.gradleProperty(it).orNull.toBoolean()
                }
            ) {
                "pairedPhoneRelease supports only the paired HTTPS phone flow"
            }
        }
        buildConfigField("boolean", "SUBMISSION_DEMO", submissionDemo.toString())
        buildConfigField("boolean", "PAIRED_PHONE_RELEASE", pairedPhoneRelease.toString())
        val reviewKey = providers.environmentVariable("ARRIVEON_REVIEW_KEY").orElse("").get()
        require(reviewKey.isEmpty() || reviewKey.matches(Regex("[a-f0-9]{64}"))) {
            "ARRIVEON_REVIEW_KEY must contain 64 lowercase hexadecimal characters"
        }
        buildConfigField("String", "PAIRING_REVIEW_KEY", "\"$reviewKey\"")
        buildConfigField(
            "boolean",
            "PHONE_TRIAL_DIAL",
            providers.gradleProperty("phoneTrialDial").orElse("false").get().toBoolean().toString(),
        )
        applicationId = "ai.pleos.assistant.mock"
        manifestPlaceholders["appLabel"] =
            if (pairedPhoneRelease) "@string/app_name_paired_release" else "@string/app_name"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner =
            if (providers.gradleProperty("demoFlowChecks").orElse("false").get().toBoolean())
                "ai.pleos.assistant.app.DemoFlowDeviceChecks"
            else if (providers.gradleProperty("phoneTrialChecks").orElse("false").get().toBoolean())
                "ai.pleos.assistant.app.PhoneTrialDeviceChecks"
            else "ai.pleos.assistant.app.DrivingDeviceChecks"
        buildConfigField(
            "boolean",
            "NAVIGATION_DEMO",
            (submissionDemo ||
                    pairedPhoneRelease ||
                    providers.gradleProperty("navigationDemo").orElse("false").get().toBoolean())
                .toString(),
        )
        buildConfigField(
            "boolean",
            "MOCK_PHONE_DEMO",
            (submissionDemo ||
                    drivingVoiceTest ||
                    providers.gradleProperty("mockPhoneDemo").orElse("false").get().toBoolean())
                .toString(),
        )
        buildConfigField(
            "boolean",
            "EC2_MOCK_DEMO",
            (submissionDemo ||
                    drivingVoiceTest ||
                    providers.gradleProperty("ec2MockDemo").orElse("false").get().toBoolean())
                .toString(),
        )
        if (providers.gradleProperty("ec2MockDemo").orElse("false").get().toBoolean()) {
            applicationId =
                if (providers.gradleProperty("mockUiValidation").orElse("false").get().toBoolean())
                    "ai.pleos.assistant.mock.uivalidation"
                else "ai.pleos.assistant.mock.ec2validation"
        }
        val realPlaceDemo =
            pairedPhoneRelease ||
                providers.gradleProperty("realPlaceDemo").orElse("false").get().toBoolean()
        buildConfigField("boolean", "REAL_PLACE_DEMO", realPlaceDemo.toString())
        if (realPlaceDemo) {
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
        if (providers.gradleProperty("mockDesignValidation").orElse("false").get().toBoolean()) {
            require(providers.gradleProperty("ec2MockDemo").orNull == "true")
            applicationId = "ai.pleos.assistant.mock.designvalidation"
        }
        val pairedPhone =
            pairedPhoneRelease ||
                providers.gradleProperty("pairedPhoneDemo").orElse("false").get().toBoolean()
        buildConfigField("boolean", "PAIRED_PHONE_DEMO", pairedPhone.toString())
        if (pairedPhone) {
            require(realPlaceDemo) { "pairedPhoneDemo requires realPlaceDemo" }
            applicationId =
                if (pairedPhoneRelease) "ai.pleos.assistant.pairedphone.release"
                else "ai.pleos.assistant.pairedphone"
        }
        if (submissionDemo) applicationId = "ai.pleos.assistant.submission"
        if (drivingVoiceTest) {
            applicationId = "ai.pleos.assistant.drivingvoice"
            manifestPlaceholders["appLabel"] = "@string/driving_voice_test_name"
            resValue("string", "driving_voice_test_name", "아리온")
        }
        val trialReservationName =
            providers.gradleProperty("liveTestReservationName").orElse("가상 시험자").get().trim()
        require(trialReservationName.matches(Regex("[\\p{L}\\p{M} -]{1,40}"))) {
            "liveTestReservationName must contain 1–40 letters, spaces, or hyphens"
        }
        resValue("string", "live_test_reservation_name", trialReservationName)
        val filmingDemo = providers.gradleProperty("filmingDemo").orElse("false").get().toBoolean()
        if (filmingDemo) {
            require(
                applicationId == "ai.pleos.assistant.mock.designvalidation" &&
                    providers.gradleProperty("mockPhoneDemo").orNull == "true" &&
                    providers.gradleProperty("realPlaceDemo").orNull != "true" &&
                    providers.gradleProperty("phoneTrialDial").orNull != "true"
            ) {
                "filmingDemo requires the isolated EC2 MOCK phone build"
            }
        }
        buildConfigField("boolean", "FILMING_DEMO", (filmingDemo || submissionDemo).toString())
        versionCode = if (pairedPhoneRelease) 3 else if (pairedPhone) 2 else 1
        versionName =
            when {
                pairedPhoneRelease -> "0.2.1-live-test"
                pairedPhone -> "0.2.0-paired-test"
                else -> "0.1.0-mock"
            }
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

if (drivingVoiceTest) {
    android.sourceSets.getByName("debug").apply {
        java.srcDir("src/drivingVoiceTest/java")
        res.srcDir("src/drivingVoiceTest/res")
        manifest.srcFile("src/drivingVoiceTest/AndroidManifest.xml")
    }
    androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
        it.enable = false
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
val gleoSchema =
    file(if (drivingVoiceTest) "src/drivingVoiceTest/schema.json" else "src/main/gleo/schema.json")
val generateGleoSchema by
    tasks.registering {
        inputs.file(gleoSchema)
        inputs.property("applicationId", provider { android.defaultConfig.applicationId!! })
        outputs.dir(gleoAssets)
        doLast {
            val output = gleoAssets.get().file("schema.json").asFile
            output.parentFile.mkdirs()
            output.writeText(
                gleoSchema
                    .readText()
                    .replace("@APPLICATION_ID@", android.defaultConfig.applicationId!!)
            )
        }
    }

android.sourceSets.getByName("main").assets.srcDir(gleoAssets)

tasks.named("preBuild").configure { dependsOn(generateGleoSchema) }
