import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// This machine's own settings: sdk.dir, and the two sync addresses below. Never committed. Gradle
// does not read this file as properties itself, so it is loaded here, through fileContents so
// that the configuration cache notices when it changes.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(it.reader()) }
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
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Where the server is: R.string.sync_base_url, read by SyncClient and SyncSignIn.
        //
        // Both addresses come from -P for one build, else local.properties, which is never
        // committed: the repository is public, and nothing in it names a server.
        //
        // Empty means no server. The app then works entirely on its own:
        // SyncClient.isConfigured() is false, nothing is scheduled, and no screen offers to
        // sign in. Empty also disarms the placeholder pins in network_security_config.xml,
        // which would otherwise refuse every connection.
        //
        debug {
            // http, not https: the development server has no certificate, which is why the
            // debug network config permits cleartext. 10.0.2.2 is how the emulator reaches
            // the machine it runs on, and the default. For a real phone on the same wifi,
            // set sies.debugSyncUrl to this machine's address there, found with
            //     ip route get PHONE_ADDRESS | grep -oP 'src \K\S+'
            // and not "ip route get 1", which names a VPN's address whenever one is up.
            resValue("string", "sync_base_url",
                providers.gradleProperty("sies.debugSyncUrl").orNull
                    ?: localProperties.getProperty("sies.debugSyncUrl")
                    ?: "http://10.0.2.2:8080/api/v1")
        }
        release {
            // https only: the release network config refuses cleartext, and its pins are
            // for the host named here. Set the pins before the address, or the first
            // request fails loudly, which is the right order for the two.
            resValue("string", "sync_base_url",
                providers.gradleProperty("sies.releaseSyncUrl").orNull
                    ?: localProperties.getProperty("sies.releaseSyncUrl")
                    ?: "")
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
        // For sync_base_url, set per build type above.
        resValues = true
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