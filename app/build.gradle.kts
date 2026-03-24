import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load API keys from local.properties
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Inject secrets as BuildConfig fields (add these to local.properties)
        buildConfigField("String", "OPENAI_API_KEY",
            "\"${localProperties["OPENAI_API_KEY"] ?: ""}\"")
    }

    aaptOptions {
        noCompress("onnx")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.onnxruntime)
    implementation(libs.play.services.auth)
}
