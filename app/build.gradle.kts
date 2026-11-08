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
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "in.basulabs.siesattendance"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "2.0.0-alpha.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key, supplied only by the release workflow (.github/workflows/release.yml),
    // which decodes the keystore from a repository secret. Without these variables a local
    // release build is still produced, just unsigned, so nothing about the key has to live
    // on a development machine.
    fun requiredEnv(name: String): String =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("SIES_KEYSTORE_FILE is set but $name is missing or empty.")
    val releaseKeystore = providers.environmentVariable("SIES_KEYSTORE_FILE").orNull
    val releaseSigning = releaseKeystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = requiredEnv("SIES_KEYSTORE_PASSWORD")
            keyAlias = requiredEnv("SIES_KEY_ALIAS")
            keyPassword = requiredEnv("SIES_KEY_PASSWORD")
        }
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
            // A package of its own, so a debug build -- and the instrumented tests, which
            // install it and uninstall it again after every run -- sits beside a release
            // install instead of having to replace it, along with the attendance it holds.
            applicationIdSuffix = ".debug"

            // http, not https: the development server has no certificate, which is why the
            // debug network config permits cleartext. 10.0.2.2 is how the emulator reaches
            // the machine it runs on, and the default. For a real phone on the same wifi,
            // set sies.debugSyncUrl to this machine's address there, found with
            //     ip route get PHONE_ADDRESS | grep -oP 'src \K\S+'
            // and not "ip route get 1", which names a VPN's address whenever one is up.
            resValue("string", "sync_base_url",
                providers.gradleProperty("sies.debugSyncUrl").orNull
                    ?: localProperties.getProperty("sies.debugSyncUrl")
                    ?: "")
        }
        release {
            // https only: the release network config refuses cleartext, and its pins are
            // for the host named here. Set the pins before the address, or the first
            // request fails loudly, which is the right order for the two.
            resValue("string", "sync_base_url",
                providers.gradleProperty("sies.releaseSyncUrl").orNull
                    ?: localProperties.getProperty("sies.releaseSyncUrl")
                    ?: "")
            releaseSigning?.let { signingConfig = it }
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
    // The same named query cases, run by the instrumented tests on the phone's own SQLite.
    androidTestImplementation(testFixtures(project(":shared")))
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}