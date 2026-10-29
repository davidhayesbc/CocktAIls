plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.davidhayesbc.cocktails.android"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    packaging {
        jniLibs {
            // ggml dynamically discovers libggml-cpu-*.so by enumerating nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.davidhayesbc.cocktails"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("COCKTAILS_VERSION_NAME").orNull ?: "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_MESSAGE_LOG_LEVEL=WARNING",
                    "-DBUILD_SHARED_LIBS=ON",
                )
            }
        }
    }

    sourceSets["main"].assets.srcDir(rootProject.file("catalog-packs"))

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime)
    implementation("org.opencv:opencv:4.10.0")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    providers.gradleProperty("bottleEvalCases").orNull?.let { systemProperty("bottle.eval.cases", it) }
    providers.gradleProperty("bottleEvalRuns").orNull?.let { systemProperty("bottle.eval.runs", it) }
    providers.gradleProperty("bottleEvalReport").orNull?.let { systemProperty("bottle.eval.report", it) }
    providers.gradleProperty("bottleEvalLocalServer").orNull?.let { systemProperty("bottle.eval.local.server", it) }
    providers.gradleProperty("bottleEvalLocalCaseIds").orNull?.let { systemProperty("bottle.eval.local.caseIds", it) }
    providers.gradleProperty("bottleEvalLocalRuntime").orNull?.let { systemProperty("bottle.eval.local.runtime", it) }
    providers.gradleProperty("bottleEvalLocalBackend").orNull?.let { systemProperty("bottle.eval.local.backend", it) }
    providers.gradleProperty("bottleEvalModelSha256").orNull?.let { systemProperty("bottle.eval.local.model.sha256", it) }
    providers.gradleProperty("bottleEvalLlamaCppCommit").orNull?.let { systemProperty("bottle.eval.local.llama.commit", it) }
}
