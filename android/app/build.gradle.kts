plugins { id("com.android.application") }

android {
    namespace = "ai.pleos.assistant.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pleos.assistant.mock"
        minSdk = 28
        targetSdk = 35
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
    implementation(project(":ux:core"))
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
}
