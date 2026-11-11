plugins { id("com.android.application") }

android {
    namespace = "ai.pleos.assistant.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pleos.assistant.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-probe"
    }
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
    testImplementation("junit:junit:4.13.2")
}
