import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

android {
    namespace = "app.immcloudscale"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.immcloudscale"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing: storeFile / storePassword / keyAlias / keyPassword in keystore.properties
    // at the project root (git-ignored). Without it the release APK is built unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        // CI: a stable key from repository secrets, so each published APK installs over the previous one.
        System.getenv("ICS_KEYSTORE_FILE")?.takeIf { file(it).exists() }?.let { ks ->
            create("stable") {
                storeFile = file(ks)
                storePassword = System.getenv("ICS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ICS_KEY_ALIAS")
                keyPassword = System.getenv("ICS_KEY_PASSWORD") ?: System.getenv("ICS_KEYSTORE_PASSWORD")
            }
        }
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            (signingConfigs.findByName("release") ?: signingConfigs.findByName("stable"))?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads Android runtime jars at test time; Google's Maven Central mirror is not rate limited.
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            // Optional live check against a running ics-server: ICS_E2E_SERVER=http://host:8790 ICS_E2E_CODE=XXXX-XXXX
            System.getenv("ICS_E2E_SERVER")?.let { v -> it.environment("ICS_E2E_SERVER", v) }
            System.getenv("ICS_E2E_CODE")?.let { v -> it.environment("ICS_E2E_CODE", v) }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("io.coil-kt.coil3:coil-video:3.0.4")

    // QR scanning for server pairing (Google Play services; no camera permission needed)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Userspace WireGuard (wireguard-go) behind Android's VpnService
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // Not needed with LEGACY graphics/SQLite modes; keeps the test classpath small.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
