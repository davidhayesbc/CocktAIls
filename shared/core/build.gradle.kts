plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("app.cash.sqldelight:runtime:${libs.versions.sqldelight.get()}")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:${libs.versions.sqldelight.get()}")
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:${libs.versions.sqldelight.get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}")
        }
    }
}

android {
    namespace = "com.davidhayesbc.cocktails.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

sqldelight {
    databases {
        create("CocktailsDatabase") {
            packageName.set("com.davidhayesbc.cocktails.shared.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}
