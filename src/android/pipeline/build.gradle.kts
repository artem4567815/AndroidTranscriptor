plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hackuna.matata.pipeline"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:audio"))
    implementation(project(":core:data"))
    implementation(project(":ml:runtime"))
    implementation(project(":ml:asr"))
    implementation(project(":ml:diarization"))
    implementation(project(":ml:llm"))
    implementation(libs.koin.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
