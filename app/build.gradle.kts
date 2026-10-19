plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "in.basulabs.siesattendance"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "in.basulabs.siesattendance"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // The enums, AcademicRules and the filter objects, compiled once and used by both the
    // app and the server. See settings.gradle.kts.
    implementation(project(":shared"))

    implementation(libs.room.runtime)
    // Draining the outbox. The only new runtime dependency Phase 6 adds: everything else
    // the sync path needs -- HTTP, JSON, AES-GCM through the Keystore -- is in the platform.
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    annotationProcessor(libs.room.compiler)

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    testImplementation(libs.junit)
    // The named query cases the SQLite and PostgreSQL dumps are both generated from.
    testImplementation(testFixtures(project(":shared")))
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}