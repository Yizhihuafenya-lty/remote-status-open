import java.util.Properties

val relayProperties = Properties().apply {
    val configFile = rootProject.file("relay.properties")
    if (configFile.isFile) {
        configFile.inputStream().use { load(it) }
    }
}

fun relayProperty(name: String, fallback: String): String =
    relayProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: fallback

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val appToken = relayProperty("APP_TOKEN", "replace-with-app-token")
val relayV4Scheme = relayProperty("RELAY_V4_SCHEME", "https")
val relayV4Host = relayProperty("RELAY_V4_HOST", "relay-v4.example.com")
val relayV4Port = relayProperty("RELAY_V4_PORT", "50001").toIntOrNull() ?: 50001
val relayV6Scheme = relayProperty("RELAY_V6_SCHEME", "https")
val relayV6Host = relayProperty("RELAY_V6_HOST", "relay-v6.example.com")
val relayV6Port = relayProperty("RELAY_V6_PORT", "50001").toIntOrNull() ?: 50001

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.remotestatus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.remotestatus"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-open"

        buildConfigField("String", "APP_TOKEN", buildConfigString(appToken))
        buildConfigField("String", "RELAY_V4_SCHEME", buildConfigString(relayV4Scheme))
        buildConfigField("String", "RELAY_V4_HOST", buildConfigString(relayV4Host))
        buildConfigField("int", "RELAY_V4_PORT", relayV4Port.toString())
        buildConfigField("String", "RELAY_V6_SCHEME", buildConfigString(relayV6Scheme))
        buildConfigField("String", "RELAY_V6_HOST", buildConfigString(relayV6Host))
        buildConfigField("int", "RELAY_V6_PORT", relayV6Port.toString())
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Test builds are signed with the debug key so they install straight over adb.
            // Replace with a real release keystore before distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
